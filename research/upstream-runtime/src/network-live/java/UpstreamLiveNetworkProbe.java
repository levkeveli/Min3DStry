import arc.files.Fi;
import arc.struct.IntSet;
import arc.util.io.Reads;
import arc.util.io.Writes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.GameState.State;
import mindustry.core.NetClient;
import mindustry.game.Universe;
import mindustry.gen.ConnectConfirmCallPacket;
import mindustry.gen.EntitySnapshotCallPacket;
import mindustry.gen.Groups;
import mindustry.gen.KickCallPacket;
import mindustry.gen.KickCallPacket2;
import mindustry.gen.Player;
import mindustry.gen.StateSnapshotCallPacket;
import mindustry.mod.Mods;
import mindustry.net.Net;
import mindustry.net.NetworkIO;
import mindustry.net.Packet;
import mindustry.net.Packets.StreamChunk;
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
    private final byte[] packet = new byte[32_768];

    public static void main(String[] args) throws IOException, InterruptedException {
        var config = new Fi(args.length == 0 ? "/3ds/min3dstry/server-endpoint.txt" : args[0]);
        String[] endpoint = config.readString().trim().split("\\s+");
        if (endpoint.length != 2) throw new IllegalArgumentException("Expected a server host and port");
        byte[] host = endpoint[0].getBytes(StandardCharsets.UTF_8);
        int port = Integer.parseInt(endpoint[1]);
        var app = UpstreamWorldLoadProbe.initialize(config.parent());
        Vars.mods = new Mods();
        Vars.universe = new Universe();
        try {
            new UpstreamLiveNetworkProbe().run(host, port, app);
        } catch (IOException | RuntimeException error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            System.out.println("UPSTREAM_LIVE_NET_ERROR " + cause.getClass().getSimpleName()
                + ": " + cause.getMessage());
            throw error;
        }
    }

    private void run(byte[] host, int port, UpstreamWorldLoadProbe.ProbeApplication app)
            throws IOException, InterruptedException {
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
        Vars.net.setClientConnected();
        Vars.netClient = NetClient.forSnapshotDispatch();
        int total = Native.streamTotal();
        var stream = new WorldStreamInput(Native.streamId(), total);
        Vars.player = Player.create();
        NetworkIO.loadWorld(new InflaterInputStream(stream));
        app.runPosted();
        if (stream.remaining != 0 || Groups.player.size() != 1 || !PLAYER_NAME.equals(Vars.player.name)
                || Vars.world.width() <= 0 || Vars.world.height() <= 0) {
            throw new IOException("Server world or assigned player did not load");
        }
        String fingerprint = UpstreamWorldLoadProbe.reportTerrain(false);
        System.out.println("UPSTREAM_LIVE_NET_WORLD join=" + join + " map=" + Vars.state.map.name()
            + " bytes=" + total + " team=" + Vars.player.team().id + " fingerprint=" + fingerprint);
        Vars.state.set(State.playing);
        confirm();
        waitForWave(join, app);
    }

    private static void confirm() throws IOException {
        var confirm = new ConnectConfirmCallPacket();
        var buffer = new ByteArrayOutputStream();
        confirm.write(new Writes(new DataOutputStream(buffer)));
        byte[] payload = buffer.toByteArray();
        if (Native.send(Address.ofData(payload), payload.length, Net.getPacketId(confirm) & 0xff, 1) == 0) {
            throw new IOException("Unable to send the upstream connection confirmation");
        }
    }

    private void waitForWave(int join, UpstreamWorldLoadProbe.ProbeApplication app) throws IOException {
        long start = System.nanoTime();
        long end = start + 30_000_000_000L;
        long tcpKeepAlive = start + TCP_KEEP_ALIVE_NS;
        long udpKeepAlive = start + UDP_KEEP_ALIVE_NS;
        int snapshotId = unsignedId(StateSnapshotCallPacket.class);
        int entityId = unsignedId(EntitySnapshotCallPacket.class);
        int initialWave = Vars.state.wave;
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
                var snapshot = decode(new StateSnapshotCallPacket(), received);
                snapshot.handleClient();
                if (Vars.state.wave != snapshot.wave || Vars.state.wavetime != snapshot.waveTime
                        || Vars.state.enemies != snapshot.enemies || Vars.universe.seconds() != snapshot.timeData
                        || Vars.state.serverTps != (snapshot.tps & 0xff)) {
                    throw new IOException("Upstream snapshot did not update client state");
                }
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
            if (receivedState && Vars.state.wave > initialWave) {
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
        throw new IOException("No new wave and synchronized enemy after connection confirmation");
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
        @Import(name = "m3ds_live_net_close")
        static native void close();
    }
}
