import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.gen.Mechc;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Unit;

/** Adapts the 3DS aim modifier and trigger to the original unit weapon controls. */
final class UpstreamOfflineWeaponInput {
    private static final long AIM_HOLD_NS = 250_000_000L;
    private final Vec2 heading = new Vec2();
    private Unit currentUnit;
    private boolean aimed;
    private boolean pendingTap, aimMode;
    private long pressedAt;

    boolean releasePauses(boolean pressed, boolean held, boolean direction, boolean trigger, long now) {
        if (pressed) {
            pendingTap = true;
            pressedAt = now;
        }
        if (held) {
            if (pendingTap && (direction || trigger) && now - pressedAt >= AIM_HOLD_NS) {
                pendingTap = false;
                aimMode = true;
            }
            return false;
        }
        boolean pause = pendingTap;
        pendingTap = false;
        aimMode = false;
        return pause;
    }

    boolean aimMode() { return aimMode; }

    void update(Unit unit, Vec2 direction, boolean aiming, boolean trigger) {
        if (currentUnit != unit) {
            currentUnit = unit;
            aimed = false;
        }
        if (aiming) {
            heading.set(direction).nor();
            aimed = true;
        }
        if (!aimed) heading.trns(unit.rotation(), 1f);
        float distance = UpstreamPlayerControl.aimDistance(unit);
        unit.aim(unit.x() + heading.x * distance, unit.y() + heading.y * distance, true);
        var player = Vars.player;
        player.mouseX = unit.aimX();
        player.mouseY = unit.aimY();
        boolean shooting = trigger && (unit.type().hasWeapons() || unit instanceof BlockUnitc) && !unit.activelyBuilding()
            && !unit.mining() && !(unit instanceof Mechc && unit.isFlying());
        if (shooting != player.shooting) {
            System.out.println("UPSTREAM_SP_FIRE shooting=" + shooting
                + " unit=" + unit.type().name + " x=" + unit.aimX() + " y=" + unit.aimY()
                + " shots=" + totalShots(unit));
        }
        player.shooting = shooting;
        unit.controlWeapons(true, shooting);
    }

    static int totalShots(Unit unit) {
        int total = 0;
        for (var mount : unit.mounts) total += mount.totalShots;
        return total;
    }
}
