import arc.files.Fi;
import arc.math.geom.Point2;
import arc.util.io.Reads;
import arc.util.io.Writes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HexFormat;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.ai.ItemUnitStance;
import mindustry.ai.UnitCommand;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.GameState.State;
import mindustry.core.NetClient;
import mindustry.game.MapObjectives;
import mindustry.game.MapObjectives.ItemObjective;
import mindustry.game.MapObjectives.ResearchObjective;
import mindustry.game.MapObjectives.TextMarker;
import mindustry.game.MapObjectives.TextureMarker;
import mindustry.game.Universe;
import mindustry.gen.BlockSnapshotCallPacket;
import mindustry.gen.BlockUnitc;
import mindustry.gen.ClearObjectivesCallPacket;
import mindustry.gen.CompleteObjectiveCallPacket;
import mindustry.gen.CreateMarkerCallPacket;
import mindustry.gen.EntitySnapshotCallPacket;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.RemoveMarkerCallPacket;
import mindustry.gen.SetObjectivesCallPacket;
import mindustry.gen.SetUnitCommandCallPacket;
import mindustry.gen.SetUnitStanceCallPacket;
import mindustry.gen.StateSnapshotCallPacket;
import mindustry.gen.TileConfigCallPacket;
import mindustry.gen.Unit;
import mindustry.gen.UnitControlCallPacket;
import mindustry.gen.UpdateMarkerCallPacket;
import mindustry.gen.UpdateMarkerTextCallPacket;
import mindustry.gen.UpdateMarkerTextureCallPacket;
import mindustry.logic.LMarkerControl;
import mindustry.mod.Mods;
import mindustry.net.Net;
import mindustry.net.NetworkIO;
import mindustry.net.Packet;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.ControlBlock;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.modules.ItemModule;

public final class UpstreamSnapshotDispatchReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a captured v160.8 WorldStream");
        var fixture = new Fi(args[0]);
        var app = UpstreamWorldLoadProbe.initialize(fixture.parent());
        Vars.mods = new Mods();
        Vars.universe = new Universe();
        Vars.indexer = new BlockIndexer();
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
        var mono = UnitTypes.mono.create(core.team);
        mono.id(12346);
        mono.set(616f, 216f);
        mono.command().command(UnitCommand.moveCommand);
        out.writeInt(mono.id);
        out.writeByte(mono.classId());
        mono.writeSync(new Writes(out));
        var packet = new EntitySnapshotCallPacket();
        packet.amount = 2;
        packet.data = bytes.toByteArray();
        packet.handleClient();
        if (!(Groups.sync.getByID(dagger.id) instanceof Unit replicated)
                || replicated.type != UnitTypes.dagger || replicated.x != 80f || replicated.y != 152f) {
            throw new IllegalStateException("Enemy unit did not synchronize");
        }
        if (!(Groups.sync.getByID(mono.id) instanceof Unit decodedMono) || decodedMono.type != UnitTypes.mono
                || decodedMono.x != 616f || decodedMono.y != 216f || !decodedMono.isCommandable()) {
            throw new IllegalStateException("Stock Mono position or controller did not synchronize");
        }
        // A player taking over a grounded unit enters VelComp's stock tile collision path.
        // Exercise it on a freshly decoded Dagger, before any offline services are installed.
        replicated.move(0.25f, 0f);
        verifyUnitCommandPackets(decodedMono);
        System.out.println("UPSTREAM_SNAPSHOT_REFERENCE wave=" + state.wave
            + " seconds=42 copper=947 dagger=" + dagger.id);
        verifyPayloadHandoff();
        verifyObjectivePackets();
        verifyMarkerPackets();
        verifyPossessionPackets(decodedMono);
        verifyStockLinkPackets();
    }

    private static void verifyStockLinkPackets() {
        var actor = Vars.player;
        var source = Vars.world.tile(74, 29);
        var target = Vars.world.tile(78, 29);
        source.setBlock(Blocks.powerNode, actor.team(), 0);
        target.setBlock(Blocks.battery, actor.team(), 0);

        var packet = new TileConfigCallPacket();
        packet.player = actor;
        packet.build = source.build;
        packet.value = new Point2[]{new Point2(target.x - source.x, target.y - source.y)};
        applyPacket(packet, new TileConfigCallPacket(), actor);
        if (source.build.power.graph != target.build.power.graph || !source.build.power.links.contains(target.pos())) {
            throw new AssertionError("The stock Point2 array did not join the power graph");
        }
        packet.value = target.pos();
        applyPacket(packet, new TileConfigCallPacket(), actor);
        if (source.build.power.links.contains(target.pos()) || target.build.power.links.contains(source.pos())) {
            throw new AssertionError("The stock packed position did not disconnect both power endpoints");
        }

        source.setBlock(Blocks.bridgeConduit, actor.team(), 0);
        target.setBlock(Blocks.bridgeConduit, actor.team(), 0);
        packet.build = source.build;
        applyPacket(packet, new TileConfigCallPacket(), actor);
        if (!(source.build instanceof ItemBridgeBuild bridge) || bridge.link != target.pos()) {
            throw new AssertionError("The stock packed position did not configure the liquid bridge");
        }
        System.out.println("UPSTREAM_STOCK_LINK_PACKETS point-array=ok packed-position=ok");
    }

    private static void verifyPossessionPackets(Unit allied) {
        var actor = Vars.player;
        var packet = new UnitControlCallPacket();
        packet.player = actor;
        packet.unit = allied;
        applyPacket(packet, new UnitControlCallPacket(), actor);
        if (actor.unit() != allied) throw new AssertionError("Stock possession packet did not assign the allied unit");

        var tile = Vars.world.tile(78, 22);
        tile.setBlock(Blocks.duo, actor.team(), 0);
        var proxy = ((ControlBlock)tile.build).unit();
        packet.unit = proxy;
        applyPacket(packet, new UnitControlCallPacket(), actor);
        if (actor.unit() != proxy || !(proxy instanceof BlockUnitc block) || block.tile() != tile.build
                || Groups.sync.getByID(proxy.id()) == proxy) {
            throw new AssertionError("Stock possession must decode the turret proxy by its tile");
        }
        System.out.println("UPSTREAM_POSSESSION_PACKET_REFERENCE movable=mono turret=duo");
    }

    private static void verifyUnitCommandPackets(Unit unit) {
        if (!Vars.player.isAdded()) Vars.player.add();
        var command = new SetUnitCommandCallPacket();
        command.player = Vars.player;
        command.unitIds = new int[]{unit.id};
        command.command = UnitCommand.mineCommand;
        applyPacket(command, new SetUnitCommandCallPacket(), Vars.player);
        if (unit.command().currentCommand() != UnitCommand.mineCommand) {
            throw new AssertionError("The original command packet did not switch the Mono to Mine");
        }

        var stance = new SetUnitStanceCallPacket();
        stance.player = Vars.player;
        stance.unitIds = new int[]{unit.id};
        stance.stance = ItemUnitStance.getByItem(Items.copper);
        stance.enable = true;
        applyPacket(stance, new SetUnitStanceCallPacket(), Vars.player);
        if (!unit.command().hasStance(stance.stance)) {
            throw new AssertionError("The original stance packet did not select copper");
        }
        System.out.println("UPSTREAM_STANCE_PACKET_REFERENCE mono=mine-copper");
    }

    private static void verifyObjectivePackets() {
        var collect = new ItemObjective(Items.beryllium, 60);
        collect.flagsAdded = new String[]{"collected"};
        collect.flagsRemoved = new String[]{"collecting"};
        var research = new ResearchObjective(Blocks.plasmaBore);
        var set = new SetObjectivesCallPacket();
        set.executor = new MapObjectives();
        set.executor.add(collect.child(research));
        applyPacket(set, new SetObjectivesCallPacket());

        var rules = Vars.state.rules;
        int collectIndex = set.executor.all.indexOf(collect, true);
        var loadedCollect = (ItemObjective)rules.objectives.get(collectIndex);
        var loadedResearch = (ResearchObjective)rules.objectives.get(set.executor.all.indexOf(research, true));
        if (loadedCollect.item != Items.beryllium || loadedCollect.amount != 60 || !loadedCollect.qualified()
                || loadedResearch.content != Blocks.plasmaBore || loadedResearch.qualified()) {
            throw new AssertionError("Stock set-objectives did not restore the objective dependency");
        }

        rules.objectiveFlags.add("collecting");
        var complete = new CompleteObjectiveCallPacket();
        complete.index = collectIndex;
        applyPacket(complete, new CompleteObjectiveCallPacket());
        if (!loadedCollect.isCompleted() || !loadedResearch.qualified()
                || !rules.objectiveFlags.contains("collected") || rules.objectiveFlags.contains("collecting")) {
            throw new AssertionError("Stock complete-objective did not advance the dependency and flags");
        }
        applyPacket(new ClearObjectivesCallPacket(), new ClearObjectivesCallPacket());
        if (!rules.objectives.all.isEmpty()) throw new AssertionError("Stock clear-objectives did not clear the executor");
        System.out.println("UPSTREAM_OBJECTIVE_REFERENCE set=ok complete=ok dependency=ok flags=ok clear=ok");
    }

    private static void applyPacket(Packet sent, Packet received) {
        applyPacket(sent, received, null);
    }

    private static void applyPacket(Packet sent, Packet received, Player forwardedBy) {
        var bytes = new ByteArrayOutputStream();
        var writes = new Writes(new DataOutputStream(bytes));
        // Stock forwardable packets include the actor in the server-to-client envelope.
        if (forwardedBy != null) mindustry.io.TypeIO.writeEntity(writes, forwardedBy);
        sent.write(writes);
        byte[] payload = bytes.toByteArray();
        received.read(new Reads(new DataInputStream(new ByteArrayInputStream(payload))), payload.length);
        received.handled();
        received.handleClient();
    }

    private static void verifyMarkerPackets() {
        var markers = Vars.state.markers;
        var create = new CreateMarkerCallPacket();
        create.id = 745;
        create.marker = new TextMarker("Original", 48f, 96f);
        applyMarkerPacket(create);
        if (!(markers.get(create.id) instanceof TextMarker label) || !label.text.equals("Original")
                || label.pos.x != 48f || label.pos.y != 96f || !markers.worldMarkers.contains(label, true)) {
            throw new AssertionError("Stock create-marker did not restore the world label");
        }

        var text = new UpdateMarkerTextCallPacket();
        text.id = create.id;
        text.type = LMarkerControl.flushText;
        text.text = "Updated";
        applyMarkerPacket(text);

        var update = new UpdateMarkerCallPacket();
        update.id = create.id;
        update.control = LMarkerControl.pos;
        update.p1 = 19;
        update.p2 = 23;
        update.p3 = Double.NaN;
        applyMarkerPacket(update);
        if (!label.text.equals("Updated") || label.pos.x != 19 * Vars.tilesize || label.pos.y != 23 * Vars.tilesize) {
            throw new AssertionError("Stock marker text or tile position did not update");
        }

        update.control = LMarkerControl.world;
        update.p1 = 0;
        update.p2 = Double.NaN;
        applyMarkerPacket(update);
        if (label.world != -1 || markers.worldMarkers.contains(label, true)) {
            throw new AssertionError("Stock world visibility did not hide the marker");
        }
        update.p1 = 1;
        applyMarkerPacket(update);
        if (label.world == -1 || !markers.worldMarkers.contains(label, true)) {
            throw new AssertionError("Stock world visibility did not restore the marker");
        }

        create.marker = new TextureMarker("white", 24f, 40f, 16f, 16f);
        applyMarkerPacket(create);
        var texture = new UpdateMarkerTextureCallPacket();
        texture.id = create.id;
        texture.texture = "error";
        applyMarkerPacket(texture);
        if (!(markers.get(create.id) instanceof TextureMarker image) || !image.texture.value.equals("error")
                || markers.worldMarkers.contains(label, true)) {
            throw new AssertionError("Stock marker replacement or texture update did not apply");
        }

        var remove = new RemoveMarkerCallPacket();
        remove.id = create.id;
        applyMarkerPacket(remove);
        if (markers.has(create.id) || markers.worldMarkers.contains(image, true)) {
            throw new AssertionError("Stock remove-marker left the marker in the registry");
        }
        System.out.println("UPSTREAM_MARKER_PACKETS create=ok text=ok position=ok visibility=ok replace=ok texture=ok remove=ok");
    }

    static void applyMarkerPacket(Packet sent) {
        var received = UpstreamLiveNetworkProbe.markerPacket(Net.getPacketClassId(sent.getClass()) & 0xff);
        if (received == null || received.getClass() != sent.getClass()) {
            throw new AssertionError("Missing stock live marker packet " + sent.getClass().getSimpleName());
        }
        applyPacket(sent, received);
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
