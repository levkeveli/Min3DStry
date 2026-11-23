import arc.Events;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;

/** Adapts the touch grid to the stock InputHandler selection and command calls. */
final class UpstreamCommandOrders {
    private static final int COMMAND_BATCH_SIZE = 200;
    private final Seq<Unit> units = new Seq<>();
    private final Seq<Building> buildings = new Seq<>();
    private final Rect hitBox = new Rect();
    private final Rect touchBox = new Rect();
    private final UpstreamCommandMenu menu = new UpstreamCommandMenu();
    private boolean active, queued;

    void toggle() {
        active = !active;
        queued = false;
        menu.close();
        units.clear();
        buildings.clear();
        Events.fire(Trigger.unitCommandChange);
    }

    void toggleQueue() {
        queued = !queued;
    }

    boolean active() { return active; }
    int mode() { return !active ? 0 : queued ? 2 : 1; }

    void openMenu(boolean stances) {
        prune();
        menu.open(units, stances);
    }

    void tapMenu(int x, int y) {
        prune();
        if (units.isEmpty()) menu.close();
        else menu.tap(units, x, y);
    }

    UpstreamCommandMenu menu() { return menu; }
    int activeMenuRows() { return menu.activeRows(units); }

    void tap(int tileX, int tileY) {
        var tile = Vars.world.tile(tileX, tileY);
        if (tile == null || Vars.player.dead() || Vars.state.isPaused()) return;
        prune();
        float x = tile.worldx(), y = tile.worldy();
        Unit friendly = hitUnit(x, y, true);
        Building building = tile.build;
        if (friendly != null) {
            if (!units.remove(friendly, true)) units.add(friendly);
            buildings.clear();
            Events.fire(Trigger.unitCommandChange);
            System.out.println("UPSTREAM_COMMAND_SELECTION units=" + units.size + " buildings=0");
        } else if (building != null && building.team == Vars.player.team() && building.isCommandable()) {
            units.clear();
            if (!buildings.remove(building, true)) buildings.add(building);
            Events.fire(Trigger.unitCommandChange);
            System.out.println("UPSTREAM_COMMAND_SELECTION units=0 buildings=" + buildings.size);
        } else if (!units.isEmpty() || !buildings.isEmpty()) {
            issueOrder(x, y, building);
        }
    }

    private Unit hitUnit(float x, float y, boolean friendly) {
        // InputHandler uses a four-world-unit query around the cursor. Read the live
        // entity group directly because the snapshot client does not run Logic's team trees.
        touchBox.set(x - 2f, y - 2f, 4f, 4f);
        var team = Vars.player.team();
        Unit closest = null;
        float closestDistance = Float.POSITIVE_INFINITY;
        for (Unit unit : Groups.unit) {
            if (!unit.isValid() || (unit.team() == team) != friendly) continue;
            if (friendly ? !unit.isCommandable() : unit.inFogTo(team)) continue;
            unit.hitbox(hitBox);
            if (!touchBox.overlaps(hitBox)) continue;
            float distance = unit.dst(x, y) - unit.hitSize / 2f;
            if (distance >= closestDistance) continue;
            closestDistance = distance;
            closest = unit;
        }
        return closest;
    }

    private void issueOrder(float x, float y, Building building) {
        var player = Vars.player;
        var target = new Vec2(x, y);
        if (!units.isEmpty()) {
            Building enemyBuilding = building != null && building.team != player.team() ? building : null;
            Unit enemyUnit = enemyBuilding == null ? hitUnit(x, y, false) : null;
            int[] ids = new int[units.size];
            for (int i = 0; i < ids.length; i++) ids[i] = units.get(i).id;
            if (ids.length <= COMMAND_BATCH_SIZE) {
                Call.commandUnits(player, ids, enemyBuilding, enemyUnit, target, queued, true);
            } else {
                // Preserve the stock batching boundary and final formation signal.
                for (int start = 0; start < ids.length; start += COMMAND_BATCH_SIZE) {
                    int end = Math.min(start + COMMAND_BATCH_SIZE, ids.length);
                    Call.commandUnits(player, Arrays.copyOfRange(ids, start, end), enemyBuilding,
                        enemyUnit, target, queued, end == ids.length);
                }
            }
            String kind = enemyBuilding != null ? "building" : enemyUnit != null ? "unit" : "move";
            System.out.println("UPSTREAM_COMMAND_ORDER kind=" + kind + " units=" + ids.length
                + " queued=" + queued + " x=" + x + " y=" + y);
        }
        if (!buildings.isEmpty()) {
            int[] positions = new int[buildings.size];
            for (int i = 0; i < positions.length; i++) positions[i] = buildings.get(i).pos();
            Call.commandBuilding(player, positions, target);
            System.out.println("UPSTREAM_COMMAND_RALLY buildings=" + positions.length + " x=" + x + " y=" + y);
        }
    }

    private void prune() {
        var team = Vars.player.team();
        units.removeAll(unit -> !unit.isValid() || unit.team() != team || !unit.isCommandable());
        buildings.removeAll(build -> !build.isValid() || build.team != team || !build.isCommandable());
    }

    void draw(UpstreamTerrainDrawProbe.Viewport viewport) {
        if (!active || Vars.player.dead()) return;
        prune();
        for (Unit unit : units) {
            if (viewport.contains(unit.tileX(), unit.tileY())) {
                Drawf.poly(unit.x, unit.y, 6, unit.hitSize + Mathf.absin(4f, 1f), 0f, Pal.remove);
            }
        }
        for (Building building : buildings) {
            if (viewport.contains(building)) {
                Drawf.poly(building.x, building.y, 4, building.hitSize() / 1.4f + 0.5f
                    + Mathf.absin(4f, 1f), 0f, Pal.remove);
            }
        }
    }
}
