import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.gen.Mechc;
import mindustry.gen.Unit;

/** Adapts the 3DS aim modifier and trigger to the original unit weapon controls. */
final class UpstreamOfflineWeaponInput {
    private final Vec2 heading = new Vec2();
    private Unit currentUnit;
    private boolean aimed;
    private boolean pauseCandidate;

    boolean releasePauses(boolean pressed, boolean held, boolean aimOrFire) {
        if (pressed) pauseCandidate = true;
        if (held) {
            if (aimOrFire) pauseCandidate = false;
            return false;
        }
        boolean pause = pauseCandidate;
        pauseCandidate = false;
        return pause;
    }

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
        float distance = Math.max(unit.type().range, unit.type().aimDst);
        unit.aim(unit.x() + heading.x * distance, unit.y() + heading.y * distance, true);
        var player = Vars.player;
        player.mouseX = unit.aimX();
        player.mouseY = unit.aimY();
        boolean shooting = trigger && unit.type().hasWeapons() && !unit.activelyBuilding()
            && !unit.mining() && !(unit instanceof Mechc && unit.isFlying());
        if (shooting != player.shooting) {
            System.out.println("UPSTREAM_SP_FIRE shooting=" + shooting
                + " unit=" + unit.type().name + " x=" + unit.aimX() + " y=" + unit.aimY());
        }
        player.shooting = shooting;
        unit.controlWeapons(true, shooting);
    }
}
