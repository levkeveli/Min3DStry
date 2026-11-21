import arc.ApplicationCore;
import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.backend.headless.HeadlessApplication;
import arc.net.ArcNetException;
import arc.net.Client;
import arc.net.Connection;
import arc.net.DcReason;
import arc.net.NetListener;
import arc.net.NetSerializer;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.serialization.Base64Coder;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Version;
import mindustry.core.World;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.BeginBreakCallPacket;
import mindustry.gen.BeginPlaceCallPacket;
import mindustry.gen.BlockSnapshotCallPacket;
import mindustry.gen.Building;
import mindustry.gen.ClientSnapshotCallPacket;
import mindustry.gen.ConnectConfirmCallPacket;
import mindustry.gen.ConstructFinishCallPacket;
import mindustry.gen.DeconstructFinishCallPacket;
import mindustry.gen.EntitySnapshotCallPacket;
import mindustry.gen.Groups;
import mindustry.gen.KickCallPacket2;
import mindustry.gen.Player;
import mindustry.gen.RemoveQueueBlockCallPacket;
import mindustry.gen.StateSnapshotCallPacket;
import mindustry.gen.Unit;
import mindustry.net.ArcNetProvider;
import mindustry.net.Host;
import mindustry.net.Net;
import mindustry.net.NetworkIO;
import mindustry.net.Packet;
import mindustry.net.Packets;
import mindustry.net.Streamable;
import mindustry.world.Tile;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.InflaterInputStream;

/**
 * Host-only end-to-end multiplayer session verifier against an unmodified
 * pinned Mindustry v160.8 server (19a0f40d44514f4802a8b79770456e7e32e34da0 /
 * Arc 7445105cd2683daf5b9ac7033ebb07265d3e814d). Advances #44 without writing
 * persistent config files into the repository working tree.
 */
public class UpstreamNetSessionBaseline {
    private static final LZ4SafeDecompressor LZ4_DECOMPRESSOR =
        LZ4Factory.fastestInstance().safeDecompressor();

    private static final class CapturingSerializer implements NetSerializer {
        private final ArcNetProvider.PacketSerializer delegate = new ArcNetProvider.PacketSerializer();
        final AtomicInteger lz4Count = new AtomicInteger();
        final Object captureLock = new Object();
        byte[] firstLz4Frame;
        byte[] firstLz4Expected;
        int firstLz4PacketId = -1;

        @Override
        public Object read(ByteBuffer buffer) {
            int start = buffer.position();
            int remaining = buffer.remaining();
            byte[] capturedFrame = null;
            byte[] expectedPayload = null;
            int packetId = -1;

            if (remaining >= 4 && buffer.get(start) != (byte) -2) {
                byte compression = buffer.get(start + 3);
                if (compression == 1) {
                    packetId = buffer.get(start) & 0xFF;
                    int uncompressedLen = buffer.getShort(start + 1) & 0xFFFF;
                    capturedFrame = new byte[remaining];
                    for (int i = 0; i < remaining; i++) {
                        capturedFrame[i] = buffer.get(start + i);
                    }
                    expectedPayload = new byte[uncompressedLen];
                    LZ4_DECOMPRESSOR.decompress(
                        capturedFrame, 4, remaining - 4, expectedPayload, 0, uncompressedLen);
                }
            }

            Object decoded = delegate.read(buffer);
            if (capturedFrame != null) {
                synchronized (captureLock) {
                    if (firstLz4Frame == null) {
                        firstLz4Frame = capturedFrame;
                        firstLz4Expected = expectedPayload;
                        firstLz4PacketId = packetId;
                    }
                }
                lz4Count.incrementAndGet();
            }
            return decoded;
        }

        @Override
        public void write(ByteBuffer buffer, Object object) {
            delegate.write(buffer, object);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--dump-connect-vector".equals(args[0])) {
            dumpConnectVector();
            return;
        }
        if (args.length != 2 && !(args.length == 4 && "--dump-lz4-dir".equals(args[2]))) {
            System.err.println(
                "Usage: UpstreamNetSessionBaseline <host> <port> [--dump-lz4-dir <dir>] | --dump-connect-vector");
            System.exit(2);
        }

        final String host = args[0];
        final int port = Integer.parseInt(args[1]);
        final Path dumpDir = args.length == 4 ? Path.of(args[3]) : null;
        final Path tempDataDir = Files.createTempDirectory("min3dstry-net-session-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(tempDataDir)));

        new SessionApp(new ApplicationCore() {
            @Override
            public void setup() {
                Core.settings.setDataDirectory(new Fi(tempDataDir.toFile()));
                Vars.headless = true;
                Vars.net = new Net(null);
                Vars.init();
                Vars.world = new World();
                Vars.content.createBaseContent();
                add(Vars.logic = new Logic());
                add(Vars.netServer = new NetServer());
                add(Vars.netClient = new NetClient());
                Vars.content.init();
                if (Vars.player == null) {
                    Vars.player = Player.create();
                }
            }

            @Override
            public void init() {
                super.init();
                try {
                    runSuite((SessionApp) Core.app, host, port, dumpDir);
                    Core.app.exit();
                } catch (Throwable t) {
                    t.printStackTrace(System.err);
                    System.exit(1);
                }
            }
        }, error -> {
            error.printStackTrace(System.err);
            System.exit(1);
        });
    }

    private static final class SessionApp extends HeadlessApplication {
        SessionApp(ApplicationCore listener, Cons<Throwable> errorHandler) {
            super(listener, errorHandler);
        }

        void runPosted() {
            runnables.run();
        }
    }

    private static void dumpConnectVector() {
        Vars.net = new Net(null);
        Version.build = 160;
        Version.type = "official";

        Packets.ConnectPacket pkt = new Packets.ConnectPacket();
        pkt.version = 160;
        pkt.versionType = "official";
        pkt.name = "Min3DStryLZ4";
        pkt.locale = "en_US";
        pkt.usid = "m3ds0001";
        pkt.uuid = new String(Base64Coder.encode(new byte[] {0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48}));
        pkt.mobile = true;
        pkt.color = 0xFFB841FF;
        pkt.mods = new Seq<>();

        ByteBuffer wire = ByteBuffer.allocate(512);
        new ArcNetProvider.PacketSerializer().write(wire, pkt);
        wire.flip();
        byte[] bytes = new byte[wire.remaining()];
        wire.get(bytes);
        if (bytes.length < 4 || bytes[3] != 1) {
            throw new IllegalStateException("Expected LZ4-compressed ConnectPacket (compression == 1)");
        }
        System.out.printf(
            Locale.ROOT,
            "CONNECT_LZ4_VECTOR len=%d hex=%s%n",
            bytes.length,
            HexFormat.of().formatHex(bytes));
    }

    private static void runSuite(SessionApp app, String host, int port, Path dumpDir) throws Exception {
        Host discovered = discoverHost(host, port);
        if (!"Glacier".equals(discovered.mapname) || discovered.version != 160 || !"official".equals(discovered.versionType)) {
            throw new IllegalStateException("Unexpected discovery host info: " + discovered.mapname
                + " version=" + discovered.version + " type=" + discovered.versionType);
        }
        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_NET_DISCOVERY map=%s version=%d vertype=%s players=%d wave=%d%n",
            discovered.mapname,
            discovered.version,
            discovered.versionType,
            discovered.players,
            discovered.wave);

        verifyVersionMismatch(host, port);

        int totalLz4 = 0;
        totalLz4 += runCompatibleSession(app, 1, host, port, dumpDir);
        resetClientSessionState(app);
        Thread.sleep(350L);
        totalLz4 += runCompatibleSession(app, 2, host, port, null);
        resetClientSessionState(app);

        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_NET_PASS sessions=2 mismatch=clientOutdated lz4_packets=%d%n",
            totalLz4);
    }

    private static Host discoverHost(String host, int port) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            byte[] req = new byte[] {(byte) -2, 1};
            socket.send(new DatagramPacket(req, req.length, InetAddress.getByName(host), port));
            byte[] buf = new byte[1024];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            ByteBuffer bb = ByteBuffer.wrap(resp.getData(), 0, resp.getLength());
            Host info = NetworkIO.readServerData(1, resp.getAddress().getHostAddress(), bb);
            info.port = port;
            return info;
        }
    }

    private static void verifyVersionMismatch(String host, int port) throws Exception {
        Version.build = 159;
        Version.type = "official";

        ArcNetProvider.PacketSerializer serializer = new ArcNetProvider.PacketSerializer();
        CountDownLatch kickLatch = new CountDownLatch(1);
        AtomicReference<Packets.KickReason> reasonRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Client client = new Client(16384, ArcNetProvider.clientReadBufferSize, serializer) {
            @Override
            public void handleNetException(ArcNetException e) {
                errorRef.compareAndSet(null, e);
            }
        };
        client.addListener(new NetListener() {
            @Override
            public void connected(Connection connection) {
                client.sendTCP(makeConnectPacket("Min3DStry159", new byte[] {0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58}));
            }

            @Override
            public void received(Connection connection, Object object) {
                if (object instanceof KickCallPacket2 kick) {
                    kick.handled();
                    reasonRef.set(kick.reason);
                    kickLatch.countDown();
                } else if (object instanceof Packets.Disconnect) {
                    kickLatch.countDown();
                }
            }
        });

        Thread netThread = new Thread(client, "NetSessionMismatch");
        netThread.setDaemon(true);
        netThread.start();
        try {
            client.connect(5000, host, port, port);
            if (!kickLatch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for clientOutdated kick");
            }
            if (reasonRef.get() != Packets.KickReason.clientOutdated) {
                throw new IllegalStateException("Expected clientOutdated kick, got: " + reasonRef.get()
                    + " err=" + errorRef.get());
            }
            System.out.println("UPSTREAM_NET_MISMATCH build=159 kick=clientOutdated");
        } finally {
            closeClientQuietly(client);
        }
    }

    private static final class SessionProgress {
        int stateSnapshots;
        int entitySnapshots;
        boolean conveyorBuilt;
        boolean wallBuilt;
        boolean wallDeconstructed;
        int completionStateCount = -1;
    }

    private static int runCompatibleSession(
            SessionApp app, int runIndex, String host, int port, Path dumpDir) throws Exception {
        Version.build = 160;
        Version.type = "official";

        CapturingSerializer serializer = new CapturingSerializer();
        CountDownLatch worldReadyLatch = new CountDownLatch(1);
        ConcurrentLinkedQueue<Packet> inbox = new ConcurrentLinkedQueue<>();
        AtomicReference<byte[]> worldBytesRef = new AtomicReference<>();
        AtomicReference<Streamable.StreamBuilder> streamRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean confirmed = new AtomicBoolean(false);

        Client client = new Client(16384, ArcNetProvider.clientReadBufferSize, serializer) {
            @Override
            public void handleNetException(ArcNetException e) {
                errorRef.compareAndSet(null, e);
            }
        };
        client.addListener(new NetListener() {
            @Override
            public void connected(Connection connection) {
                // Use identical UUID across run 1 and run 2 to exercise clean disconnect/rejoin.
                client.sendTCP(makeConnectPacket(
                    "Min3DStrySes",
                    new byte[] {0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68}));
            }

            @Override
            public void received(Connection connection, Object object) {
                try {
                    if (object instanceof Packets.StreamBegin begin) {
                        streamRef.set(new Streamable.StreamBuilder(begin, false));
                    } else if (object instanceof Packets.StreamChunk chunk) {
                        Streamable.StreamBuilder builder = streamRef.get();
                        if (builder != null && builder.id == chunk.id) {
                            builder.add(chunk.data);
                            if (builder.isDone()) {
                                worldBytesRef.set(builder.stream.toByteArray());
                                streamRef.set(null);
                                worldReadyLatch.countDown();
                            }
                        }
                    } else if (object instanceof KickCallPacket2 || isGameplayPacket(object)) {
                        inbox.add((Packet) object);
                    }
                } catch (Throwable t) {
                    errorRef.compareAndSet(null, t);
                    worldReadyLatch.countDown();
                }
            }

            @Override
            public void disconnected(Connection connection, DcReason reason) {
                if (!confirmed.get()) {
                    errorRef.compareAndSet(
                        null, new IllegalStateException("Disconnected before WorldStream: " + reason));
                    worldReadyLatch.countDown();
                }
            }
        });

        Thread netThread = new Thread(client, "NetSessionRun" + runIndex);
        netThread.setDaemon(true);
        netThread.start();
        try {
            client.connect(5000, host, port, port);
            if (!worldReadyLatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for WorldStream (run=" + runIndex + ")");
            }
            if (errorRef.get() != null) {
                throw new IllegalStateException("Session failed waiting for WorldStream", errorRef.get());
            }
            byte[] worldBytes = worldBytesRef.get();
            if (worldBytes == null || worldBytes.length == 0) {
                throw new IllegalStateException("Empty WorldStream payload");
            }

            Vars.net.setClientConnected();
            Vars.player = Player.create();
            try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(worldBytes))) {
                NetworkIO.loadWorld(in);
            }
            app.runPosted();
            Vars.state.set(GameState.State.playing);

            int initialBuildings = verifyInitialSessionWorld(runIndex);

            confirmed.set(true);
            client.sendTCP(new ConnectConfirmCallPacket());

            SessionProgress progress = driveSessionLoop(app, client, runIndex, serializer, inbox, errorRef);
            int finalBuildings = verifyPostSessionState(runIndex, initialBuildings, progress);

            if (runIndex == 1 && dumpDir != null) {
                dumpFirstLz4Packet(serializer, dumpDir);
            }

            int capturedLz4 = serializer.lz4Count.get();
            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_NET_SESSION run=%d map=%s width=%d height=%d buildings=%d wave=%d playerId=%d state_snapshots=%d entity_snapshots=%d lz4_packets=%d%n",
                runIndex,
                Vars.state.map.name(),
                Vars.world.width(),
                Vars.world.height(),
                finalBuildings,
                Vars.state.wave,
                Vars.player.id,
                progress.stateSnapshots,
                progress.entitySnapshots,
                capturedLz4);
            return capturedLz4;
        } finally {
            closeClientQuietly(client);
        }
    }

    private static boolean isGameplayPacket(Object object) {
        return object instanceof StateSnapshotCallPacket
            || object instanceof EntitySnapshotCallPacket
            || object instanceof BeginPlaceCallPacket
            || object instanceof ConstructFinishCallPacket
            || object instanceof BeginBreakCallPacket
            || object instanceof DeconstructFinishCallPacket
            || object instanceof RemoveQueueBlockCallPacket
            || object instanceof BlockSnapshotCallPacket;
    }

    private static int verifyInitialSessionWorld(int runIndex) {
        int originBuildings = countOriginBuildings();
        if (Vars.world.width() != 150 || Vars.world.height() != 250
                || Vars.state.wave < 1 || Vars.player.id <= 0) {
            throw new IllegalStateException(
                "Unexpected loaded world dimensions/player (run=" + runIndex + "): w=" + Vars.world.width()
                    + " h=" + Vars.world.height()
                    + " buildings=" + originBuildings
                    + " wave=" + Vars.state.wave
                    + " playerId=" + Vars.player.id);
        }
        int copper = coreCopper();
        Tile conveyorTile = Vars.world.tile(85, 26);
        Tile wallTile = Vars.world.tile(86, 26);
        if (runIndex == 1) {
            if (originBuildings != 2
                    || conveyorTile.block() != Blocks.air
                    || wallTile.block() != Blocks.air
                    || copper != 200) {
                throw new IllegalStateException(
                    "Unexpected Session 1 initial state: buildings=" + originBuildings
                        + " tile85=" + conveyorTile.block().name
                        + " tile86=" + wallTile.block().name
                        + " copper=" + copper);
            }
        } else {
            if (originBuildings != 4
                    || conveyorTile.block() != Blocks.conveyor
                    || conveyorTile.build == null
                    || conveyorTile.build.rotation != 1
                    || conveyorTile.team() != Vars.player.team()
                    || wallTile.block() != Blocks.copperWall
                    || wallTile.team() != Vars.player.team()
                    || copper != 193) {
                throw new IllegalStateException(
                    "Session 2 rejoin WorldStream did not preserve Session 1 state: buildings=" + originBuildings
                        + " tile85=" + conveyorTile.block().name
                        + " rot85=" + (conveyorTile.build == null ? -1 : conveyorTile.build.rotation)
                        + " tile86=" + wallTile.block().name
                        + " copper=" + copper);
            }
        }
        return originBuildings;
    }

    private static SessionProgress driveSessionLoop(
            SessionApp app,
            Client client,
            int runIndex,
            CapturingSerializer serializer,
            ConcurrentLinkedQueue<Packet> inbox,
            AtomicReference<Throwable> errorRef) throws InterruptedException {
        SessionProgress progress = new SessionProgress();
        int snapshotSequence = 0;
        long lastSnapshotMs = 0L;
        boolean finalIdleSnapshotSent = false;
        long deadlineMs = System.currentTimeMillis() + 15_000L;

        while (System.currentTimeMillis() < deadlineMs) {
            if (errorRef.get() != null) {
                throw new IllegalStateException("Session network error during live gameplay", errorRef.get());
            }
            drainInbox(app, inbox, progress);

            boolean actionsDone = runIndex == 1
                ? (progress.conveyorBuilt && progress.wallBuilt)
                : progress.wallDeconstructed;
            if (actionsDone && progress.completionStateCount < 0) {
                progress.completionStateCount = progress.stateSnapshots;
            }

            Unit unit = controlledUnit();
            long nowMs = System.currentTimeMillis();
            if (unit != null) {
                if (!actionsDone && nowMs - lastSnapshotMs >= 66L) {
                    sendClientSnapshot(client, unit, snapshotSequence++, runIndex, progress);
                    lastSnapshotMs = nowMs;
                } else if (actionsDone && !finalIdleSnapshotSent) {
                    sendClientSnapshot(client, unit, snapshotSequence++, runIndex, progress);
                    finalIdleSnapshotSent = true;
                }
            }

            if (isSessionComplete(runIndex, serializer, progress, actionsDone)) {
                return progress;
            }
            Thread.sleep(20L);
        }

        throw new IllegalStateException(
            "Timed out waiting for live gameplay sync (run=" + runIndex
                + " states=" + progress.stateSnapshots
                + " entities=" + progress.entitySnapshots
                + " lz4=" + serializer.lz4Count.get()
                + " conveyorBuilt=" + progress.conveyorBuilt
                + " wallBuilt=" + progress.wallBuilt
                + " wallDeconstructed=" + progress.wallDeconstructed
                + " copper=" + coreCopper()
                + " unit=" + controlledUnit() + ")");
    }

    private static void drainInbox(
            SessionApp app, ConcurrentLinkedQueue<Packet> inbox, SessionProgress progress) {
        Packet pkt;
        while ((pkt = inbox.poll()) != null) {
            if (pkt instanceof KickCallPacket2 kick) {
                kick.handled();
                throw new IllegalStateException("Unexpected kick in compatible session: " + kick.reason);
            }
            pkt.handled();
            pkt.handleClient();
            app.runPosted();

            if (pkt instanceof StateSnapshotCallPacket snap) {
                if (snap.wave < 1 || snap.coreData == null) {
                    throw new IllegalStateException("Invalid StateSnapshotCallPacket payload");
                }
                progress.stateSnapshots++;
            } else if (pkt instanceof EntitySnapshotCallPacket) {
                progress.entitySnapshots++;
            } else if (pkt instanceof ConstructFinishCallPacket finish && finish.tile != null) {
                if (finish.tile.x == 85 && finish.tile.y == 26
                        && finish.block == Blocks.conveyor
                        && finish.rotation == 1
                        && finish.team == Vars.player.team()) {
                    progress.conveyorBuilt = true;
                } else if (finish.tile.x == 86 && finish.tile.y == 26
                        && finish.block == Blocks.copperWall
                        && finish.team == Vars.player.team()) {
                    progress.wallBuilt = true;
                }
            } else if (pkt instanceof DeconstructFinishCallPacket finish && finish.tile != null) {
                if (finish.tile.x == 86 && finish.tile.y == 26
                        && finish.block == Blocks.copperWall) {
                    progress.wallDeconstructed = true;
                }
            }
        }
    }

    private static void sendClientSnapshot(
            Client client, Unit unit, int sequence, int runIndex, SessionProgress progress) {
        Queue<BuildPlan> plans = new Queue<>();
        if (runIndex == 1) {
            if (!progress.conveyorBuilt) {
                plans.addLast(new BuildPlan(85, 26, 1, Blocks.conveyor));
            }
            if (!progress.wallBuilt) {
                plans.addLast(new BuildPlan(86, 26, 0, Blocks.copperWall));
            }
        } else {
            if (!progress.wallDeconstructed) {
                plans.addLast(new BuildPlan(86, 26));
            }
        }

        ClientSnapshotCallPacket snap = new ClientSnapshotCallPacket();
        snap.snapshotID = sequence;
        snap.unitID = unit.id();
        snap.dead = false;
        snap.x = unit.x();
        snap.y = unit.y();
        snap.pointerX = unit.x();
        snap.pointerY = unit.y();
        snap.rotation = unit.rotation();
        snap.baseRotation = 0f;
        snap.xVelocity = 0f;
        snap.yVelocity = 0f;
        snap.mining = null;
        snap.boosting = false;
        snap.shooting = false;
        snap.chatting = false;
        snap.building = !plans.isEmpty();
        snap.plans = snap.building ? plans : null;
        snap.selectedBlock = null;
        snap.selectedRotation = 0;
        snap.viewX = unit.x();
        snap.viewY = unit.y();
        snap.viewWidth = 400f;
        snap.viewHeight = 240f;
        client.sendUDP(snap);
    }

    private static boolean isSessionComplete(
            int runIndex,
            CapturingSerializer serializer,
            SessionProgress progress,
            boolean actionsDone) {
        if (!actionsDone
                || progress.stateSnapshots <= progress.completionStateCount
                || progress.entitySnapshots < 1
                || serializer.lz4Count.get() < 1) {
            return false;
        }
        int expectedCopper = runIndex == 1 ? 193 : 196;
        return coreCopper() == expectedCopper;
    }

    private static int verifyPostSessionState(
            int runIndex, int initialBuildings, SessionProgress progress) {
        int finalBuildings = countOriginBuildings();
        int copper = coreCopper();
        Tile conveyorTile = Vars.world.tile(85, 26);
        Tile wallTile = Vars.world.tile(86, 26);
        if (conveyorTile.block() != Blocks.conveyor
                || conveyorTile.build == null
                || conveyorTile.build.rotation != 1
                || conveyorTile.team() != Vars.player.team()) {
            throw new IllegalStateException("Expected Conveyor@rot1 at (85, 26), found " + conveyorTile.block().name);
        }

        if (runIndex == 1) {
            if (!progress.conveyorBuilt || !progress.wallBuilt
                    || wallTile.block() != Blocks.copperWall
                    || wallTile.team() != Vars.player.team()
                    || finalBuildings != 4
                    || copper != 193) {
                throw new IllegalStateException(
                    "Unexpected Session 1 post-build state: buildings=" + finalBuildings
                        + " wall=" + wallTile.block().name + " copper=" + copper);
            }
            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_NET_ACTION run=1 op=build conveyor=85,26@rot1 wall=86,26 copper=200->%d buildings=%d->%d%n",
                copper,
                initialBuildings,
                finalBuildings);
        } else {
            if (!progress.wallDeconstructed
                    || wallTile.block() != Blocks.air
                    || finalBuildings != 3
                    || copper != 196) {
                throw new IllegalStateException(
                    "Unexpected Session 2 post-deconstruct state: buildings=" + finalBuildings
                        + " wall=" + wallTile.block().name + " copper=" + copper);
            }
            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_NET_ACTION run=2 op=rejoin_deconstruct persisted=%d wall_removed=86,26 copper=193->%d buildings=%d->%d%n",
                initialBuildings,
                copper,
                initialBuildings,
                finalBuildings);
        }
        return finalBuildings;
    }

    private static void dumpFirstLz4Packet(CapturingSerializer serializer, Path dumpDir)
            throws IOException, NoSuchAlgorithmException {
        byte[] frame;
        byte[] expected;
        int pktId;
        synchronized (serializer.captureLock) {
            frame = serializer.firstLz4Frame;
            expected = serializer.firstLz4Expected;
            pktId = serializer.firstLz4PacketId;
        }
        if (frame == null || expected == null) {
            throw new IllegalStateException("No LZ4 packet captured to dump");
        }
        Files.createDirectories(dumpDir);
        Files.write(dumpDir.resolve("lz4-frame.bin"), frame);
        Files.write(dumpDir.resolve("lz4-expected.bin"), expected);
        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_LZ4_PACKET id=%d compressed_bytes=%d payload_len=%d sha256=%s%n",
            pktId,
            frame.length,
            expected.length,
            sha256Hex(expected));
    }

    private static void resetClientSessionState(SessionApp app) {
        Vars.net = new Net(null);
        Vars.logic.reset();
        app.runPosted();
    }

    private static Unit controlledUnit() {
        Player player = Vars.player;
        if (player == null) {
            return null;
        }
        Unit unit = player.unit();
        return unit == null || player.dead() || Groups.sync.getByID(unit.id()) != unit ? null : unit;
    }

    private static int countOriginBuildings() {
        int originBuildings = 0;
        for (Tile tile : Vars.world.tiles) {
            Building b = tile.build;
            if (b != null && b.tile == tile) {
                originBuildings++;
            }
        }
        return originBuildings;
    }

    private static int coreCopper() {
        var core = Vars.player != null && Vars.player.team() != null ? Vars.player.team().core() : null;
        return core == null ? -1 : core.items.get(Items.copper);
    }

    private static Packets.ConnectPacket makeConnectPacket(String name, byte[] uuidRaw) {
        Packets.ConnectPacket pkt = new Packets.ConnectPacket();
        pkt.version = Version.build;
        pkt.versionType = Version.type;
        pkt.name = name;
        pkt.locale = "en_US";
        pkt.usid = "m3ds-ses";
        pkt.uuid = new String(Base64Coder.encode(uuidRaw));
        pkt.mobile = true;
        pkt.color = 0xFFB841FF;
        pkt.mods = new Seq<>();
        return pkt;
    }

    private static void closeClientQuietly(Client client) {
        try {
            client.close();
        } catch (Throwable ignored) {
        }
        try {
            client.stop();
        } catch (Throwable ignored) {
        }
        try {
            client.dispose();
        } catch (Throwable ignored) {
        }
    }

    private static String sha256Hex(byte[] data) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
