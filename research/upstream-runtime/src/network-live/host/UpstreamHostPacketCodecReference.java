import arc.struct.Seq;
import java.nio.ByteBuffer;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.net.ArcNetProvider.PacketSerializer;
import mindustry.net.Net;
import mindustry.net.Packets.ConnectPacket;
import mindustry.net.Packets.StreamChunk;

/** Exchanges real v160.8 packets and envelopes with the desktop serializer. */
public final class UpstreamHostPacketCodecReference {
    public static void main(String[] args) {
        Version.build = 160;
        Vars.net = new Net(null);
        var stock = new PacketSerializer();
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
        stock.write(buffer, connect);
        requireConnect((ConnectPacket)host.decode(buffer.array(), buffer.position()), connect);
        if (buffer.get(3) != 1) throw new AssertionError("Expected stock LZ4 compression");

        int size = host.encode(connect);
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
        System.out.println("UPSTREAM_HOST_PACKET_CODEC stock-connect=both-directions stream-chunk=byte-exact");
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
