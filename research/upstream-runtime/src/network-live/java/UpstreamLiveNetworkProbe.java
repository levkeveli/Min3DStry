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
import mindustry.gen.ClientSnapshotCallPacket;
import mindustry.gen.ClearObjectivesCallPacket;
import mindustry.gen.CompleteObjectiveCallPacket;
import mindustry.gen.ConnectConfirmCallPacket;
import mindustry.gen.CreateMarkerCallPacket;
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
import mindustry.gen.SetObjectivesCallPacket;
import mindustry.gen.SetPositionCallPacket;
import mindustry.gen.StateSnapshotCallPacket;
import mindustry.gen.TileConfigCallPacket;
import mindustry.gen.Unit;
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
    // Match Arc's pinned TcpConnection and UdpConnection default intervals.
    private static final long TCP_KEEP_ALIVE_NS = 8_000_000_000L;
    private static final long UDP_KEEP_ALIVE_NS = 19_000_000_000L;
    private static final String PLAYER_NAME = "Min3DStry ARM11";
    private enum Mode { SNAPSHOTS, MOVEMENT, CONTROLS }
    private final byte[] packet = new byte[32_768];
    private final Mode mode;
    private final Consumer<Unit> frame;
    private final UpstreamLiveBuildControls building;
    private int nextLiveTransport = 1;

    private UpstreamLiveNetworkProbe(Mode mode, Consumer<Unit> frame, UpstreamLiveBuildControls building) {
        this.mode = mode;
        this.frame = frame;
        this.building = building;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        run(endpointFile(args), null, null, null);
    }

    static Fi endpointFile(String[] args) {
        return new Fi(args.length == 0 ? "/3ds/min3dstry/server-endpoint.txt" : args[0]);
    }

    static void run(Fi config, Runnable beforeConnect, Consumer<Unit> frame, UpstreamLiveBuildControls building)
            throws IOException, InterruptedException {
        String[] endpoint = config.readString().trim().split("\\s+");
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
            var app = UpstreamWorldLoadProbe.initialize(config.parent());
            stage = "services";
            Vars.mods = new Mods();
            Vars.universe = new Universe();
            if (frame != null) {
                Core.audio = new MockAudio();
                Vars.indexer = new BlockIndexer();
                Vars.pathfinder = new Pathfinder();
                Vars.fogControl = new FogControl();
            }
            stage = "graphics";
            if (beforeConnect != null) beforeConnect.run();
            stage = "session";
            new UpstreamLiveNetworkProbe(mode, frame, building).run(host, port, app);
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
        if (Native.beginWorldLoad() == 0) throw new IOException("Unable to keep the connection open during world loading");
        try {
            NetworkIO.loadWorld(new InflaterInputStream(stream));
            app.runPosted();
        } finally {
            Native.endWorldLoad();
        }
        if (stream.remaining != 0 || Groups.player.size() != 1 || !PLAYER_NAME.equals(Vars.player.name)
                || Vars.world.width() <= 0 || Vars.world.height() <= 0) {
            throw new IOException("Server world or assigned player did not load");
        }
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
        Vars.state.set(State.playing);
        send(new ConnectConfirmCallPacket(), true);
        if (mode == Mode.CONTROLS) {
            driveLiveClient(app);
        } else {
            waitForWave(join, app);
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
        // Leave headroom for rendering on an ARM11 while the server runs its own clock.
        long tcpKeepAliveInterval = frame == null ? TCP_KEEP_ALIVE_NS : TCP_KEEP_ALIVE_NS / 2;
        keepAlive(1);
        long lastTcpSend = System.nanoTime();
        long lastUdpKeepAlive = lastTcpSend;
        long lastFrame = 0;
        int stateId = unsignedId(StateSnapshotCallPacket.class);
        int entityId = unsignedId(EntitySnapshotCallPacket.class);
        System.out.println(frame == null ? "Circle Pad or D-pad moves. START leaves."
            : "Tap the build grid to place. R rotates. B toggles removal. START leaves.");
        while (true) {
            int input = Native.input();
            if (input < 0) return;
            long now = System.nanoTime();
            if (now - lastTcpSend >= tcpKeepAliveInterval) {
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
            lastWave = receiveLiveUpdates(stateId, entityId, lastWave);
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

    private int receiveLiveUpdates(int stateId, int entityId, int lastWave) throws IOException {
        // Bound each drain by packet count. A slow render can leave several snapshots queued,
        // and checking a short frame deadline after each packet lets that queue keep growing.
        for (int i = 0; i < 16; i++) {
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
            } else {
                var marker = markerPacket(id);
                if (marker != null) {
                    decode(marker, received).handleClient();
                } else if (frame != null) {
                    applyBuildingUpdate(id, received);
                }
            }
        }
        return lastWave;
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
        return unit == null || player.dead() || Groups.sync.getByID(unit.id()) != unit ? null : unit;
    }

    private static void sendPosition(Unit unit, int sequence) throws IOException {
        sendPosition(unit, sequence, false);
    }

    // Match the player snapshot fields for movement and the upstream build queue in NetClient.sync.
    private static void sendPosition(Unit unit, int sequence, boolean building) throws IOException {
        var player = Vars.player;
        var snapshot = new ClientSnapshotCallPacket();
        snapshot.snapshotID = sequence;
        snapshot.unitID = unit.id();
        snapshot.x = unit.x();
        snapshot.y = unit.y();
        snapshot.pointerX = unit.aimX();
        snapshot.pointerY = unit.aimY();
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
        send(snapshot, false);
    }

    private static final class LiveControls {
        private static final long FRAME_NS = 16_666_667L;
        // The player snapshot cadence in pinned NetClient.
        private static final long SNAPSHOT_NS = 66_000_000L;
        private static final float DEAD_ZONE = 20f / 127f;
        private static final float STOP_SPEED = 0.03f;
        private final UpstreamLiveBuildControls buildingControls;
        private final Vec2 direction = new Vec2();
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
                return;
            }
            if (previousUnit != unit) {
                previousUnit = unit;
                lastStep = now;
                moving = false;
                System.out.println("UPSTREAM_LIVE_CONTROLS_READY unit=" + unit.id()
                    + " x=" + unit.x() + " y=" + unit.y());
            }
            if (buildingControls != null) buildingControls.update(unit, input, Native.touch());
            if (now - lastStep >= FRAME_NS) {
                direction.set((byte)input / 127f, (byte)(input >>> 8) / 127f).limit(1f);
                if (direction.len2() < DEAD_ZONE * DEAD_ZONE) direction.setZero();
                float delta = Math.min(3f, (now - lastStep) / (float)FRAME_NS);
                lastStep = now;
                stepUnit(unit, direction, delta);
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
                unit.lookAt(unit.prefRotation());
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
        @Import(name = "m3ds_live_net_begin_world_load")
        static native int beginWorldLoad();
        @Import(name = "m3ds_live_net_end_world_load")
        static native void endWorldLoad();
        @Import(name = "m3ds_live_net_input")
        static native int input();

        @Import(name = "m3ds_live_net_touch")
        static native int touch();
        @Import(name = "m3ds_live_net_close")
        static native void close();
    }
}
