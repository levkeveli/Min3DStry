import arc.files.Fi;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.NetClient;
import mindustry.game.Universe;
import mindustry.gen.Player;
import mindustry.mod.Mods;
import mindustry.net.NetworkIO;

/** Runs the touch adapter against the pinned upstream world, unit and build queue. */
public final class UpstreamBuildControlsReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a captured v160.8 WorldStream");
        var fixture = new Fi(args[0]);
        var app = UpstreamWorldLoadProbe.initialize(fixture.parent());
        Vars.mods = new Mods();
        Vars.universe = new Universe();
        Vars.netClient = NetClient.forSnapshotDispatch();
        Vars.player = Player.create();
        Vars.net.setClientConnected();
        NetworkIO.loadWorld(new InflaterInputStream(fixture.read()));
        app.runPosted();

        var team = Vars.player.team();
        var unit = UnitTypes.gamma.create(team);
        unit.set(648f, 208f);
        Vars.player.unit(unit);
        var controls = new UpstreamLiveBuildControls();
        controls.viewport(73, 20);
        controls.update(unit, 0, touch(288, 136));
        if (!unit.plans().isEmpty()) throw new AssertionError("The grid's right edge must be exclusive");
        controls.update(unit, 0, touch(200, 24));
        controls.update(unit, 0, touch(232, 136));
        var conveyor = unit.plans().removeFirst();
        if (conveyor.x != 85 || conveyor.y != 26 || conveyor.rotation != 1
                || conveyor.block != Blocks.conveyor || conveyor.breaking) {
            throw new AssertionError("Wrong upstream conveyor plan");
        }

        controls.update(unit, 0, touch(120, 24));
        controls.update(unit, 0, touch(248, 136));
        var wall = unit.plans().removeFirst();
        if (wall.x != 86 || wall.y != 26 || wall.block != Blocks.copperWall || wall.breaking) {
            throw new AssertionError("Wrong upstream wall plan");
        }
        wall.tile().setBlock(wall.block, team, wall.rotation);
        controls.update(unit, 0, touch(280, 24));
        controls.update(unit, 0, touch(248, 136));
        var removal = unit.plans().removeFirst();
        if (!removal.breaking || removal.x != wall.x || removal.y != wall.y) {
            throw new AssertionError("Wrong upstream deconstruction plan");
        }
        controls.viewport(87, 20);
        if (controls.cursorX() != -1 || controls.cursorY() != -1) {
            throw new AssertionError("An offscreen cursor must clear");
        }
        System.out.println("UPSTREAM_TOUCH_REFERENCE conveyor=85,26,1 wall=86,26 break=86,26");
    }

    private static int touch(int x, int y) {
        return x | (y << 16);
    }
}
