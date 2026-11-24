import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.geom.QuadTree;
import arc.math.geom.Rect;
import arc.struct.Seq;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.function.Supplier;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.input.Placement;
import mindustry.world.Build;

/** Uses the pinned schematic reader, transforms, and construction plans behind the touch picker. */
final class UpstreamSchematicPlacement {
    private final Fi directory;
    private final int[] icons = new int[UpstreamBuildMenu.ROWS];
    private final Rect footprint = new Rect();
    private Fi[] files;
    private byte[] labels;
    private int page;
    private boolean open;
    private Schematics stock;
    private Schematic selected;
    private Seq<BuildPlan> preview = new Seq<>();
    private int turns, anchorX = -1, anchorY = -1;
    private boolean capturing;
    private int cornerX = -1, cornerY = -1;
    private Supplier<String> nameInput;
    private Predicate<String> confirmReplacement = name -> false;

    UpstreamSchematicPlacement(Fi directory) {
        this.directory = directory;
        Arrays.fill(icons, -1);
    }

    void open() {
        files = Arrays.stream(directory.list()).filter(file -> !file.isDirectory()
            && file.extEquals(Vars.schematicExtension)).toArray(Fi[]::new);
        Arrays.sort(files, Comparator.comparing(Fi::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Fi::name));
        page = 0;
        open = true;
        refreshLabels(null);
    }

    void tap(int x, int y) {
        if (x >= 224 && x < 312 && y >= 4 && y < 38) {
            open = false;
            files = null;
            capturing = true;
        } else if (y >= UpstreamBuildMenu.FOOTER_TOP) {
            if (x < 100 && previousPage()) {
                page--;
                refreshLabels(null);
            } else if (x >= 220 && nextPage()) {
                page++;
                refreshLabels(null);
            } else if (x >= 100 && x < 220) {
                cancel();
            }
        } else if (x >= 8 && x < 312 && y >= UpstreamBuildMenu.ROW_TOP
                && y < UpstreamBuildMenu.ROW_TOP + UpstreamBuildMenu.ROWS * UpstreamBuildMenu.ROW_HEIGHT) {
            int index = page * UpstreamBuildMenu.ROWS + (y - UpstreamBuildMenu.ROW_TOP) / UpstreamBuildMenu.ROW_HEIGHT;
            if (index < files.length && Vars.state.rules.schematicsAllowed) select(files[index]);
        }
    }

    private void select(Fi file) {
        try {
            selected = readSchematic(file);
        } catch (IOException | RuntimeException error) {
            refreshLabels(file.name());
            return;
        }
        open = false;
        files = null;
        System.out.println("UPSTREAM_SCHEMATIC_SELECTED blocks=" + selected.tiles.size
            + " width=" + selected.width + " height=" + selected.height);
    }

    private void refreshLabels(String invalidFile) {
        refreshLabels(invalidFile, null);
    }

    private void refreshLabels(String invalidFile, String notice) {
        String title = Core.bundle.get("schematics", "Schematics");
        var text = new StringBuilder(notice == null ? title : notice);
        if (notice == null) {
            if (!Vars.state.rules.schematicsAllowed) {
                text.append(" (disabled)");
            } else if (files.length == 0) {
                text.append(" (no .msch files)");
            } else {
                text.append("  ").append(page + 1).append('/').append(1 + (files.length - 1) / UpstreamBuildMenu.ROWS);
            }
        }
        text.append('\0');
        for (int row = 0; row < UpstreamBuildMenu.ROWS; row++) {
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (index < files.length) {
                var file = files[index];
                text.append(displayName(file).replace('\n', ' ').replace('\r', ' ').replace('\0', ' '));
                if (file.name().equals(invalidFile)) text.append(" (invalid .msch)");
            }
            text.append('\0');
        }
        labels = text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Schematic readSchematic(Fi file) throws IOException {
        try (var input = file.read(1024)) {
            return Schematics.read(input);
        }
    }

    private static String displayName(Fi file) {
        try {
            return readSchematic(file).tags.get("name", file.nameWithoutExtension());
        } catch (IOException | RuntimeException ignored) {
            return file.nameWithoutExtension();
        }
    }

    void position(Unit unit, int x, int y) {
        anchorX = x;
        anchorY = y;
        preview = stock().toPlans(Schematics.rotate(selected, turns), x, y);
        var existing = new QuadTree<BuildPlan>(new Rect(-Vars.tilesize, -Vars.tilesize,
            (Vars.world.width() + 2) * Vars.tilesize, (Vars.world.height() + 2) * Vars.tilesize));
        for (BuildPlan placed : unit.plans()) {
            if (!placed.breaking) existing.insert(placed);
        }
        for (BuildPlan plan : preview) {
            plan.cachedValid = canQueue(unit, plan, existing);
            if (plan.cachedValid) existing.insert(plan);
        }
        System.out.println("UPSTREAM_SCHEMATIC_PREVIEW x=" + x + " y=" + y + " turns=" + turns + " plans=" + preview.size);
    }

    private boolean canQueue(Unit unit, BuildPlan plan, QuadTree<BuildPlan> existing) {
        if (!Build.validPlaceIgnoreUnits(plan.block, unit.team(), plan.x, plan.y, plan.rotation, true, true)) return false;
        Rect bounds = plan.bounds(footprint);
        return existing.find(bounds.x, bounds.y, bounds.width, bounds.height,
            old -> !(old.x == plan.x && old.y == plan.y && plan.block.canReplace(old.block))) == null;
    }

    void rotate(Unit unit) {
        turns = (turns + 1) % 4;
        if (anchorX >= 0) position(unit, anchorX, anchorY);
    }

    void submit(Unit unit) {
        if (anchorX < 0 || !unit.canBuild() || !Vars.state.rules.schematicsAllowed) return;
        position(unit, anchorX, anchorY);
        int count = 0;
        for (BuildPlan plan : preview) {
            if (!plan.cachedValid) continue;
            plan.block.onNewPlan(plan);
            unit.addBuild(plan);
            count++;
        }
        System.out.println("UPSTREAM_SCHEMATIC_QUEUED count=" + count + " turns=" + turns);
        if (count > 0) cancel();
    }

    private Schematics stock() {
        if (stock == null) stock = new Schematics();
        return stock;
    }

    void captureCorner(int x, int y) {
        if (anchorX < 0) {
            anchorX = x;
            anchorY = y;
        } else {
            cornerX = x;
            cornerY = y;
            System.out.println("UPSTREAM_SCHEMATIC_CAPTURE_AREA x=" + anchorX + " y=" + anchorY
                + " x2=" + cornerX + " y2=" + cornerY);
        }
    }

    void nameCapturesWith(Supplier<String> input, Predicate<String> confirm) {
        nameInput = input;
        confirmReplacement = confirm;
    }

    void saveCapture(Team team) {
        saveCapture(team, nameInput, confirmReplacement);
    }

    void saveCapture(Team team, Supplier<String> nameInput, Predicate<String> confirmReplacement) {
        if (cornerX < 0) return;
        var schematic = stock().create(anchorX, anchorY, cornerX, cornerY, team);
        String notice;
        if (schematic.tiles.isEmpty()) {
            notice = "No buildings in selection";
        } else {
            try {
                String name = nameInput == null ? availableCaptureFile().nameWithoutExtension() : nameInput.get();
                if (name == null || name.isBlank()) {
                    notice = null;
                } else {
                    var file = writeCapture(schematic, name, confirmReplacement);
                    notice = file == null ? null : "Saved " + schematic.name();
                    if (file != null) {
                        System.out.println("UPSTREAM_SCHEMATIC_SAVED file=" + file.name()
                            + " blocks=" + schematic.tiles.size + " width=" + schematic.width + " height=" + schematic.height);
                    }
                }
            } catch (IOException | RuntimeException error) {
                notice = "Could not save schematic";
                System.out.println("UPSTREAM_SCHEMATIC_SAVE_ERROR " + error.getMessage());
            }
        }
        cancel();
        open();
        refreshLabels(null, notice);
    }

    private Fi availableCaptureFile() {
        int index = 1;
        Fi destination;
        do {
            destination = directory.child("Schematic-" + index++ + "." + Vars.schematicExtension);
        } while (destination.exists());
        return destination;
    }

    private Fi writeCapture(Schematic schematic, String name, Predicate<String> confirmReplacement) throws IOException {
        directory.mkdirs();
        Fi destination = availableCaptureFile();
        Schematic previous = null;
        for (var file : directory.list()) {
            if (file.isDirectory() || !file.extEquals(Vars.schematicExtension)) continue;
            Schematic candidate;
            try {
                candidate = readSchematic(file);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            if (!name.equals(candidate.tags.get("name"))) continue;
            if (!confirmReplacement.test(name)) return null;
            destination = file;
            previous = candidate;
            break;
        }
        schematic.tags.put("name", name);
        schematic.tags.put("description", "");
        if (previous != null) {
            schematic.tags.putAll(previous.tags);
            schematic.labels = previous.labels;
        }
        File temporary = File.createTempFile("schematic-", ".tmp", directory.file());
        try {
            Schematics.write(schematic, new Fi(temporary));
            if (previous == null) {
                if (destination.exists() || !temporary.renameTo(destination.file())) {
                    throw new IOException("Unable to publish " + destination.name());
                }
            } else if (!temporary.renameTo(destination.file())) {
                replaceOnFat(temporary, destination.file());
            }
            return destination;
        } finally {
            temporary.delete();
        }
    }

    private static void replaceOnFat(File temporary, File destination) throws IOException {
        File backup = File.createTempFile("schematic-backup-", ".tmp", destination.getParentFile());
        if (!backup.delete() || !destination.renameTo(backup)) {
            throw new IOException("Unable to back up " + destination.getName());
        }
        if (temporary.renameTo(destination)) {
            backup.delete();
        } else if (backup.renameTo(destination)) {
            throw new IOException("Unable to replace " + destination.getName());
        } else {
            throw new IOException("Unable to restore " + destination.getName() + " from " + backup.getName());
        }
    }

    void draw(UpstreamTerrainDrawProbe.Viewport viewport) {
        if (capturing && anchorX >= 0) {
            var area = Placement.normalizeArea(anchorX, anchorY, cornerX < 0 ? anchorX : cornerX,
                cornerY < 0 ? anchorY : cornerY, 0, false, Vars.maxSchematicSize);
            int size = Vars.tilesize;
            Lines.stroke(1f, Pal.accent);
            Lines.rect(area.x * size - size / 2f, area.y * size - size / 2f,
                (area.x2 - area.x + 1) * size, (area.y2 - area.y + 1) * size);
            Draw.reset();
        }
        for (BuildPlan plan : preview) {
            if (!viewport.contains(plan.x, plan.y)) continue;
            Drawf.square(plan.drawx(), plan.drawy(), plan.block.size * Vars.tilesize / 2f - 0.5f,
                plan.cachedValid ? Pal.accent : Pal.remove);
        }
    }

    void cancel() {
        open = false;
        files = null;
        selected = null;
        preview.clear();
        turns = 0;
        anchorX = anchorY = cornerX = cornerY = -1;
        capturing = false;
    }

    boolean isOpen() { return open; }
    boolean positioning() { return selected != null; }
    boolean capturing() { return capturing; }
    boolean active() { return isOpen() || positioning() || capturing; }
    boolean previousPage() { return page > 0; }
    boolean nextPage() { return (page + 1) * UpstreamBuildMenu.ROWS < files.length; }
    byte[] labels() { return labels; }
    int[] icons() { return icons; }
}
