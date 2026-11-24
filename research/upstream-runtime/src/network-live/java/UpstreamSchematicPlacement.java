import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.geom.QuadTree;
import arc.math.geom.Rect;
import arc.struct.Seq;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.InflaterInputStream;
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
    private Function<String, String> editName;
    private Function<String, String> editDescription;
    private boolean editing;

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
        editing = false;
        refreshLabels(null);
    }

    void tap(int x, int y) {
        if (x >= 152 && x < 216 && y >= 4 && y < 38 && editName != null) {
            editing = !editing;
            refreshLabels(null);
        } else if (x >= 224 && x < 312 && y >= 4 && y < 38) {
            editing = false;
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
            if (index < files.length) {
                if (editing) edit(files[index]);
                else if (Vars.state.rules.schematicsAllowed) select(files[index]);
            }
        }
    }

    void editWith(Function<String, String> name, Function<String, String> description) {
        editName = name;
        editDescription = description;
    }

    private void edit(Fi file) {
        editing = false;
        try {
            if (editMetadata(file, editName, editDescription)) {
                System.out.println("UPSTREAM_SCHEMATIC_METADATA_EDITED file=" + file.name());
            }
            refreshLabels(null);
        } catch (IOException | RuntimeException error) {
            refreshLabels(null, Core.bundle.get("error", "Error"));
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
        String title = editing ? Core.bundle.get("schematic.edit", "Edit schematic")
            : Core.bundle.get("schematics", "Schematics");
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
                String name = readDisplayName(file);
                text.append((name == null ? file.nameWithoutExtension() : name)
                    .replace('\n', ' ').replace('\r', ' ').replace('\0', ' '));
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

    boolean editMetadata(Fi file, Function<String, String> nameInput,
                         Function<String, String> descriptionInput) throws IOException {
        if (!file.parent().equals(directory) || !file.extEquals(Vars.schematicExtension)
                || !file.file().isFile()) return false;
        Schematic schematic = readSchematic(file);
        String name = nameInput.apply(schematic.name());
        if (name == null || name.isEmpty()) return false;
        String description = descriptionInput.apply(schematic.description());
        if (description == null) return false;
        schematic.tags.put("name", name);
        schematic.tags.put("description", description);
        publish(schematic, file, true);
        return true;
    }

    private static String readDisplayName(Fi file) {
        try (var input = file.read(1024)) {
            // The pinned Schematics reader starts with msch, a version byte,
            // then compressed dimensions and tags. Listing needs only those tags.
            if (input.read() != 'm' || input.read() != 's' || input.read() != 'c' || input.read() != 'h') {
                return null;
            }
            int version = input.read();
            if (version < 0 || version > 1) return null;
            try (var metadata = new DataInputStream(new InflaterInputStream(input))) {
                metadata.readShort();
                metadata.readShort();
                int count = metadata.readUnsignedByte();
                for (int i = 0; i < count; i++) {
                    String key = metadata.readUTF();
                    String value = metadata.readUTF();
                    if (key.equals("name")) return value;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Broken files can still be identified in the picker by their filenames.
        }
        return null;
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
            if (file.isDirectory() || !file.extEquals(Vars.schematicExtension) || !name.equals(readDisplayName(file))) continue;
            Schematic candidate;
            try {
                candidate = readSchematic(file);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
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
        publish(schematic, destination, previous != null);
        return destination;
    }

    private void publish(Schematic schematic, Fi destination, boolean replacing) throws IOException {
        File temporary = File.createTempFile("schematic-", ".tmp", directory.file());
        try {
            Schematics.write(schematic, new Fi(temporary));
            if (!replacing) {
                if (destination.exists() || !temporary.renameTo(destination.file())) {
                    throw new IOException("Unable to publish " + destination.name());
                }
            } else if (!temporary.renameTo(destination.file())) {
                replaceOnFat(temporary, destination.file());
            }
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
