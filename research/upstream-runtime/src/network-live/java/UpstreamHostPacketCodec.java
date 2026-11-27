import arc.util.io.ReusableByteInStream;
import arc.util.io.ReusableByteOutStream;
import arc.util.io.Reads;
import arc.util.io.Writes;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import mindustry.net.Net;
import mindustry.net.Packet;
import mindustry.net.Packets.StreamChunk;
import net.jpountz.lz4.UpstreamPortableLz4;

/** ArcNetProvider.PacketSerializer's pinned v160.8 envelope for original packets on ARM11. */
final class UpstreamHostPacketCodec {
    private static final int maxBody = 32768, header = 4;
    // TeaVM's native ByteBuffer caches a data address. This codec survives
    // loads and managed heap collections, so keep the packet scratch as Java arrays.
    private final ReusableByteOutStream body = new ReusableByteOutStream(maxBody);
    private final ReusableByteInStream inputBody = new ReusableByteInStream();
    private final Reads reads = new Reads(new DataInputStream(inputBody));
    private final Writes writes = new Writes(new DataOutputStream(body));
    private String encodePhase;
    final byte[] output = new byte[header + UpstreamPortableLz4.compressor.maxCompressedLength(maxBody)];

    Packet decode(byte[] input, int size) {
        if (size < header || size > input.length) throw new IllegalArgumentException("Incomplete packet header");
        Packet packet = Net.newPacket(input[0]);
        if (!packet.allow(true)) throw new IllegalArgumentException("Packet is invalid for a server endpoint");
        int length = (input[1] & 255) << 8 | input[2] & 255;
        if (length > maxBody) throw new IllegalArgumentException("Packet exceeds the original read buffer");

        byte[] scratch = body.getBytes();
        int encoding = input[3] & 255;
        if (encoding == 0 && size == length + header) {
            System.arraycopy(input, header, scratch, 0, length);
        } else if (encoding == 1) {
            int decoded = UpstreamPortableLz4.decompressor.decompress(
                input, header, size - header, scratch, 0, length);
            if (decoded != length) throw new IllegalArgumentException("Compressed packet length did not match its header");
        } else {
            throw new IllegalArgumentException("Invalid original packet envelope");
        }
        inputBody.setBytes(scratch, 0, length);
        packet.read(reads, length);
        return packet;
    }

    int encode(Packet packet) {
        encodePhase = "packet-body";
        body.reset();
        packet.write(writes);
        int length = body.size();
        if (length > maxBody) throw new IllegalArgumentException("Packet exceeds the original write buffer");
        byte[] scratch = body.getBytes();
        encodePhase = "packet-id";
        output[0] = Net.getPacketId(packet);
        output[1] = (byte)(length >>> 8);
        output[2] = (byte)length;
        if (length < 36 || packet instanceof StreamChunk) {
            output[3] = 0;
            System.arraycopy(scratch, 0, output, header, length);
            return header + length;
        }
        output[3] = 1;
        encodePhase = "compression";
        int compressed = UpstreamPortableLz4.compressor.compress(
            scratch, 0, length, output, header, output.length - header);
        return header + compressed;
    }
    String encodePhase() {
        return encodePhase;
    }
}
