import arc.files.Fi;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import mindustry.mod.DataAssetCache;

/** Original Mindustry cache keys backed by the handheld settings directory. */
final class UpstreamAssetCache extends DataAssetCache {
    private final Fi directory;

    UpstreamAssetCache(Fi directory) {
        this.directory = directory;
    }

    @Override
    public synchronized Fi get(String key) {
        var existing = super.get(key);
        if (existing != null || !validKey(key)) return existing;
        var file = directory.child(key);
        try {
            if (hasExpectedBytes(file, key)) {
                addOverride(key, file);
                return file;
            }
            return null;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public synchronized byte[] add(byte[] data) {
        var digest = new UpstreamAssetSha256();
        digest.update(data, data.length);
        byte[] hash = digest.finish();
        String key = encodeHash(hash);
        try {
            if (get(key) == null) {
                receive(new DataInputStream(new ByteArrayInputStream(data)), new byte[8192], data.length, key);
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return hash;
    }

    synchronized void receive(DataInputStream input, byte[] buffer, int length, String key) throws IOException {
        if (!validKey(key)) throw new IOException("Invalid stock asset key");
        directory.mkdirs();
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
            if (!key.equals(encodeHash(hash.finish()))) throw new IOException("Stock asset hash mismatch");
            var target = directory.child(key);
            if (!temporary.file().renameTo(target.file()) && !hasExpectedBytes(target, key)) {
                // Some FAT and Windows implementations require a missing destination for rename.
                if (!target.exists() || target.isDirectory() || !target.delete()
                        || !temporary.file().renameTo(target.file())) {
                    throw new IOException("Unable to publish the verified asset to disk");
                }
            }
            addOverride(key, target);
        } finally {
            temporary.delete();
        }
    }

    private static boolean hasExpectedBytes(Fi file, String key) throws IOException {
        if (!file.exists() || file.isDirectory()) return false;
        var hash = new UpstreamAssetSha256();
        try (var input = file.read()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) hash.update(buffer, count);
        }
        return key.equals(encodeHash(hash.finish()));
    }

    private static boolean validKey(String key) {
        if (key == null || key.length() != 52) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if ((c < 'A' || c > 'Z') && (c < '2' || c > '7')) return false;
        }
        return true;
    }
}
