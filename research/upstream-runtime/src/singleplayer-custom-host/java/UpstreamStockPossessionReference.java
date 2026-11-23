import arc.files.Fi;
import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Unit;
import mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild;

/** Verifies stock possession, turret fire, and release in the offline session. */
public final class UpstreamStockPossessionReference {
    public static void main(String[] args) throws Exception {
        var fixture = new Fi(args[0]);
        var session = UpstreamSinglePlayerSession.create(fixture.parent(), "en");
        try {
            session.start(null, fixture, null);
            var initial = session.ensurePlayerSpawn();
            if (initial == null || initial.type() != UnitTypes.gamma) throw new AssertionError("Expected stock Gamma");
            var controls = new UpstreamLiveBuildControls();
            controls.viewport(73, 20);
            var dagger = UnitTypes.dagger.create(initial.team());
            dagger.set(600f, 216f);
            dagger.add();
            var enemy = UnitTypes.dagger.create(Vars.state.rules.waveTeam);
            enemy.set(688f, 192f);
            enemy.add();
            controls.update(initial, 0, touch(305, 156));
            Vars.state.rules.possessionAllowed = false;
            tap(controls, initial, 75, 27);
            if (Vars.player.unit() != initial) throw new AssertionError("Possession is disabled by the original rules");
            Vars.state.rules.possessionAllowed = true;
            tap(controls, initial, 86, 24);
            if (Vars.player.unit() != initial) throw new AssertionError("An opposing unit must not be controllable");
            enemy.remove();
            tap(controls, initial, 75, 27);
            if (Vars.player.unit() != dagger || initial.isAdded()) {
                throw new AssertionError("Possession did not transfer to the stock Dagger");
            }
            var turretTile = Vars.world.tile(86, 26);
            turretTile.setBlock(Blocks.duo, dagger.team(), 0);
            var turret = (ItemTurretBuild)turretTile.build;
            turret.handleStack(Items.copper, 15, dagger);
            var wall = Vars.world.tile(90, 26);
            wall.setBlock(Blocks.copperWall, Vars.state.rules.waveTeam, 0);
            var target = wall.build;
            float health = target.health;
            controls.update(dagger, 0, touch(305, 156));
            tap(controls, dagger, 86, 26);
            Unit controlled = Vars.player.unit();
            if (!(controlled instanceof BlockUnitc block) || block.tile() != turret || !turret.controlled()) {
                throw new AssertionError("The original Duo control proxy was not assigned to the player");
            }
            var weapons = new UpstreamOfflineWeaponInput();
            var east = new Vec2(1f, 0f);
            int ammo = turret.totalAmmo;
            for (int tick = 0; tick < 150 && target.health >= health; tick++) {
                weapons.update(controlled, east, true, true);
                session.stepSimulation(1f, 0f);
            }
            if (turret.totalAmmo >= ammo || target.health >= health || controlled.x() != turret.x
                    || controlled.y() != turret.y) {
                throw new AssertionError("The anchored turret must fire the original ammo at the chosen target");
            }
            controls.update(controlled, 0, touch(305, 156));
            controls.update(controlled, 1 << 17, -1);
            Unit released = session.ensurePlayerSpawn();
            if (turret.controlled() || released == null || !released.spawnedByCore() || released == controlled) {
                throw new AssertionError("Release must return to a stock core unit");
            }
            System.out.println("UPSTREAM_STOCK_POSSESSION_REFERENCE unit=dagger turret=duo release=ok ammo=" + turret.totalAmmo);
        } finally {
            session.shutdown();
        }
    }

    private static void tap(UpstreamLiveBuildControls controls, Unit player, int x, int y) {
        controls.update(player, 0, touch(32 + (x - 73) * 16 + 8, 48 + (31 - y) * 16 + 8));
    }

    private static int touch(int x, int y) {
        return y << 16 | x;
    }
}
