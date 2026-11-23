import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Strings;
import java.io.IOException;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.editor.Arm11MapEditor;
import mindustry.editor.MapEditor;
import mindustry.graphics.Pal;
import mindustry.io.MapIO;
import mindustry.world.Block;
import mindustry.world.blocks.environment.OverlayFloor;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.meta.BuildVisibility;

/** 3DS controller and viewport for the stock editor session. */
final class UpstreamMapEditorApp {
    private static final long FRAME_NS = 16_666_667L;
    private static final long CURSOR_REPEAT_NS = 125_000_000L;
    private static final int ROWS_PER_PAGE = 6;
    private static final int BRUSH_LABEL_INDEX = 6;
    private static final int NATIVE_PALETTE_LABELS = 16;
    private final UpstreamTerrainRenderProbe target;
    private final Fi source;
    private final Fi export;
    private final Fi mapDirectory;
    private final Arm11MapEditor editor = new Arm11MapEditor();
    private Seq<Block> palette;
    private UpstreamTerrainDrawProbe.Viewport viewport;
    private int x, y, selected;
    private int page;
    private int brushIndex;
    private boolean pickingBrush;
    private byte[] paletteLabels;
    private final int[] paletteIcons = new int[ROWS_PER_PAGE];
    private byte[] label;
    private int[] glyphs;
    private int lastX, lastY;
    private long nextMove;
    private int exportNumber = 1;

    UpstreamMapEditorApp(UpstreamTerrainRenderProbe target, Fi source, Fi export, Fi mapDirectory) {
        this.target = target;
        this.source = source;
        this.export = export;
        this.mapDirectory = mapDirectory;
    }

    void open() throws IOException {
        if (source.exists()) editor.beginEdit(MapIO.createMap(source, true));
        else editor.beginEdit(200, 200);
        x = editor.width() / 2;
        y = editor.height() / 2;
        System.out.println("UPSTREAM_EDITOR_OPEN source=" + (source.exists() ? source.name() : "new")
            + " width=" + editor.width() + " height=" + editor.height());
    }

    void run(UpstreamTerrainDrawProbe.Viewport viewport) {
        this.viewport = viewport;
        palette = stockPalette();
        selected = Math.max(0, palette.indexOf(Blocks.stone, true));
        page = selected / ROWS_PER_PAGE;
        updateSelectionLabel();
        refreshPalette();
        UpstreamSinglePlayerInput.poll();
        int input;
        while ((input = UpstreamSinglePlayerInput.poll()) >= 0) {
            long now = System.nanoTime();
            move(input, now);
            int touch = UpstreamSinglePlayerInput.touch();
            if (touch >= 0) selectTouch(touch & 0xffff, touch >>> 16);
            if ((input & (1 << 18)) != 0) {
                int step = (input & UpstreamSinglePlayerInput.L_HELD) != 0 ? -1 : 1;
                selected = Mathf.mod(selected + step, palette.size);
                if (!pickingBrush) page = selected / ROWS_PER_PAGE;
                updateSelectionLabel();
                refreshPalette();
            }
            if ((input & UpstreamSinglePlayerInput.A_PRESSED) != 0) stroke(false);
            if ((input & UpstreamSinglePlayerInput.B_PRESSED) != 0) stroke(true);
            if ((input & UpstreamSinglePlayerInput.X_PRESSED) != 0 && editor.canUndo()) {
                editor.undo();
                report("undo");
            }
            if ((input & UpstreamSinglePlayerInput.WAVE_PRESSED) != 0 && editor.canRedo()) {
                editor.redo();
                report("redo");
            }
            if ((input & UpstreamSinglePlayerInput.SAVE_PRESSED) != 0) save();
            viewport.moveTo(x - UpstreamTerrainDrawProbe.VIEW_WIDTH / 2,
                y - UpstreamTerrainDrawProbe.VIEW_HEIGHT / 2);
            viewport.draw(this::drawMap);
            while (System.nanoTime() - now < FRAME_NS) Thread.yield();
        }
    }

    static Seq<Block> stockPalette() {
        var palette = new Seq<Block>();
        for (Block block : Vars.content.blocks()) {
            if (block.inEditor && block.buildVisibility != BuildVisibility.debugOnly
                    && Core.atlas.isFound(block.uiIcon)) palette.add(block);
        }
        // Match the stock editor's core, environment, synthetic and overlay ordering.
        palette.sort((a, b) -> {
            int core = -Boolean.compare(a instanceof CoreBlock, b instanceof CoreBlock);
            if (core != 0) return core;
            int synthetic = Boolean.compare(a.synthetic(), b.synthetic());
            if (synthetic != 0) return synthetic;
            int overlay = Boolean.compare(a instanceof OverlayFloor, b instanceof OverlayFloor);
            return overlay != 0 ? overlay : Integer.compare(a.id, b.id);
        });
        return palette;
    }

    private void selectTouch(int atX, int atY) {
        if (atY >= 201 && atY < 235) {
            if (atX >= 104 && atX < 216) {
                pickingBrush = !pickingBrush;
                page = (pickingBrush ? brushIndex : selected) / ROWS_PER_PAGE;
            } else if (atX >= 8 && atX < 96) {
                changePage(-1);
            } else if (atX >= 224 && atX < 312) {
                changePage(1);
            }
        } else if (!pickingBrush && atY >= 4 && atY < 38) {
            if (atX >= 8 && atX < 62) changePage(-4);
            if (atX >= 258 && atX < 312) changePage(4);
        } else if (atX >= 8 && atX < 312 && atY >= 44 && atY < 194 && (atY - 44) % 25 < 23) {
            int index = page * ROWS_PER_PAGE + (atY - 44) / 25;
            if (index >= countRows()) return;
            if (pickingBrush) {
                brushIndex = index;
                editor.brushSize = MapEditor.brushSizes[index];
                pickingBrush = false;
                page = selected / ROWS_PER_PAGE;
                System.out.println("UPSTREAM_EDITOR_BRUSH radius=" + editor.brushSize);
            } else {
                selected = index;
                System.out.println("UPSTREAM_EDITOR_BLOCK block=" + palette.get(selected).name);
            }
            updateSelectionLabel();
        } else {
            return;
        }
        refreshPalette();
    }

    private int countRows() {
        return pickingBrush ? MapEditor.brushSizes.length : palette.size;
    }

    private void changePage(int direction) {
        page = Mathf.clamp(page + direction, 0, (countRows() - 1) / ROWS_PER_PAGE);
    }

    private void updateSelectionLabel() {
        updateLabel(palette.get(selected).localizedName + " / " + brushSize(brushIndex));
    }

    private static String brushSize(int index) {
        return Strings.autoFixed(MapEditor.brushSizes[index], 1);
    }

    private void refreshPalette() {
        String footer = Core.bundle.get(pickingBrush ? "back" : "editor.brush");
        String[] buttons = new String[NATIVE_PALETTE_LABELS];
        Arrays.fill(buttons, "");
        buttons[BRUSH_LABEL_INDEX] = footer;
        target.buildLabels((String.join("\0", buttons) + "\0").getBytes(StandardCharsets.UTF_8));

        var labels = new StringBuilder(Core.bundle.get(pickingBrush ? "editor.brush" : "editor"));
        labels.append("  ").append(page + 1).append('/').append((countRows() - 1) / ROWS_PER_PAGE + 1).append('\0');
        for (int row = 0; row < ROWS_PER_PAGE; row++) {
            int index = page * ROWS_PER_PAGE + row;
            paletteIcons[row] = -1;
            if (index < countRows()) {
                if (pickingBrush) {
                    labels.append(brushSize(index));
                } else {
                    var block = palette.get(index);
                    labels.append(block.localizedName);
                    paletteIcons[row] = block.id;
                }
            }
            labels.append('\0');
        }
        paletteLabels = labels.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void move(int input, long now) {
        int dx = axis((byte)input), dy = axis((byte)(input >> 8));
        if (dx != lastX || dy != lastY || now >= nextMove) {
            x = Mathf.clamp(x + dx, 0, editor.width() - 1);
            y = Mathf.clamp(y + dy, 0, editor.height() - 1);
            nextMove = now + CURSOR_REPEAT_NS;
        }
        lastX = dx;
        lastY = dy;
    }

    private static int axis(int value) {
        return value > 60 ? 1 : value < -60 ? -1 : 0;
    }

    private void stroke(boolean erase) {
        editor.stroke(x, y, palette.get(selected), erase);
        report(erase ? "erase" : "pencil");
    }

    private void report(String action) {
        var tile = editor.tile(x, y);
        System.out.println("UPSTREAM_EDITOR_STROKE action=" + action + " x=" + x + " y=" + y
            + " floor=" + tile.floor().name + " block=" + tile.block().name);
    }

    private void save() {
        Fi file = export;
        if (file == null) {
            do {
                file = mapDirectory.child("Edited-" + exportNumber++ + "." + Vars.mapExtension);
            } while (file.exists());
        }
        try {
            var parent = file.parent();
            if (parent != null && !parent.exists()) parent.mkdirs();
            String name = editor.tags.get("name", file.nameWithoutExtension());
            editor.export(file, name);
            updateLabel(Core.bundle.get("editor.saved"));
            System.out.println("UPSTREAM_EDITOR_EXPORTED file=" + file.name());
        } catch (IOException failure) {
            updateLabel(Core.bundle.get("editor.errorsave"));
            System.out.println("UPSTREAM_EDITOR_EXPORT_ERROR " + failure.getMessage());
        }
    }

    private void updateLabel(String detail) {
        String caption = Core.bundle.get("editor") + " / " + detail;
        label = (caption + "\0").getBytes(StandardCharsets.UTF_8);
        glyphs = UpstreamSinglePlayerApp.encodePlainMenuLabel(caption);
    }

    private void drawMap() {
        // EditorRenderer renders center tiles with the original full block icon and team overlay.
        for (int row = Math.max(0, viewport.bottom - Vars.maxBlockSize);
                row < Math.min(editor.height(), viewport.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT + Vars.maxBlockSize);
                row++) {
            for (int column = Math.max(0, viewport.left - Vars.maxBlockSize);
                    column < Math.min(editor.width(), viewport.left + UpstreamTerrainDrawProbe.VIEW_WIDTH + Vars.maxBlockSize);
                    column++) {
                var tile = editor.tile(column, row);
                var block = tile.block();
                if (tile.build == null || !tile.isCenter() || !viewport.contains(tile.build)) continue;
                float rotation = block.rotate && block.rotateDrawEditor ? tile.build.rotdeg() : 0f;
                Draw.rect(block.fullIcon, tile.drawx(), tile.drawy(), rotation);
                if (block.teamRegions != null && block.teamRegions[tile.build.team.id] != null) {
                    Draw.rect(block.teamRegions[tile.build.team.id], tile.drawx(), tile.drawy());
                }
            }
        }
        Draw.color(Pal.accent);
        Lines.stroke(0.5f);
        float half = Vars.tilesize / 2f;
        Lines.rect(x * Vars.tilesize - half, y * Vars.tilesize - half, Vars.tilesize, Vars.tilesize);
        Draw.reset();
        target.drawObjective(label, glyphs, null, 6f);
        target.buildOverlay(-1, -1, -1, 0, false, false, true, false, false, 0, 0, false, false);
        int active = (pickingBrush ? brushIndex : selected) - page * ROWS_PER_PAGE;
        target.buildCatalog(paletteLabels, paletteIcons, active < ROWS_PER_PAGE ? active : -1,
            page != 0, (page + 1) * ROWS_PER_PAGE < countRows(),
            pickingBrush ? UpstreamTerrainRenderProbe.ITEM_CATALOG : UpstreamTerrainRenderProbe.BLOCK_CATALOG);
    }
}
