import arc.files.Fi;
import arc.util.io.Writes;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.HexFormat;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.GameState.State;
import mindustry.core.NetClient;
import mindustry.game.Universe;
import mindustry.gen.BlockSnapshotCallPacket;
import mindustry.gen.EntitySnapshotCallPacket;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.StateSnapshotCallPacket;
import mindustry.gen.Unit;
import mindustry.mod.Mods;
import mindustry.net.NetworkIO;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.modules.ItemModule;

public final class UpstreamSnapshotDispatchReference {
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
        Vars.state.set(State.playing);

        var core = Vars.player.team().core();
        if (core == null) throw new IllegalStateException("Missing team core");
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeByte(1);
        out.writeByte(core.team.id);
        var items = new ItemModule();
        items.set(Items.copper, 947);
        items.write(new Writes(out));

        var state = new StateSnapshotCallPacket();
        state.wave = Vars.state.wave + 1;
        state.waveTime = 6543f;
        state.enemies = 1;
        state.timeData = 42;
        state.tps = 60;
        state.coreData = bytes.toByteArray();
        state.handleClient();
        if (Vars.state.wave != state.wave || Vars.state.wavetime != state.waveTime
                || Vars.state.enemies != state.enemies || Vars.state.serverTps != 60
                || Vars.universe.seconds() != 42 || core.items.get(Items.copper) != 947) {
            throw new IllegalStateException("State or core inventory did not synchronize");
        }

        var dagger = UnitTypes.dagger.create(Vars.state.rules.waveTeam);
        dagger.id(12345);
        dagger.set(80f, 152f);
        bytes.reset();
        out.writeInt(dagger.id);
        out.writeByte(dagger.classId());
        dagger.writeSync(new Writes(out));
        var packet = new EntitySnapshotCallPacket();
        packet.amount = 1;
        packet.data = bytes.toByteArray();
        packet.handleClient();
        if (!(Groups.sync.getByID(dagger.id) instanceof Unit replicated)
                || replicated.type != UnitTypes.dagger || replicated.x != 80f || replicated.y != 152f) {
            throw new IllegalStateException("Enemy unit did not synchronize");
        }
        System.out.println("UPSTREAM_SNAPSHOT_REFERENCE wave=" + state.wave
            + " seconds=42 copper=947 dagger=" + dagger.id);
        verifyPayloadHandoff();
    }

    private static void verifyPayloadHandoff() {
        var team = Vars.player.team();
        var first = Vars.world.tile(74, 22);
        var second = Vars.world.tile(77, 22);
        first.setBlock(Blocks.payloadConveyor, team, 0);
        second.setBlock(Blocks.payloadConveyor, team, 0);
        var sender = (PayloadConveyorBuild) first.build;
        var receiver = (PayloadConveyorBuild) second.build;
        sender.handlePayload(sender, new BuildPayload(Blocks.copperWall, team));

        // Two records captured from the official v160.8 server's periodic snapshot after the handoff.
        var packet = new BlockSnapshotCallPacket();
        packet.amount = 2;
        packet.data = HexFormat.of().parseHex(
            "004a0016018e43b400008001030108ffff422016800000000000"
            + "004d0016018e43b400008001030108ffff4220168038c7ded5010100d80043a000008001030108ffff");
        packet.handleClient();
        if (sender.item != null || !(receiver.item instanceof BuildPayload wall)
                || wall.block() != Blocks.copperWall || wall.build.team != team) {
            throw new AssertionError("Stock block snapshot did not transfer the carried Copper Wall");
        }
        System.out.println("UPSTREAM_PAYLOAD_SNAPSHOT sender=empty receiver=copper-wall");
    }
}
