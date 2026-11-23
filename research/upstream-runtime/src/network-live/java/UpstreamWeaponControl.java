import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Mechc;
import mindustry.gen.Unit;

/** Translates a handheld heading and trigger to upstream player and unit input state. */
final class UpstreamWeaponControl {
    private final Vec2 heading = new Vec2();
    private Unit currentUnit;
    private boolean aimed;

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
        // PlayerComp.afterSync restores the local pointer from these fields.
        player.mouseX = unit.aimX();
        player.mouseY = unit.aimY();
        player.shooting = trigger && (unit.type().hasWeapons() || unit instanceof BlockUnitc)
            && !unit.activelyBuilding() && !unit.mining() && !(unit instanceof Mechc && unit.isFlying());
        unit.controlWeapons(true, player.shooting);
    }
}
