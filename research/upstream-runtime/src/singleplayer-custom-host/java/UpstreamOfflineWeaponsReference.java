import arc.files.Fi;
import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/** Drives original Gamma projectiles through the offline simulation and save path. */
public final class UpstreamOfflineWeaponsReference {
    public static void main(String[] args) throws Exception {
        Fi fixture = new Fi(args[0]), save = new Fi(args[1]);
        var session = UpstreamSinglePlayerSession.create(save.parent(), "en");
        try {
            session.start(null, fixture, null);
            var unit = session.ensurePlayerSpawn();
            if (unit == null || unit.type != UnitTypes.gamma) throw new AssertionError("Expected stock Gamma");
            var target = enemy();
            if (target == null) throw new AssertionError("Expected an opposing Fortress");
            var weapons = new UpstreamOfflineWeaponInput();
            if (weapons.releasePauses(true, true, true, false, 0L) || weapons.aimMode()
                    || weapons.releasePauses(false, true, true, false, 100_000_000L) || weapons.aimMode()
                    || !weapons.releasePauses(false, false, true, false, 120_000_000L)) {
                throw new AssertionError("A quick X tap while moving must pause without entering aim mode");
            }
            if (weapons.releasePauses(true, true, true, false, 1_000_000_000L)
                    || weapons.releasePauses(false, true, true, false, 1_300_000_000L) || !weapons.aimMode()
                    || weapons.releasePauses(false, false, false, false, 1_400_000_000L)) {
                throw new AssertionError("A held direction must aim without also pausing on release");
            }
            var east = new Vec2(1f, 0f);
            weapons.update(unit, east, true, false);
            float x = unit.x(), y = unit.y(), health = target.health;
            session.stepSimulation(0f, 0f);
            if (unit.aimX() <= x || Math.abs(unit.aimY() - y) > 0.01f || Vars.player.shooting
                    || Math.abs(unit.x() - x) > 0.1f || Math.abs(unit.y() - y) > 0.1f) {
                throw new AssertionError("Aiming should select the eastern target without firing");
            }
            var neutral = new Vec2();
            boolean sawProjectile = false;
            for (int tick = 0; tick < 120 && target.health >= health; tick++) {
                weapons.update(unit, neutral, false, true);
                session.stepSimulation(0f, 0f);
                sawProjectile |= Groups.bullet.contains(bullet -> bullet.owner == unit);
            }
            if (!sawProjectile || target.health <= 0 || target.health >= health || !Vars.player.shooting) {
                throw new AssertionError("Stock weapons did not damage the Fortress: health=" + target.health);
            }
            weapons.update(unit, neutral, false, false);
            int releasedShots = UpstreamOfflineWeaponInput.totalShots(unit);
            for (int tick = 0; tick < 40; tick++) session.stepSimulation(0f, 0f);
            if (Vars.player.shooting || unit.mounts[0].shoot || releasedShots <= 0
                    || UpstreamOfflineWeaponInput.totalShots(unit) != releasedShots) {
                throw new AssertionError("Releasing the trigger must stop the stock weapon");
            }
            float damaged = target.health;
            session.save(save);
            session.start(null, save, null);
            var restored = enemy();
            if (restored == null || Math.abs(restored.health - damaged) > 0.01f) {
                throw new AssertionError("Stock save did not preserve the enemy damage");
            }
            System.out.println("UPSTREAM_OFFLINE_WEAPONS_REFERENCE gamma=" + health + "->" + damaged
                + " aim=ok release=ok reload=ok");
        } finally {
            session.shutdown();
        }
    }

    private static Unit enemy() {
        return Groups.unit.find(unit -> unit.type == UnitTypes.fortress && unit.team == Vars.state.rules.waveTeam);
    }
}
