import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.mock.MockAudio;
import arc.struct.IntSet;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.ai.Pathfinder;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.GameState.State;
import mindustry.core.NetClient;
import mindustry.game.FogControl;
import mindustry.game.MapObjectives.ItemObjective;
import mindustry.game.Universe;
import mindustry.gen.BeginBreakCallPacket;
import mindustry.gen.BeginPlaceCallPacket;
import mindustry.gen.BlockSnapshotCallPacket;
import mindustry.gen.BlockUnitc;
import mindustry.gen.ClientSnapshotCallPacket;
import mindustry.gen.ClearObjectivesCallPacket;
import mindustry.gen.CompleteObjectiveCallPacket;
import mindustry.gen.CommandBuildingCallPacket;
import mindustry.gen.CommandUnitsCallPacket;
import mindustry.gen.ConnectConfirmCallPacket;
import mindustry.gen.CreateMarkerCallPacket;
import mindustry.gen.CreateWeatherCallPacket;
import mindustry.gen.CreateBulletCallPacket;
import mindustry.gen.ConstructFinishCallPacket;
import mindustry.gen.DeconstructFinishCallPacket;
import mindustry.gen.EntitySnapshotCallPacket;
import mindustry.gen.Groups;
import mindustry.gen.KickCallPacket;
import mindustry.gen.KickCallPacket2;
import mindustry.gen.Mechc;
import mindustry.gen.Player;
import mindustry.gen.RemoveMarkerCallPacket;
import mindustry.gen.RemoveQueueBlockCallPacket;
import mindustry.gen.SendChatMessageCallPacket;
import mindustry.gen.SendMessageCallPacket;
import mindustry.gen.SendMessageCallPacket2;
import mindustry.gen.SetObjectivesCallPacket;
import mindustry.gen.SetPositionCallPacket;
import mindustry.gen.SetRuleCallPacket;
import mindustry.gen.SetRulesCallPacket;
import mindustry.gen.SoundAtCallPacket;
import mindustry.gen.SoundCallPacket;
import mindustry.gen.SetUnitCommandCallPacket;
import mindustry.gen.SetUnitStanceCallPacket;
import mindustry.gen.StateSnapshotCallPacket;
import mindustry.gen.TakeItemsCallPacket;
import mindustry.gen.TileConfigCallPacket;
import mindustry.gen.TransferItemToCallPacket;
import mindustry.gen.Unit;
import mindustry.gen.UnitBuildingControlSelectCallPacket;
import mindustry.gen.UnitClearCallPacket;
import mindustry.gen.UnitControlCallPacket;
import mindustry.gen.UnitDespawnCallPacket;
import mindustry.gen.UnitDestroyCallPacket;
import mindustry.gen.UnitSpawnCallPacket;
import mindustry.gen.UpdateMarkerCallPacket;
import mindustry.gen.UpdateMarkerTextCallPacket;
import mindustry.gen.UpdateMarkerTextureCallPacket;
import mindustry.mod.Mods;
import mindustry.net.Host;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.net.NetworkIO;
import mindustry.net.Packet;
import mindustry.net.Packets.StreamChunk;
import mindustry.world.blocks.distribution.Sorter;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

public final class UpstreamLiveNetworkProbe {
    private static final int ACCEPTED = 0, REJECTED = 1, TIMEOUT = -2;
    private static final int BUILD = 160;
    private static final int CHAT_PRESSED = 1 << 22;
    // Match Arc's pinned TcpConnection and UdpConnection default intervals.
    private static final long TCP_KEEP_ALIVE_NS = 8_000_000_000L;
    private static final long UDP_KEEP_ALIVE_NS = 19_000_000_000L;
    private static final String PLAYER_NAME = "Min3DStry ARM11";
    private enum Mode { SNAPSHOTS, MOVEMENT, CONTROLS }
    private final byte[] packet = new byte[32_768];
    private final Mode mode;
    private final Consumer<Unit> frame;
    private final UpstreamLiveBuildControls building;
    private final Consumer<String> chat;
    private Runnable deferredGraphics;
    private int nextLiveTransport = 1;

    private UpstreamLiveNetworkProbe(Mode mode, Runnable deferredGraphics, Consumer<Unit> frame,
                                     UpstreamLiveBuildControls building, Consumer<String> chat) {
        this.mode = mode;
        this.deferredGraphics = deferredGraphics;
        this.frame = frame;
        this.building = building;
        this.chat = chat;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var endpoint = endpointFile(args);
        run(endpoint.parent(), endpoint.readString(), null, null, null, null, null);
    }

    static Fi endpointFile(String[] args) {
        return new Fi(args.length == 0 ? "/3ds/min3dstry/server-endpoint.txt" : args[0]);
    }

    static void run(Fi directory, String address, Runnable beforeConnect, Runnable deferredGraphics,
                    Consumer<Unit> frame, UpstreamLiveBuildControls building, Consumer<String> chat)
                    throws IOException, InterruptedException {
        String[] endpoint = address.trim().split("\\s+");
        if (endpoint.length < 2 || endpoint.length > 3) {
            throw new IllegalArgumentException("Expected a server host and port with optional movement or controls mode");
        }
        Mode mode = endpoint.length == 2 ? (frame == null ? Mode.SNAPSHOTS : Mode.CONTROLS) : switch (endpoint[2]) {
            case "movement" -> Mode.MOVEMENT;
            case "controls" -> Mode.CONTROLS;
            default -> throw new IllegalArgumentException("Unknown live network mode: " + endpoint[2]);
        };
        byte[] host = endpoint[0].getBytes(StandardCharsets.UTF_8);
        int port = Integer.parseInt(endpoint[1]);
        String stage = "content";
        try {
            var app = UpstreamWorldLoadProbe.initialize(directory);
            stage = "services";
            Vars.mods = new Mods();
            Vars.universe = new Universe();
            if (frame != null) {
                if (Core.audio == null) Core.audio = new MockAudio();
                Vars.indexer = new BlockIndexer();
                Vars.pathfinder = new Pathfinder();
                Vars.fogControl = new FogControl();
            }
            stage = "client setup";
            if (beforeConnect != null) beforeConnect.run();
            stage = "session";
            new UpstreamLiveNetworkProbe(mode, deferredGraphics, frame, building, chat).run(host, port, app);
        } catch (IOException | RuntimeException | LinkageError error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            System.out.println("UPSTREAM_LIVE_NET_ERROR stage=" + stage + " " + cause.getClass().getSimpleName()
                + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            throw error;
        }
    }

    private void run(byte[] host, int port, UpstreamWorldLoadProbe.ProbeApplication app)
            throws IOException, InterruptedException {
        if (mode == Mode.CONTROLS) {
            try {
                connect(host, port, app, 1);
            } finally {
                Native.close();
            }
            System.out.println("UPSTREAM_LIVE_CONTROLS_LEFT");
            return;
        }
        try {
            int result = open(host, port, BUILD - 1);
            if (result != REJECTED || Native.rejectedPacket() != unsignedId(KickCallPacket2.class)) {
                throw new IOException("Expected a server kick for the outdated build, status=" + result);
            }
            System.out.println("UPSTREAM_LIVE_NET_REJECT build=" + (BUILD - 1) + " packet=KickCallPacket2");
        } finally {
            Native.close();
        }
        for (int join = 1; join <= 2; join++) {
            try {
                connect(host, port, app, join);
            } finally {
                Native.close();
            }
            System.out.println("UPSTREAM_LIVE_NET_DISCONNECTED join=" + join);
            if (join == 1) {
                // Net.disconnect normally clears client mode before Logic.reset removes synced entities.
                Vars.net = new Net(null);
                Vars.logic.reset();
                app.runPosted();
                Thread.sleep(500);
            }
        }
        System.out.println("UPSTREAM_LIVE_NET_PASS");
    }

    private void connect(byte[] host, int port, UpstreamWorldLoadProbe.ProbeApplication app, int join)
            throws IOException {
        int result = open(host, port, BUILD);
        if (result != ACCEPTED || Native.streamType() != Net.packetIdWorldStream || Native.streamTotal() <= 0) {
            throw new IOException("Expected a live multiplayer WorldStream, status=" + result
                + " packet=" + Native.rejectedPacket() + " streamType=" + Native.streamType());
        }
        if (frame != null) Vars.net = new Net(new LiveClientProvider());
        Vars.net.setClientConnected();
        Vars.netClient = NetClient.forSnapshotDispatch();
        int total = Native.streamTotal();
        var stream = new WorldStreamInput(Native.streamId(), total);
        Vars.player = Player.create();
        if (Native.beginKeepAlive(0) == 0) throw new IOException("Unable to keep the connection open during world loading");
        try {
            NetworkIO.loadWorld(new InflaterInputStream(stream));
            app.runPosted();
            if (stream.remaining != 0 || Groups.player.size() != 1 || !PLAYER_NAME.equals(Vars.player.name)
                    || Vars.world.width() <= 0 || Vars.world.height() <= 0) {
                throw new IOException("Server world or assigned player did not load");
            }
            if (deferredGraphics != null) {
                System.out.println("UPSTREAM_WORLD_DECODED beforeGraphics=true");
                deferredGraphics.run();
                deferredGraphics = null;
            }
            reportReceivedWorld(join, total);
            Vars.state.set(State.playing);
            send(new ConnectConfirmCallPacket(), true);
        } finally {
            Native.endKeepAlive();
        }
        if (mode == Mode.CONTROLS) {
            driveLiveClient(app);
        } else {
            waitForWave(join, app);
        }
    }

    private void reportReceivedWorld(int join, int total) {
        String fingerprint = UpstreamWorldLoadProbe.reportTerrain(false);
        System.out.println("UPSTREAM_LIVE_NET_WORLD join=" + join + " map=" + Vars.state.map.name()
            + " bytes=" + total + " team=" + Vars.player.team().id + " fingerprint=" + fingerprint);
        var objectives = Vars.state.rules.objectives;
        if (!objectives.all.isEmpty()) {
            int dependencies = 0, markers = 0;
            for (var objective : objectives) {
                dependencies += objective.parents.size;
                markers += objective.markers.length;
            }
            System.out.println("UPSTREAM_WORLD_OBJECTIVES count=" + objectives.all.size
                + " dependencies=" + dependencies + " markers=" + markers);
            if (objectives.get(0) instanceof ItemObjective first) {
                System.out.println("UPSTREAM_WORLD_ITEM_OBJECTIVE item=" + first.item.name + " amount=" + first.amount);
            }
        }
    }

    private static void send(Packet outgoing, boolean reliable) throws IOException {
        var buffer = new ByteArrayOutputStream();
        outgoing.write(new Writes(new DataOutputStream(buffer)));
        byte[] payload = buffer.toByteArray();
        if (Native.send(Address.ofData(payload), payload.length, Net.getPacketId(outgoing) & 0xff,
                reliable ? 1 : 0) == 0) {
            throw new IOException("Unable to send " + outgoing.getClass().getSimpleName());
        }
    }

    /** Lets generated upstream calls use this probe's connected ARM11 Arc transport. */
    private static final class LiveClientProvider implements Net.NetProvider {
        @Override
        public void sendClient(Object message, boolean reliable) {
            try {
                send((Packet) message, reliable);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        @Override
        public void disconnectClient() {
            Native.close();
        }

        @Override
        public void connectClient(String ip, int port, Runnable success) throws IOException {
            throw new IOException("The live probe connects from its endpoint file");
        }

        @Override
        public void discoverServers(Cons<Host> callback, Runnable done) {
            done.run();
        }

        @Override
        public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed) {
            failed.get(new IOException("The live probe does not ping hosts"));
        }

        @Override
        public void hostServer(int port) throws IOException {
            throw new IOException("The live probe is a client");
        }

        @Override
        public Iterable<NetConnection> getConnections() {
            return List.of();
        }

        @Override
        public void closeServer() {}
    }

    private void waitForWave(int join, UpstreamWorldLoadProbe.ProbeApplication app) throws IOException {
        long start = System.nanoTime();
        long end = start + 30_000_000_000L;
        long tcpKeepAlive = start + TCP_KEEP_ALIVE_NS;
        long udpKeepAlive = start + UDP_KEEP_ALIVE_NS;
        int snapshotId = unsignedId(StateSnapshotCallPacket.class);
        int entityId = unsignedId(EntitySnapshotCallPacket.class);
        int unitSpawnId = unsignedId(UnitSpawnCallPacket.class);
        int unitDespawnId = unsignedId(UnitDespawnCallPacket.class);
        int unitDestroyId = unsignedId(UnitDestroyCallPacket.class);
        int initialWave = Vars.state.wave;
        var movement = mode == Mode.MOVEMENT ? new MovementCheck(join) : null;
        var initialEnemies = new IntSet();
        for (var unit : Groups.unit) {
            if (unit.team == Vars.state.rules.waveTeam) initialEnemies.add(unit.id);
        }
        boolean receivedState = false;
        while (System.nanoTime() < end) {
            long now = System.nanoTime();
            if (now >= tcpKeepAlive) {
                keepAlive(1);
                System.out.println("UPSTREAM_LIVE_NET_KEEPALIVE join=" + join + " transport=tcp");
                tcpKeepAlive = now + TCP_KEEP_ALIVE_NS;
            }
            if (now >= udpKeepAlive) {
                keepAlive(0);
                udpKeepAlive = now + UDP_KEEP_ALIVE_NS;
            }
            int received = receive(1, 0);
            if (received == TIMEOUT) {
                received = receive(0, 100);
                if (received >= 0) udpKeepAlive = System.nanoTime() + UDP_KEEP_ALIVE_NS;
            }
            if (received == TIMEOUT) continue;
            int id = received >>> 16;
            if (id == snapshotId) {
                var snapshot = applyState(received);
                if (!receivedState) {
                    receivedState = true;
                    System.out.println("UPSTREAM_LIVE_NET_STATE join=" + join + " wave=" + snapshot.wave
                        + " time=" + snapshot.timeData + " enemies=" + snapshot.enemies
                        + " coreBytes=" + snapshot.coreData.length);
                }
            } else if (id == entityId) {
                decode(new EntitySnapshotCallPacket(), received).handleClient();
            } else if (id == unitSpawnId) {
                handleUnitSpawn(received);
            } else if (id == unitDespawnId) {
                handleUnitDespawn(received);
            } else if (id == unitDestroyId) {
                handleUnitDestroy(received);
            }
            app.runPosted();
            if (movement != null) movement.update(now);
            if (receivedState && Vars.state.wave > initialWave && (movement == null || movement.finished())) {
                for (var unit : Groups.unit) {
                    if (unit.team == Vars.state.rules.waveTeam && unit.type == UnitTypes.dagger
                            && !initialEnemies.contains(unit.id)
                            && Groups.sync.getByID(unit.id) == unit) {
                        System.out.println("UPSTREAM_LIVE_NET_SYNC join=" + join + " fromWave=" + initialWave
                            + " wave=" + Vars.state.wave + " unit=" + unit.type.name + " id=" + unit.id);
                        return;
                    }
                }
            }
        }
        throw new IOException("Incomplete live state check; movement complete="
            + (movement == null || movement.finished()));
    }

    private void handleUnitSpawn(int received){
        // TypeIO.readUnitContainer adds the unit and returns null, so read the leading id
        // from the packet payload before the decoder consumes it.
        int id = reader(received).i();
        decode(new UnitSpawnCallPacket(), received).handleClient();
        System.out.println("UPSTREAM_LIVE_UNIT_SPAWN id=" + id);
    }

    private void handleUnitDespawn(int received){
        var despawn = decode(new UnitDespawnCallPacket(), received);
        despawn.handleClient();
        if (despawn.unit != null) {
            System.out.println("UPSTREAM_LIVE_UNIT_DESPAWN id=" + despawn.unit.id);
        }
    }

    private void handleUnitDestroy(int received){
        var destroy = decode(new UnitDestroyCallPacket(), received);
        destroy.handleClient();
        System.out.println("UPSTREAM_LIVE_UNIT_DESTROY id=" + destroy.uid);
    }

    private StateSnapshotCallPacket applyState(int received) throws IOException {
        var snapshot = decode(new StateSnapshotCallPacket(), received);
        snapshot.handleClient();
        if (Vars.state.wave != snapshot.wave || Vars.state.wavetime != snapshot.waveTime
                || Vars.state.enemies != snapshot.enemies || Vars.universe.seconds() != snapshot.timeData
                || Vars.state.serverTps != (snapshot.tps & 0xff)) {
            throw new IOException("Upstream snapshot did not update client state");
        }
        return snapshot;
    }

    private void driveLiveClient(UpstreamWorldLoadProbe.ProbeApplication app) throws IOException {
        var controls = new LiveControls(building);
        int lastWave = -1;
        // A slow first presentation must not postpone the stock TCP heartbeat.
        if (frame != null && Native.beginKeepAlive(1) == 0) {
            throw new IOException("Unable to keep the connection open during rendering");
        }
        keepAlive(1);
        long lastTcpSend = System.nanoTime();
        long lastUdpKeepAlive = lastTcpSend;
        long lastFrame = 0;
        int stateId = unsignedId(StateSnapshotCallPacket.class);
        int entityId = unsignedId(EntitySnapshotCallPacket.class);
        int unitSpawnId = unsignedId(UnitSpawnCallPacket.class);
        int unitDespawnId = unsignedId(UnitDespawnCallPacket.class);
        int unitDestroyId = unsignedId(UnitDestroyCallPacket.class);
        System.out.println("UPSTREAM_LIVE_PACKET_IDS spawn=" + unitSpawnId
            + " despawn=" + unitDespawnId + " destroy=" + unitDestroyId);
        System.out.println(frame == null ? "Circle Pad or D-pad moves. START leaves."
            : "Tap the build grid to place. R rotates. B toggles removal. Y opens chat. START leaves.");
        while (true) {
            int input = Native.input();
            if (input < 0) return;
            if (chat != null && (input & CHAT_PRESSED) != 0) {
                sendChat();
                continue;
            }
            long now = System.nanoTime();
            if (frame == null && now - lastTcpSend >= TCP_KEEP_ALIVE_NS) {
                keepAlive(1);
                lastTcpSend = now;
            }
            if (now - Math.max(lastUdpKeepAlive, controls.lastSnapshot) >= UDP_KEEP_ALIVE_NS) {
                keepAlive(0);
                lastUdpKeepAlive = now;
            }
            // Advance toward the previous snapshot before receiving new targets. Reading a
            // snapshot resets its interpolation clock and records the current pose.
            if (!Vars.state.isPaused()) {
                for (var unit : Groups.unit) {
                    if (!unit.isLocal()) unit.interpolate();
                }
            }
            lastWave = receiveLiveUpdates(stateId, entityId, unitSpawnId, unitDespawnId, unitDestroyId, lastWave);
            app.runPosted();
            now = System.nanoTime();
            controls.update(input, now);
            if (frame != null && now - lastFrame >= 33_333_333L) {
                var unit = controlledUnit();
                if (unit != null || lastFrame != 0) {
                    frame.accept(unit);
                    lastFrame = now;
                }
            }
        }
    }

    private int receiveLiveUpdates(int stateId, int entityId, int unitSpawnId, int unitDespawnId,
                                   int unitDestroyId, int lastWave) throws IOException {
        // Bound each drain by packet count. The graphical loop needs a larger budget so
        // reliable control packets are not stuck behind stock block snapshots.
        int budget = frame == null ? 16 : 128;
        for (int i = 0; i < budget; i++) {
            // Alternate to give construction traffic and periodic snapshots a turn.
            int preferred = nextLiveTransport;
            nextLiveTransport ^= 1;
            int received = receive(preferred, 0);
            if (received == TIMEOUT) received = receive(preferred ^ 1, 0);
            if (received == TIMEOUT && i == 0) received = receive(0, 8);
            if (received == TIMEOUT) break;
            int id = received >>> 16;
            if (id == stateId) {
                var unit = controlledUnit();
                var ore = unit == null || unit.mineTile == null ? null : unit.getMineResult(unit.mineTile);
                var core = ore == null ? null : Vars.player.team().core();
                int before = core == null ? 0 : core.items.get(ore);
                var state = applyState(received);
                if (core != null) {
                    int after = core.items.get(ore);
                    if (after != before) {
                        System.out.println("UPSTREAM_MINE_CORE_SYNC item=" + ore.name + " count=" + after);
                    }
                }
                if (lastWave != state.wave) {
                    lastWave = state.wave;
                    System.out.println("UPSTREAM_LIVE_CONTROLS_STATE wave=" + lastWave);
                }
            } else if (id == entityId) {
                decode(new EntitySnapshotCallPacket(), received).handleClient();
            } else if (id == unitSpawnId) {
                handleUnitSpawn(received);
            } else if (id == unitDespawnId) {
                handleUnitDespawn(received);
            } else if (id == unitDestroyId) {
                handleUnitDestroy(received);
            } else if (frame != null && id == unsignedId(CreateBulletCallPacket.class)) {
                decode(new CreateBulletCallPacket(), received).handleClient();
            } else if (id == unsignedId(CompleteObjectiveCallPacket.class)) {
                var completion = decode(new CompleteObjectiveCallPacket(), received);
                completion.handleClient();
                logObjectiveCompletion(completion.index);
            } else if (id == unsignedId(ClearObjectivesCallPacket.class)) {
                decode(new ClearObjectivesCallPacket(), received).handleClient();
            } else if (id == unsignedId(SetPositionCallPacket.class)) {
                decode(new SetPositionCallPacket(), received).handleClient();
            } else if (id == unsignedId(SetObjectivesCallPacket.class)) {
                decode(new SetObjectivesCallPacket(), received).handleClient();
            } else if (id == unsignedId(SetRulesCallPacket.class)) {
                decode(new SetRulesCallPacket(), received).handleClient();
            } else if (id == unsignedId(SetRuleCallPacket.class)) {
                decode(new SetRuleCallPacket(), received).handleClient();
            } else if (chat != null && id == unsignedId(SendMessageCallPacket.class)) {
                var message = decode(new SendMessageCallPacket(), received);
                message.handleClient();
                receiveChat(message.message, "server");
            } else if (chat != null && id == unsignedId(SendMessageCallPacket2.class)) {
                var message = decode(new SendMessageCallPacket2(), received);
                message.handleClient();
                receiveChat(message.message, "player");
            } else if (frame != null && id == unsignedId(SoundCallPacket.class)) {
                decode(new SoundCallPacket(), received).handleClient();
            } else if (frame != null && id == unsignedId(SoundAtCallPacket.class)) {
                decode(new SoundAtCallPacket(), received).handleClient();
            } else if (frame != null && id == unsignedId(CreateWeatherCallPacket.class)) {
                var weather = decode(new CreateWeatherCallPacket(), received);
                // Stock WeatherState is in Groups.sync. The server's snapshot supplies its ID
                // and state. Creating a second entity here can claim an ID used by a stock unit.
                System.out.println("UPSTREAM_LIVE_WEATHER name=" + weather.weather.name);
            } else {
                var marker = markerPacket(id);
                if (marker != null) {
                    decode(marker, received).handleClient();
                } else if (frame != null) {
                    applyBuildingUpdate(id, received);
                }
            }
        }
        // UnitDestroyCallPacket is unreliable; sweep dead wave units so a lost packet
        // cannot leave a destroyed enemy rendered by the graphical client.
        for (int i = Groups.unit.size() - 1; i >= 0; i--) {
            var unit = Groups.unit.index(i);
            if (unit.team == Vars.state.rules.waveTeam && (unit.dead || unit.health <= 0f)) {
                System.out.println("UPSTREAM_LIVE_UNIT_REMOVED id=" + unit.id);
                unit.remove();
            }
        }
        return lastWave;
    }

    private void receiveChat(String message, String source) {
        if (message == null) return;
        chat.accept(message);
        System.out.println("UPSTREAM_LIVE_CHAT_RECEIVED source=" + source + " chars=" + message.length());
    }

    private static void sendChat() throws IOException {
        byte[] text = new byte[4 * Vars.maxTextLength + 1];
        byte[] hint = (Core.bundle.get("keybind.chat.name") + "\0").getBytes(StandardCharsets.UTF_8);
        int length = Native.readChat(Address.ofData(text), text.length, Vars.maxTextLength, Address.ofData(hint));
        if (length <= 0 || length >= text.length) return;
        var message = new String(text, 0, length, StandardCharsets.UTF_8).trim();
        if (message.isEmpty() || message.length() > Vars.maxTextLength) return;
        var packet = new SendChatMessageCallPacket();
        packet.message = message;
        send(packet, true);
        System.out.println("UPSTREAM_LIVE_CHAT_SENT chars=" + message.length());
    }

    static Packet markerPacket(int id) {
        if (id == unsignedId(CreateMarkerCallPacket.class)) return new CreateMarkerCallPacket();
        if (id == unsignedId(UpdateMarkerCallPacket.class)) return new UpdateMarkerCallPacket();
        if (id == unsignedId(UpdateMarkerTextCallPacket.class)) return new UpdateMarkerTextCallPacket();
        if (id == unsignedId(UpdateMarkerTextureCallPacket.class)) return new UpdateMarkerTextureCallPacket();
        if (id == unsignedId(RemoveMarkerCallPacket.class)) return new RemoveMarkerCallPacket();
        return null;
    }

    private static void logObjectiveCompletion(int index) {
        var rules = Vars.state.rules;
        var objectives = rules.objectives;
        var completed = objectives.get(index);
        if (completed == null || !completed.isCompleted()) return;
        int next = -1;
        String nextKind = "none";
        for (int i = 0; i < objectives.all.size; i++) {
            var objective = objectives.all.get(i);
            if (objective.parents.contains(completed, true) && objective.qualified()) {
                next = i;
                nextKind = objective.getClass().getSimpleName();
                break;
            }
        }
        System.out.println("UPSTREAM_OBJECTIVE_COMPLETE index=" + index
            + " kind=" + completed.getClass().getSimpleName() + " next=" + next
            + " nextKind=" + nextKind + " flags=" + rules.objectiveFlags.size);
    }

    private void applyBuildingUpdate(int id, int received) {
        if (id == unsignedId(BeginPlaceCallPacket.class)) {
            decode(new BeginPlaceCallPacket(), received).handleClient();
        } else if (id == unsignedId(BeginBreakCallPacket.class)) {
            decode(new BeginBreakCallPacket(), received).handleClient();
        } else if (id == unsignedId(DeconstructFinishCallPacket.class)) {
            var finished = decode(new DeconstructFinishCallPacket(), received);
            finished.handleClient();
            if (finished.tile != null && finished.tile.block() == Blocks.air) {
                System.out.println("UPSTREAM_LIVE_REMOVED x=" + finished.tile.x + " y=" + finished.tile.y);
            }
        } else if (id == unsignedId(ConstructFinishCallPacket.class)) {
            var finished = decode(new ConstructFinishCallPacket(), received);
            finished.handleClient();
            var tile = finished.tile;
            if (tile != null && tile.build != null && tile.block() == finished.block) {
                System.out.println("UPSTREAM_LIVE_BUILD block=" + tile.block().name + " x=" + tile.x
                    + " y=" + tile.y + " rotation=" + tile.build.rotation + " team=" + tile.team().id);
            }
        } else if (id == unsignedId(RemoveQueueBlockCallPacket.class)) {
            decode(new RemoveQueueBlockCallPacket(), received).handleClient();
        } else if (id == unsignedId(TakeItemsCallPacket.class)) {
            decode(new TakeItemsCallPacket(), received).handleClient();
        } else if (id == unsignedId(CommandUnitsCallPacket.class)) {
            decode(new CommandUnitsCallPacket(), received).handleClient();
        } else if (id == unsignedId(CommandBuildingCallPacket.class)) {
            decode(new CommandBuildingCallPacket(), received).handleClient();
        } else if (id == unsignedId(UnitControlCallPacket.class)) {
            decode(new UnitControlCallPacket(), received).handleClient();
        } else if (id == unsignedId(UnitClearCallPacket.class)) {
            decode(new UnitClearCallPacket(), received).handleClient();
        } else if (id == unsignedId(UnitBuildingControlSelectCallPacket.class)) {
            decode(new UnitBuildingControlSelectCallPacket(), received).handleClient();
        } else if (id == unsignedId(SetUnitCommandCallPacket.class)) {
            decode(new SetUnitCommandCallPacket(), received).handleClient();
        } else if (id == unsignedId(SetUnitStanceCallPacket.class)) {
            decode(new SetUnitStanceCallPacket(), received).handleClient();
        } else if (id == unsignedId(TransferItemToCallPacket.class)) {
            decode(new TransferItemToCallPacket(), received).handleClient();
        } else if (id == unsignedId(BlockSnapshotCallPacket.class)) {
            var snapshot = decode(new BlockSnapshotCallPacket(), received);
            snapshot.handleClient();
            System.out.println("UPSTREAM_LIVE_BLOCK_SNAPSHOT records=" + snapshot.amount);
        } else if (id == unsignedId(TileConfigCallPacket.class)) {
            var config = decode(new TileConfigCallPacket(), received);
            config.handleClient();
            if (config.build instanceof Sorter.SorterBuild sorter) {
                var item = sorter.config();
                System.out.println("UPSTREAM_CONFIG_REMOTE x=" + sorter.tileX() + " y=" + sorter.tileY()
                    + " item=" + (item == null ? "none" : item.name));
            }
        }
    }

    private static Unit controlledUnit() {
        var player = Vars.player;
        var unit = player.unit();
        return unit == null || player.dead() || !(unit instanceof BlockUnitc) && Groups.sync.getByID(unit.id()) != unit
            ? null : unit;
    }

    private static void sendPosition(Unit unit, int sequence) throws IOException {
        sendPosition(unit, sequence, false);
    }

    // Match the player snapshot fields for movement and the upstream build queue in NetClient.sync.
    private static void sendPosition(Unit unit, int sequence, boolean building) throws IOException {
        send(playerSnapshot(unit, sequence, building), false);
    }

    static ClientSnapshotCallPacket playerSnapshot(Unit unit, int sequence, boolean building) {
        var player = Vars.player;
        var snapshot = new ClientSnapshotCallPacket();
        snapshot.snapshotID = sequence;
        snapshot.unitID = unit.id();
        snapshot.x = unit.x();
        snapshot.y = unit.y();
        snapshot.pointerX = unit.aimX();
        snapshot.pointerY = unit.aimY();
        snapshot.shooting = player.shooting;
        snapshot.rotation = unit.rotation();
        snapshot.baseRotation = unit instanceof Mechc mech ? mech.baseRotation() : 0f;
        snapshot.xVelocity = unit.vel.x;
        snapshot.yVelocity = unit.vel.y;
        snapshot.mining = unit.mineTile;
        snapshot.building = building;
        if (building) snapshot.plans = unit.plans();
        snapshot.selectedBlock = player.selectedBlock;
        snapshot.selectedRotation = player.selectedRotation;
        snapshot.viewX = unit.x();
        snapshot.viewY = unit.y();
        snapshot.viewWidth = 400f;
        snapshot.viewHeight = 240f;
        return snapshot;
    }

    private static final class LiveControls {
        private static final int AIM_HELD = 1 << 20, FIRE_HELD = 1 << 21;
        private static final long FRAME_NS = 16_666_667L;
        // The player snapshot cadence in pinned NetClient.
        private static final long SNAPSHOT_NS = 66_000_000L;
        private static final float DEAD_ZONE = 20f / 127f;
        private static final float STOP_SPEED = 0.03f;
        private final UpstreamLiveBuildControls buildingControls;
        private final Vec2 direction = new Vec2();
        private final UpstreamWeaponControl weapons = new UpstreamWeaponControl();
        private long lastStep, lastSnapshot;
        private int sequence;
        private Unit previousUnit;
        private boolean moving;
        private String inputDevice;

        LiveControls(UpstreamLiveBuildControls buildingControls) {
            this.buildingControls = buildingControls;
        }

        void update(int input, long now) throws IOException {
            var unit = controlledUnit();
            if (unit == null) {
                previousUnit = null;
                Vars.player.shooting = false;
                return;
            }
            if (previousUnit != unit) {
                previousUnit = unit;
                Vars.player.shooting = false;
                lastStep = now;
                moving = false;
                System.out.println("UPSTREAM_LIVE_CONTROLS_READY unit=" + unit.id()
                    + " x=" + unit.x() + " y=" + unit.y());
                System.out.println("UPSTREAM_CONTROL_FOCUS target=" + UpstreamPlayerControl.name(unit));
            }
            // Mirror PlayerComp.update before stock input rules consult the player's position.
            Vars.player.set(unit);
            if (buildingControls != null) buildingControls.update(unit, input, Native.touch());
            if (Vars.player.unit() != unit) return;
            direction.set((byte)input / 127f, (byte)(input >>> 8) / 127f).limit(1f);
            if (direction.len2() < DEAD_ZONE * DEAD_ZONE) direction.setZero();
            boolean anchored = unit instanceof BlockUnitc;
            boolean overlay = buildingControls != null && buildingControls.blocksWeaponInput();
            boolean aiming = (input & AIM_HELD) != 0;
            weapons.update(unit, direction, !overlay && (anchored || aiming) && !direction.isZero(),
                !overlay && (input & FIRE_HELD) != 0);
            if (anchored || aiming) direction.setZero();

            if (now - lastStep >= FRAME_NS) {
                float delta = Math.min(3f, (now - lastStep) / (float)FRAME_NS);
                lastStep = now;
                if (!anchored) stepUnit(unit, direction, delta);
                if (!direction.isZero() && !moving) {
                    moving = true;
                    inputDevice = (input & 0x10000) != 0 ? "dpad" : "circle";
                    System.out.println("UPSTREAM_LIVE_CONTROLS_MOVING unit=" + unit.id()
                        + " input=" + inputDevice);
                } else if (moving && direction.isZero() && unit.vel.len2() < STOP_SPEED * STOP_SPEED) {
                    unit.vel.setZero();
                    moving = false;
                    System.out.println("UPSTREAM_LIVE_CONTROLS_STOPPED unit=" + unit.id()
                        + " input=" + inputDevice + " x=" + unit.x() + " y=" + unit.y());
                }
            }
            if (unit.mineTile != null && !unit.validMine(unit.mineTile)) unit.mineTile = null;
            if (now - lastSnapshot >= SNAPSHOT_NS) {
                if (buildingControls != null) unit.validatePlans();
                sendPosition(unit, sequence++, buildingControls != null && !unit.plans().isEmpty());
                lastSnapshot = now;
            }
        }

        private static void stepUnit(Unit unit, Vec2 input, float delta) {
            float previousDelta = Time.delta;
            Time.delta = delta;
            try {
                if (Vars.player.shooting && unit.type().omniMovement && unit.type().faceTarget) {
                    unit.lookAt(unit.angleTo(unit.aimX(), unit.aimY()));
                } else {
                    unit.lookAt(unit.prefRotation());
                }
                unit.movePref(input.scl(unit.speed()));
                // Match the local position and drag update in upstream VelComp.
                float x = unit.x(), y = unit.y();
                unit.move(unit.vel.x * delta, unit.vel.y * delta);
                if (Mathf.equal(x, unit.x())) unit.vel.x = 0f;
                if (Mathf.equal(y, unit.y())) unit.vel.y = 0f;
                float groundDrag = unit.isGrounded() ? unit.floorOn().dragMultiplier : 1f;
                float drag = unit.type().drag * groundDrag * unit.dragMultiplier * Vars.state.rules.dragMultiplier;
                unit.vel.scl(Math.max(1f - drag * delta, 0f));
            } finally {
                Time.delta = previousDelta;
            }
        }
    }

    /** Exercises a short eastward input sequence on the server-assigned, upstream unit. */
    private static final class MovementCheck {
        private final int join;
        private final Vec2 direction = new Vec2();
        private int snapshotId;
        private int unitId;
        private int stoppedSamples;
        private float startX;
        private long nextSnapshot;

        private MovementCheck(int join) {
            this.join = join;
        }

        boolean finished() {
            return stoppedSamples == 4;
        }

        void update(long now) throws IOException {
            var unit = controlledUnit();
            if (finished() || now < nextSnapshot || unit == null) return;
            if (snapshotId == 0) {
                unitId = unit.id();
                startX = unit.x();
            }
            if (unit.id() != unitId) throw new IOException("Server-assigned unit changed during movement");

            if (unit.x() - startX < 32f) {
                unit.movePref(direction.set(unit.speed(), 0f));
                unit.move(unit.vel.x * Time.delta, unit.vel.y * Time.delta);
            } else {
                unit.vel.setZero();
                stoppedSamples++;
            }

            int sent = snapshotId++;
            sendPosition(unit, sent);
            nextSnapshot = now + 33_333_333L;
            if (finished()) {
                System.out.println("UPSTREAM_LIVE_NET_MOVEMENT join=" + join + " unit=" + unitId
                    + " snapshot=" + sent + " fromX=" + startX + " x=" + unit.x() + " y=" + unit.y());
            }
        }
    }

    private static void keepAlive(int reliable) throws IOException {
        if (Native.keepAlive(reliable) == 0) throw new IOException("Unable to send Arc keepalive");
    }

    private <T extends Packet> T decode(T decoded, int received) {
        decoded.read(reader(received), received & 0xffff);
        decoded.handled();
        return decoded;
    }

    private int receive(int reliable, int timeoutMs) throws IOException {
        int received = Native.receive(Address.ofData(packet), packet.length, reliable, timeoutMs);
        if (received < 0 && received != TIMEOUT) {
            throw new IOException("Live upstream socket receive failed: " + received);
        }
        return received;
    }

    private Reads reader(int received) {
        return new Reads(new DataInputStream(new ByteArrayInputStream(packet, 0, received & 0xffff)));
    }

    private static int unsignedId(Class<?> type) {
        return Net.getPacketClassId(type) & 0xff;
    }

    private static int open(byte[] host, int port, int version) {
        return Native.open(Address.ofData(host), host.length, port, version,
            unsignedId(KickCallPacket.class), unsignedId(KickCallPacket2.class));
    }

    /** Supplies original StreamChunk data without buffering a second copy of the entire world. */
    private final class WorldStreamInput extends InputStream {
        private final int streamId;
        private int remaining;
        private byte[] chunk = new byte[0];
        private int offset;

        WorldStreamInput(int streamId, int total) {
            this.streamId = streamId;
            remaining = total;
        }

        @Override
        public int read() throws IOException {
            return ready() ? chunk[offset++] & 0xff : -1;
        }

        @Override
        public int read(byte[] out, int start, int length) throws IOException {
            Objects.checkFromIndexSize(start, length, out.length);
            if (length == 0) return 0;
            if (!ready()) return -1;
            int count = Math.min(length, chunk.length - offset);
            System.arraycopy(chunk, offset, out, start, count);
            offset += count;
            return count;
        }

        private boolean ready() throws IOException {
            if (offset < chunk.length) return true;
            if (remaining == 0) return false;
            int received = TIMEOUT;
            for (int skipped = 0; skipped < 16; skipped++) {
                received = receive(1, 5000);
                if (received == TIMEOUT) throw new IOException("Timed out waiting for an upstream world chunk");
                int id = received >>> 16;
                if (id == unsignedId(StreamChunk.class)) break;
                if (id == unsignedId(KickCallPacket.class) || id == unsignedId(KickCallPacket2.class)) {
                    throw new IOException("Server kicked the client during the world transfer");
                }
            }
            if (received >>> 16 != unsignedId(StreamChunk.class)) {
                throw new IOException("Expected an upstream world chunk");
            }
            var next = new StreamChunk();
            next.read(reader(received));
            if (next.id != streamId || next.data.length == 0 || next.data.length > remaining
                    || next.data.length + 6 != (received & 0xffff)) {
                throw new IOException("World chunk differs from the declared stream");
            }
            chunk = next.data;
            offset = 0;
            remaining -= chunk.length;
            return true;
        }
    }

    @Include(value = "m3ds_net_bridge.h", isSystem = true)
    private static final class Native {
        @Import(name = "m3ds_live_net_open")
        static native int open(Address host, int length, int port, int version,
                               int kickTextId, int kickReasonId);
        @Import(name = "m3ds_live_net_stream_id")
        static native int streamId();
        @Import(name = "m3ds_live_net_stream_total")
        static native int streamTotal();
        @Import(name = "m3ds_live_net_stream_type")
        static native int streamType();
        @Import(name = "m3ds_live_net_packet_id")
        static native int rejectedPacket();
        @Import(name = "m3ds_live_net_receive")
        static native int receive(Address packet, int capacity, int reliable, int timeoutMs);
        @Import(name = "m3ds_live_net_send")
        static native int send(Address packet, int length, int packetId, int reliable);
        @Import(name = "m3ds_live_net_keep_alive")
        static native int keepAlive(int reliable);
        @Import(name = "m3ds_live_net_begin_keep_alive")
        static native int beginKeepAlive(int rendering);
        @Import(name = "m3ds_live_net_end_keep_alive")
        static native void endKeepAlive();
        @Import(name = "m3ds_live_net_input")
        static native int input();

        @Import(name = "m3ds_live_net_read_chat")
        static native int readChat(Address text, int capacity, int maxLength, Address hint);

        @Import(name = "m3ds_live_net_touch")
        static native int touch();
        @Import(name = "m3ds_live_net_close")
        static native void close();
    }
}
