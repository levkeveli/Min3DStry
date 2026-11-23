import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import mindustry.io.SaveIO;
import mindustry.io.SaveVersion;

public final class UpstreamStockCompressedChunksReference {
    private static final int marker = 0x16080003;
    private static final int chunkSize = 3000;

    public static void main(String[] args) throws IOException {
        var random = new Random(1608);
        var unknownChunk = new byte[chunkSize];
        random.nextBytes(unknownChunk);

        var reader = SaveIO.getSaveWriter();
        try (var input = inflate(unknownChunk, chunkSize)) {
            reader.skipChunk(input);
            reader.skipLegacyShortChunk(input);
            if (input.readInt() != marker || input.read() != -1) {
                throw new AssertionError("Skipping compressed chunks corrupted later bytes");
            }
        }

        try (var input = inflate(new byte[0], chunkSize)) {
            reader.skipChunk(input);
            throw new AssertionError("An incomplete chunk was silently accepted");
        } catch (EOFException expected) {
            // The declared chunk length exceeds the contents of the valid zlib stream.
        }
        try (var input = inflate(new byte[0], -1)) {
            reader.skipChunk(input);
            throw new AssertionError("A negative chunk length was silently accepted");
        } catch (IOException expected) {
            if (expected instanceof EOFException) throw expected;
        }
        System.out.println("UPSTREAM_STOCK_COMPRESSED_CHUNKS current=skipped legacy=skipped marker=retained eof=rejected negative=rejected");
        verifyWrittenChunks(reader);
    }

    private static void verifyWrittenChunks(SaveVersion writer) throws IOException {
        byte[] payload = new byte[10_001];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            writer.writeChunk(output, outer -> {
                outer.output.writeByte(127);
                writer.writeChunk(outer.output, inner -> inner.output.write(payload, 1, payload.length - 1));
                outer.output.writeByte(-128);
            });
            writer.writeChunk(output, empty -> {});
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            if (input.readInt() != payload.length + 5 || input.readByte() != 127
                    || input.readInt() != payload.length - 1) {
                throw new AssertionError("Stock save-region length or prefix changed");
            }
            for (int i = 1; i < payload.length; i++) {
                if (input.readByte() != payload[i]) throw new AssertionError("Stock save-region payload changed at " + i);
            }
            if (input.readByte() != -128 || input.readInt() != 0 || input.read() != -1) {
                throw new AssertionError("Stock save-region suffix or empty chunk changed");
            }
        }
        System.out.println("UPSTREAM_SAVE_BUFFER length=ok payload=ok nested=ok empty=ok");
    }

    private static DataInputStream inflate(byte[] chunk, int declaredSize) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var compressed = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            compressed.writeInt(declaredSize);
            if (chunk.length != 0) compressed.write(chunk);
            compressed.writeShort(declaredSize);
            if (chunk.length != 0) compressed.write(chunk);
            compressed.writeInt(marker);
        }
        return new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
