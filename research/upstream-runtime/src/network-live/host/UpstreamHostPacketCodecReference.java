import arc.struct.Seq;
import java.nio.ByteBuffer;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.gen.EntitySnapshotCallPacket;
import mindustry.gen.StateSnapshotCallPacket;
import mindustry.net.ArcNetProvider.PacketSerializer;
import mindustry.net.Net;
import mindustry.net.Packets.ConnectPacket;
import mindustry.net.Packets.StreamChunk;

/** Exchanges real v160.8 packets and envelopes with the desktop serializer. */
public final class UpstreamHostPacketCodecReference {
    public static void main(String[] args) {
        Version.build = 160;
        Vars.net = new Net(null);
        // Force the same initial property state as TeaVM before either codec uses LZ4.
        String architecture = System.clearProperty("os.arch");
        var host = new UpstreamHostPacketCodec();
        var buffer = ByteBuffer.allocate(65536);

        var connect = new ConnectPacket();
        connect.name = "Stock PC reference";
        connect.locale = "de";
        connect.versionType = "official";
        connect.uuid = "AAECAwQFBgc=";
        connect.usid = "normal-host-reference";
        connect.mods = Seq.with("pinned-reference");
        connect.color = 0x12345678;
        // Use a compressed packet first so the bundled compressor initializes its utility class.
        int size = host.encode(connect);
        if (architecture != null) System.setProperty("os.arch", architecture);
        var stock = new PacketSerializer();
        stock.write(buffer, connect);
        requireConnect((ConnectPacket)host.decode(buffer.array(), buffer.position()), connect);
        if (buffer.get(3) != 1) throw new AssertionError("Expected stock LZ4 compression");

        if (host.output[3] != 1) throw new AssertionError("Expected handheld LZ4 compression");
        requireConnect((ConnectPacket)stock.read(ByteBuffer.wrap(host.output, 0, size)), connect);

        var chunk = new StreamChunk();
        chunk.id = 4481;
        chunk.data = new byte[1024];
        for (int i = 0; i < chunk.data.length; i++) chunk.data[i] = (byte)i;
        size = host.encode(chunk);
        buffer.clear();
        stock.write(buffer, chunk);
        if (size != buffer.position() || host.output[3] != 0
                || !Arrays.equals(host.output, 0, size, buffer.array(), 0, size)) {
            throw new AssertionError("World stream chunk framing differs from stock");
        }
        var read = (StreamChunk)stock.read(ByteBuffer.wrap(host.output, 0, size));
        if (read.id != chunk.id || !Arrays.equals(chunk.data, read.data)) {
            throw new AssertionError("Stock reader lost stream chunk bytes");
        }
        requireSnapshots(stock, host);
        System.out.println("UPSTREAM_HOST_PACKET_CODEC stock-connect=both-directions stream-chunk=byte-exact "
            + "state-snapshot=ok entity-snapshot=ok");
    }

    private static void requireSnapshots(PacketSerializer stock, UpstreamHostPacketCodec host) {
        var state = new StateSnapshotCallPacket();
        state.waveTime = 456.25f;
        state.wave = 9;
        state.enemies = 12;
        state.paused = true;
        state.timeData = 0x01020304;
        state.tps = 60;
        state.rand0 = 0x0102030405060708L;
        state.rand1 = 0xf1e2d3c4b5a69788L;
        state.coreData = new byte[]{2, -1, 0, 4, 3, 12, 0, 1};

        int size = host.encode(state);
        if (host.output[3] != 1) throw new AssertionError("Expected a stock compressed state envelope");
        var actual = (StateSnapshotCallPacket)stock.read(ByteBuffer.wrap(host.output, 0, size));
        actual.handled();
        if (actual.waveTime != state.waveTime || actual.wave != state.wave || actual.enemies != state.enemies
                || actual.paused != state.paused || actual.gameOver != state.gameOver
                || actual.timeData != state.timeData || actual.tps != state.tps
                || actual.rand0 != state.rand0 || actual.rand1 != state.rand1
                || !Arrays.equals(actual.coreData, state.coreData)) {
            throw new AssertionError("Stock StateSnapshot fields differ");
        }

        var entities = new EntitySnapshotCallPacket();
        entities.amount = 2;
        entities.data = new byte[129];
        for (int i = 0; i < entities.data.length; i++) entities.data[i] = (byte)(i % 17);
        size = host.encode(entities);
        if (host.output[3] != 1) throw new AssertionError("Expected a stock compressed entity envelope");
        var decoded = (EntitySnapshotCallPacket)stock.read(ByteBuffer.wrap(host.output, 0, size));
        decoded.handled();
        if (decoded.amount != entities.amount || !Arrays.equals(decoded.data, entities.data)) {
            throw new AssertionError("Stock EntitySnapshot fields differ");
        }
    }

    private static void requireConnect(ConnectPacket actual, ConnectPacket expected) {
        if (actual.version != Version.build || !actual.name.equals(expected.name)
                || !actual.locale.equals(expected.locale) || !actual.versionType.equals(expected.versionType)
                || !actual.usid.equals(expected.usid) || actual.color != expected.color
                || actual.mods.size != 1 || !actual.mods.first().equals(expected.mods.first())) {
            throw new AssertionError("Stock ConnectPacket fields differ");
        }
    }
}
