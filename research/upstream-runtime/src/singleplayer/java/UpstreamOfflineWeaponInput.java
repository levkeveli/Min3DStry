import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.gen.Unit;

/** Adapts the 3DS aim modifier and trigger to the original unit weapon controls. */
final class UpstreamOfflineWeaponInput {
    private static final long AIM_HOLD_NS = 250_000_000L;
    private final UpstreamWeaponControl weapons = new UpstreamWeaponControl();
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
        boolean previous = Vars.player.shooting;
        weapons.update(unit, direction, aiming, trigger);
        boolean shooting = Vars.player.shooting;
        if (shooting != previous) {
            System.out.println("UPSTREAM_SP_FIRE shooting=" + shooting
                + " unit=" + unit.type().name + " x=" + unit.aimX() + " y=" + unit.aimY()
                + " shots=" + totalShots(unit));
        }
    }

    static int totalShots(Unit unit) {
        int total = 0;
        for (var mount : unit.mounts) total += mount.totalShots;
        return total;
    }
}
