import arc.Core;
import arc.files.Fi;
import arc.math.geom.QuadTree;
import arc.math.geom.Rect;
import arc.struct.Seq;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
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
        if (y >= UpstreamBuildMenu.FOOTER_TOP) {
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
        try (var input = file.read(1024)) {
            selected = Schematics.read(input);
        } catch (IOException | RuntimeException error) {
            refreshLabels(file.name());
            return;
        }
        if (stock == null) stock = new Schematics();
        open = false;
        files = null;
        System.out.println("UPSTREAM_SCHEMATIC_SELECTED blocks=" + selected.tiles.size
            + " width=" + selected.width + " height=" + selected.height);
    }

    private void refreshLabels(String invalidFile) {
        String title = Core.bundle.get("schematics", "Schematics");
        var text = new StringBuilder(title);
        if (!Vars.state.rules.schematicsAllowed) {
            text.append(" (disabled)");
        } else if (files.length == 0) {
            text.append(" (no .msch files)");
        } else {
            text.append("  ").append(page + 1).append('/').append(1 + (files.length - 1) / UpstreamBuildMenu.ROWS);
        }
        text.append('\0');
        for (int row = 0; row < UpstreamBuildMenu.ROWS; row++) {
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (index < files.length) {
                var file = files[index];
                text.append(file.nameWithoutExtension().replace('\n', ' ').replace('\r', ' '));
                if (file.name().equals(invalidFile)) text.append(" (invalid .msch)");
            }
            text.append('\0');
        }
        labels = text.toString().getBytes(StandardCharsets.UTF_8);
    }

    void position(Unit unit, int x, int y) {
        anchorX = x;
        anchorY = y;
        preview = stock.toPlans(Schematics.rotate(selected, turns), x, y);
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

    void draw(UpstreamTerrainDrawProbe.Viewport viewport) {
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
        anchorX = anchorY = -1;
    }

    boolean isOpen() { return open; }
    boolean positioning() { return selected != null; }
    boolean active() { return isOpen() || positioning(); }
    boolean previousPage() { return page > 0; }
    boolean nextPage() { return (page + 1) * UpstreamBuildMenu.ROWS < files.length; }
    byte[] labels() { return labels; }
    int[] icons() { return icons; }
}
