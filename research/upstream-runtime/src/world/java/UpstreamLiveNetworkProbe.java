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
import mindustry.gen.ConnectConfirmCallPacket;
import mindustry.gen.Groups;
import mindustry.gen.KickCallPacket2;
import mindustry.gen.Player;
import mindustry.gen.StateSnapshotCallPacket;
import mindustry.mod.Mods;
import mindustry.net.Net;
import mindustry.net.NetworkIO;
import mindustry.net.Packets.StreamChunk;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

public final class UpstreamLiveNetworkProbe {
    private static final int ACCEPTED = 0, REJECTED = 1, TIMEOUT = -2;
    private static final int BUILD = 160;
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
        Vars.player = Player.create();
        Vars.net.setClientConnected();
        new UpstreamLiveNetworkProbe().run(host, port, app);
    }

    private void run(byte[] host, int port, UpstreamWorldLoadProbe.ProbeApplication app)
            throws IOException, InterruptedException {
        try {
            int result = Native.open(Address.ofData(host), host.length, port, BUILD - 1);
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
            if (join == 1) Thread.sleep(500);
        }
        System.out.println("UPSTREAM_LIVE_NET_PASS");
    }

    private void connect(byte[] host, int port, UpstreamWorldLoadProbe.ProbeApplication app, int join)
            throws IOException {
        int result = Native.open(Address.ofData(host), host.length, port, BUILD);
        if (result != ACCEPTED || Native.streamType() != Net.packetIdWorldStream || Native.streamTotal() <= 0) {
            throw new IOException("Expected a live multiplayer WorldStream, status=" + result);
        }
        int total = Native.streamTotal();
        var stream = new WorldStreamInput(Native.streamId(), total);
        var seenEntities = new IntSet();
        NetworkIO.loadWorld(new InflaterInputStream(stream), seenEntities::add);
        app.runPosted();
        if (stream.remaining != 0 || Groups.player.size() != 1 || !PLAYER_NAME.equals(Vars.player.name)
                || Vars.world.width() <= 0 || Vars.world.height() <= 0) {
            throw new IOException("Server world or assigned player did not load");
        }
        String fingerprint = UpstreamWorldLoadProbe.reportTerrain(false);
        System.out.println("UPSTREAM_LIVE_NET_WORLD join=" + join + " map=" + Vars.state.map.name()
            + " bytes=" + total + " team=" + Vars.player.team().id + " fingerprint=" + fingerprint);
        confirm();
        StateSnapshotCallPacket snapshot = waitForSnapshot();
        System.out.println("UPSTREAM_LIVE_NET_STATE join=" + join + " wave=" + snapshot.wave
            + " time=" + snapshot.timeData + " enemies=" + snapshot.enemies
            + " coreBytes=" + snapshot.coreData.length);
    }

    private static void confirm() throws IOException {
        var confirm = new ConnectConfirmCallPacket();
        var buffer = new ByteArrayOutputStream();
        confirm.write(new Writes(new DataOutputStream(buffer)));
        byte[] payload = buffer.toByteArray();
        if (Native.send(Address.ofData(payload), payload.length, Net.getPacketId(confirm) & 0xff) == 0) {
            throw new IOException("Unable to send the upstream connection confirmation");
        }
    }

    private StateSnapshotCallPacket waitForSnapshot() throws IOException {
        long end = System.nanoTime() + 10_000_000_000L;
        int snapshotId = unsignedId(StateSnapshotCallPacket.class);
        while (System.nanoTime() < end) {
            int received = receive(0, 100);
            if (received == TIMEOUT) received = receive(1, 50);
            if (received >= 0 && received >>> 16 == snapshotId) {
                var snapshot = new StateSnapshotCallPacket();
                snapshot.read(reader(received), received & 0xffff);
                snapshot.handled();
                return snapshot;
            }
        }
        throw new IOException("No state snapshot after connection confirmation");
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
            int received = receive(1, 5000);
            if (received < 0 || received >>> 16 != unsignedId(StreamChunk.class)) {
                throw new IOException("Expected upstream world chunk");
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
        static native int open(Address host, int length, int port, int version);
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
        static native int send(Address packet, int length, int packetId);
        @Import(name = "m3ds_live_net_close")
        static native void close();
    }
}
