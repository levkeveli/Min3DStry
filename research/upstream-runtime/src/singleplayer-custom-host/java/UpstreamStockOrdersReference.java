import arc.files.Fi;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;

/** Exercises stock pathfinding and rally-point serialization after real touch orders. */
public final class UpstreamStockOrdersReference {
    public static void main(String[] args) throws Exception {
        Fi fixture = new Fi(args[0]), save = new Fi(args[1]);
        var session = UpstreamSinglePlayerSession.create(save.parent(), "en");
        try {
            session.start(null, fixture, null);
            Unit player = session.ensurePlayerSpawn();
            var controls = new UpstreamLiveBuildControls();
            controls.viewport(73, 20);
            controls.update(player, 0, touch(305, 128));
            controls.update(player, 0, touch(72, 120));
            controls.update(player, 0, touch(104, 120));
            controls.update(player, 0, touch(88, 88));
            controls.update(player, 0, touch(120, 200));
            controls.update(player, 0, touch(248, 168));
            for (int tick = 0; tick < 360 && moved() != 2; tick++) {
                session.stepSimulation(0f, 0f);
                Thread.yield();
            }
            assertOrders();
            session.save(save);
            session.start(null, save, null);
            assertOrders();
            System.out.println("UPSTREAM_STOCK_COMMAND_MOVEMENT units=2 rally=688,192 save=ok");
        } finally {
            session.shutdown();
        }
    }

    private static void assertOrders() {
        var factory = Vars.world.build(78, 22);
        if (moved() != 2 || !(factory instanceof UnitFactoryBuild unitFactory)
                || unitFactory.commandPos == null || unitFactory.commandPos.x != 688f
                || unitFactory.commandPos.y != 192f) {
            throw new AssertionError("Expected two units to move and the original factory rally point to persist");
        }
    }

    private static int moved() {
        return Groups.unit.count(unit -> unit.team == Vars.state.rules.defaultTeam
            && unit.type == UnitTypes.dagger && unit.y > 218f);
    }

    private static int touch(int x, int y) {
        return y << 16 | x;
    }
}
