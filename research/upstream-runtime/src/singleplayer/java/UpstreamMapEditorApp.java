import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.Seq;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.editor.Arm11MapEditor;
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
    private final UpstreamTerrainRenderProbe target;
    private final Fi source;
    private final Fi export;
    private final Fi mapDirectory;
    private final Arm11MapEditor editor = new Arm11MapEditor();
    private final Seq<Block> palette = new Seq<>();
    private UpstreamTerrainDrawProbe.Viewport viewport;
    private int x, y, selected;
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
        selected = Math.max(0, palette.indexOf(Blocks.stone, true));
        updateLabel(palette.get(selected).localizedName);
        UpstreamSinglePlayerInput.poll();
        int input;
        while ((input = UpstreamSinglePlayerInput.poll()) >= 0) {
            long now = System.nanoTime();
            move(input, now);
            if ((input & (1 << 18)) != 0) {
                int step = (input & UpstreamSinglePlayerInput.L_HELD) != 0 ? -1 : 1;
                selected = Mathf.mod(selected + step, palette.size);
                updateLabel(palette.get(selected).localizedName);
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
    }
}
