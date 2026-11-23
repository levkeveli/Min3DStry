import arc.files.Fi;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.struct.ShortSeq;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.InflaterInputStream;
import mindustry.mod.DataAssetCache;

/** Disk-backed cache for the original v160.8 pre-world asset exchange. */
final class UpstreamServerAssets {
    final DataAssetCache cache = new DataAssetCache() {
        @Override
        public synchronized byte[] add(byte[] data) {
            // Stock save readers also cache embedded assets. Reuse the ARM11 digest for that path.
            var digest = new UpstreamAssetSha256();
            digest.update(data, data.length);
            byte[] hash = digest.finish();
            String key = DataAssetCache.encodeHash(hash);
            try {
                var existing = directory.child(key);
                if (validFile(existing, key)) {
                    addOverride(key, existing);
                } else {
                    directory.mkdirs();
                    writeAsset(new DataInputStream(new ByteArrayInputStream(data)), new byte[8192], data.length, key);
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            return hash;
        }
    };
    private final Fi directory;
    private final Seq<String> missing = new Seq<>();

    UpstreamServerAssets(Fi directory) {
        this.directory = directory;
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
                var existing = directory.child(key);
                if (cache.has(key) || validFile(existing, key)) {
                    cache.addOverride(key, existing);
                } else {
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
            directory.mkdirs();
            for (int i = 0; i < count; i++) {
                int length = input.readInt();
                remaining -= Integer.BYTES;
                if (length < 0 || length > remaining) throw new IOException("Invalid server asset length");
                writeAsset(input, buffer, length, missing.get(i));
                remaining -= length;
            }
            if (remaining != 0 || input.read() != -1) throw new IOException("Extra data in server asset stream");
        }
        missing.clear();
    }

    private void writeAsset(DataInputStream input, byte[] buffer, int length, String key) throws IOException {
        var hash = new UpstreamAssetSha256();
        var temporary = new Fi(File.createTempFile("incoming-", ".tmp", directory.file()));
        try {
            try (var output = temporary.write(false)) {
                for (int remaining = length; remaining > 0;) {
                    int count = Math.min(remaining, buffer.length);
                    input.readFully(buffer, 0, count);
                    hash.update(buffer, count);
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            if (!key.equals(DataAssetCache.encodeHash(hash.finish()))) throw new IOException("Server asset hash mismatch");
            var target = directory.child(key);
            if (!temporary.file().renameTo(target.file())) {
                if (!validFile(target, key)) throw new IOException("Unable to publish the verified asset to disk");
            }
            cache.addOverride(key, target);
        } finally {
            temporary.delete();
        }
    }

    private static boolean validFile(Fi file, String key) throws IOException {
        if (!file.exists() || file.isDirectory()) return false;
        var hash = new UpstreamAssetSha256();
        try (var input = file.read()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) hash.update(buffer, count);
        }
        if (key.equals(DataAssetCache.encodeHash(hash.finish()))) return true;
        file.delete();
        return false;
    }
}
