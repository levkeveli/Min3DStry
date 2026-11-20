import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Host-only inventory of the pinned upstream Mindustry audio assets.
 *
 * The official server JAR does not contain sounds/music, so this scans the
 * pinned Git checkout's core/assets tree. It does not decode or convert audio;
 * it records the exact source set and a deterministic manifest hash for #29.
 */
class UpstreamAudioInventory {
    private record Asset(String kind, String relative, long bytes, String sha256) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one path to the pinned Mindustry checkout");
        }
        Path root = Paths.get(args[0]);
        List<Asset> assets = new ArrayList<>();
        collect(root, "sounds", assets);
        collect(root, "music", assets);
        assets.sort(Comparator.comparing(Asset::kind).thenComparing(Asset::relative));

        MessageDigest manifest = sha256();
        long totalBytes = 0L;
        String currentKind = null;
        long kindBytes = 0L;
        int kindFiles = 0;
        MessageDigest kindManifest = null;

        for (Asset asset : assets) {
            if (currentKind != null && !asset.kind.equals(currentKind)) {
                printSummary(currentKind, kindFiles, kindBytes, kindManifest);
                currentKind = null;
            }
            if (currentKind == null) {
                currentKind = asset.kind;
                kindBytes = 0L;
                kindFiles = 0;
                kindManifest = sha256();
            }
            System.out.printf(
                "UPSTREAM_AUDIO_FILE kind=%s path=%s bytes=%d sha256=%s%n",
                asset.kind, asset.relative, asset.bytes, asset.sha256);
            String line = asset.kind + "\t" + asset.relative + "\t" + asset.bytes + "\t" + asset.sha256 + "\n";
            byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
            manifest.update(encoded);
            kindManifest.update(encoded);
            kindBytes += asset.bytes;
            kindFiles++;
            totalBytes += asset.bytes;
        }
        if (currentKind != null) {
            printSummary(currentKind, kindFiles, kindBytes, kindManifest);
        }
        System.out.printf(
            "UPSTREAM_AUDIO total_files=%d total_bytes=%d manifest_sha256=%s%n",
            assets.size(), totalBytes, HexFormat.of().formatHex(manifest.digest()));
    }

    private static void printSummary(String kind, int files, long bytes, MessageDigest digest) {
        System.out.printf(
            "UPSTREAM_AUDIO kind=%s files=%d bytes=%d manifest_sha256=%s%n",
            kind, files, bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static void collect(Path root, String kind, List<Asset> out) throws IOException {
        Path dir = root.resolve("core/assets").resolve(kind);
        if (!Files.isDirectory(dir)) {
            throw new IOException("Missing upstream asset directory: " + dir);
        }
        try (var stream = Files.walk(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase();
                if (!(name.endsWith(".ogg") || name.endsWith(".mp3") || name.endsWith(".wav"))) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(file);
                String relative = kind + "/" + dir.relativize(file).toString().replace('\\', '/');
                out.add(new Asset(kind, relative, bytes.length, HexFormat.of().formatHex(sha256().digest(bytes))));
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
