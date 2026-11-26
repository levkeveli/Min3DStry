import arc.util.io.ByteBufferInput;
import arc.util.io.ByteBufferOutput;
import arc.util.io.Reads;
import arc.util.io.Writes;
import java.nio.ByteBuffer;
import mindustry.net.Net;
import mindustry.net.Packet;
import mindustry.net.Packets.StreamChunk;
import net.jpountz.lz4.UpstreamPortableLz4;

/** ArcNetProvider.PacketSerializer's pinned v160.8 envelope for original packets on ARM11. */
final class UpstreamHostPacketCodec {
    private static final int maxBody = 32768, header = 4;
    private final ByteBuffer body = ByteBuffer.allocate(maxBody);
    private final Reads reads = new Reads(new ByteBufferInput(body));
    private final Writes writes = new Writes(new ByteBufferOutput(body));
    final byte[] output = new byte[header + UpstreamPortableLz4.compressor.maxCompressedLength(maxBody)];

    Packet decode(byte[] input, int size) {
        if (size < header || size > input.length) throw new IllegalArgumentException("Incomplete packet header");
        Packet packet = Net.newPacket(input[0]);
        if (!packet.allow(true)) throw new IllegalArgumentException("Packet is invalid for a server endpoint");
        int length = (input[1] & 255) << 8 | input[2] & 255;
        if (length > maxBody) throw new IllegalArgumentException("Packet exceeds the original read buffer");

        int encoding = input[3] & 255;
        if (encoding == 0 && size == length + header) {
            System.arraycopy(input, header, body.array(), 0, length);
        } else if (encoding == 1) {
            int decoded = UpstreamPortableLz4.decompressor.decompress(
                input, header, size - header, body.array(), 0, length);
            if (decoded != length) throw new IllegalArgumentException("Compressed packet length did not match its header");
        } else {
            throw new IllegalArgumentException("Invalid original packet envelope");
        }
        body.position(0).limit(length);
        packet.read(reads, length);
        return packet;
    }

    int encode(Packet packet) {
        body.clear();
        packet.write(writes);
        int length = body.position();
        output[0] = Net.getPacketId(packet);
        output[1] = (byte)(length >>> 8);
        output[2] = (byte)length;
        if (length < 36 || packet instanceof StreamChunk) {
            output[3] = 0;
            System.arraycopy(body.array(), 0, output, header, length);
            return header + length;
        }
        output[3] = 1;
        int compressed = UpstreamPortableLz4.compressor.compress(
            body.array(), 0, length, output, header, output.length - header);
        return header + compressed;
    }
}
