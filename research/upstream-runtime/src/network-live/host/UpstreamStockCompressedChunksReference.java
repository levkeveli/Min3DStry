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
    }

    private static DataInputStream inflate(byte[] chunk, int declaredSize) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var compressed = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            compressed.writeInt(declaredSize);
            compressed.write(chunk);
            compressed.writeShort(declaredSize);
            compressed.write(chunk);
            compressed.writeInt(marker);
        }
        return new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
