import arc.files.Fi;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.struct.ShortSeq;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;
import mindustry.mod.DataAssetCache;

/** Original v160.8 pre-world asset exchange. */
final class UpstreamServerAssets {
    final UpstreamAssetCache cache;
    private final Seq<String> missing = new Seq<>();

    UpstreamServerAssets(Fi directory) {
        this.cache = new UpstreamAssetCache(directory);
    }

    short[] readRequirements(InputStream stream) throws IOException {
        missing.clear();
        var indices = new ShortSeq();
        var seen = new ObjectSet<String>();
        try (var input = new DataInputStream(new InflaterInputStream(stream))) {
            int count = input.readInt();
            if (count < 0 || count > Short.MAX_VALUE + 1) throw new IOException("Invalid server asset requirement count");
            byte[] bytes = new byte[32];
            for (int i = 0; i < count; i++) {
                input.readFully(bytes);
                String key = DataAssetCache.encodeHash(bytes);
                if (!seen.add(key)) continue;
                if (!cache.has(key)) {
                    missing.add(key);
                    indices.add((short) i);
                }
            }
            if (input.read() != -1) throw new IOException("Extra data in server asset requirements");
        }
        // The existing 32 KiB Arc bridge carries a two-byte array length and two bytes per index.
        if (indices.size > (32768 - 2) / 2) throw new IOException("Server asset request exceeds the Arc packet limit");
        return indices.toArray();
    }

    void receiveAssets(InputStream stream, int streamBytes) throws IOException {
        byte[] buffer = new byte[8192];
        try (var input = new DataInputStream(stream)) {
            int count = input.readInt();
            if (count != missing.size) throw new IOException("Server asset count differs from the request");
            int remaining = streamBytes - Integer.BYTES;
            for (int i = 0; i < count; i++) {
                int length = input.readInt();
                remaining -= Integer.BYTES;
                if (length < 0 || length > remaining) throw new IOException("Invalid server asset length");
                cache.receive(input, buffer, length, missing.get(i));
                remaining -= length;
            }
            if (remaining != 0 || input.read() != -1) throw new IOException("Extra data in server asset stream");
        }
        missing.clear();
    }

}
