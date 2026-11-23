import arc.math.geom.Rect;
import mindustry.Vars;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.logic.Ranged;
import mindustry.world.blocks.ControlBlock;

/** Maps grid targets to the pinned InputHandler possession and release actions. */
final class UpstreamPlayerControl {
    private final Rect hitbox = new Rect();

    boolean take(int tileX, int tileY) {
        var player = Vars.player;
        var tile = Vars.world.tile(tileX, tileY);
        if (tile == null || player.dead() || Vars.state.isPaused() || !Vars.state.rules.possessionAllowed) return false;
        float x = tile.worldx(), y = tile.worldy();
        Unit closest = null;
        float distance = 40f * 40f;
        for (Unit unit : Groups.unit) {
            if (unit.team() != player.team() || !unit.isValid() || !unit.isAI() || !unit.playerControllable()) continue;
            float next = unit.dst2(x, y);
            if (next < distance) {
                closest = unit;
                distance = next;
            }
        }
        if (closest != null) {
            closest.hitbox(hitbox);
            if (hitbox.grow(6f).contains(x, y)) return take(closest);
        }
        var building = tile.build;
        if (building == null || building.team != player.team()) return false;
        if (building instanceof ControlBlock control && control.canControl()) {
            Unit unit = control.unit();
            if (unit != player.unit() && unit.isAI() && unit.playerControllable()) return take(unit);
        }
        if (building.canControlSelect(player.unit())) {
            Call.buildingControlSelect(player, building);
            System.out.println("UPSTREAM_CONTROL_SELECT_BUILDING block=" + building.block.name
                + " x=" + tileX + " y=" + tileY);
            return true;
        }
        return false;
    }

    private static boolean take(Unit unit) {
        Call.unitControl(Vars.player, unit);
        System.out.println("UPSTREAM_CONTROL_TAKE target=" + name(unit) + " x=" + unit.x() + " y=" + unit.y());
        return true;
    }

    void release() {
        if (Vars.player.dead() || Vars.state.isPaused()) return;
        Call.unitClear(Vars.player);
        System.out.println("UPSTREAM_CONTROL_RELEASE");
    }

    static float aimDistance(Unit unit) {
        if (unit instanceof BlockUnitc block && block.tile() instanceof Ranged turret) return turret.range();
        return Math.max(unit.type().range, unit.type().aimDst);
    }

    static String name(Unit unit) {
        return unit instanceof BlockUnitc block ? block.tile().block.name : unit.type().name;
    }
}
