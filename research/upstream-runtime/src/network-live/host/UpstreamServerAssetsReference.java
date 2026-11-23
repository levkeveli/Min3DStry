import arc.files.Fi;
import arc.struct.Seq;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import mindustry.Vars;
import mindustry.mod.DataAssetCache;
import mindustry.mod.data.BundleAsset;
import mindustry.mod.data.DataAsset;
import mindustry.net.NetworkIO;

final class UpstreamServerAssetsReference {
    static void verify() throws Exception {
        var root = new Fi(Files.createTempDirectory("m3ds-asset-reference-").toFile());
        var previousCache = Vars.assetCache;
        var previousDirectory = Vars.assetCacheDirectory;
        try {
            Vars.assetCacheDirectory = root.child("server");
            Vars.assetCache = new DataAssetCache();
            Vars.assetCacheDirectory.mkdirs();
            var small = new BundleAsset();
            small.setPath("bundle.properties");
            byte[] properties = "fixture.test=original server asset\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            small.updateData(properties);
            var large = new BundleAsset();
            large.setPath("bundle_en.properties");
            byte[] bytes = new byte[24569];
            new Random(160).nextBytes(bytes);
            large.updateData(bytes);

            var requirementBytes = new ByteArrayOutputStream();
            NetworkIO.writeRequiredAssets(new DeflaterOutputStream(requirementBytes), Seq.<DataAsset>with(small, large, small));
            byte[] requirements = requirementBytes.toByteArray();
            var assetBytes = new ByteArrayOutputStream();
            NetworkIO.writeAssets(assetBytes, Seq.<DataAsset>with(small, large));
            byte[] payload = assetBytes.toByteArray();
            var directory = root.child("client");
            var client = new UpstreamServerAssets(directory);
            if (!Arrays.equals(client.readRequirements(new ByteArrayInputStream(requirements)), new short[]{0, 1})) {
                throw new AssertionError("Cold stock asset transfer requested the wrong indices");
            }
            client.receiveAssets(new ByteArrayInputStream(payload), payload.length);
            if (!Arrays.equals(client.cache.get(small.stringHash).readBytes(), properties)
                    || !Arrays.equals(client.cache.get(large.stringHash).readBytes(), bytes)) {
                throw new AssertionError("Disk-backed cache did not retain byte-identical stock assets");
            }
            client = new UpstreamServerAssets(directory);
            if (client.readRequirements(new ByteArrayInputStream(requirements)).length != 0) {
                throw new AssertionError("A new process requested already verified stock assets");
            }
            directory.child(large.stringHash).writeBytes(new byte[]{42});
            client = new UpstreamServerAssets(directory);
            if (!Arrays.equals(client.readRequirements(new ByteArrayInputStream(requirements)), new short[]{1})) {
                throw new AssertionError("A corrupt cache entry must be requested again");
            }
            assetBytes.reset();
            NetworkIO.writeAssets(assetBytes, Seq.<DataAsset>with(large));
            byte[] corrupt = assetBytes.toByteArray();
            corrupt[corrupt.length - 1] ^= 1;
            try {
                client.receiveAssets(new ByteArrayInputStream(corrupt), corrupt.length);
                throw new AssertionError("A changed asset must fail SHA-256 validation");
            } catch (IOException expected) {
                if (directory.list().length != 1 || client.cache.has(large.stringHash)) throw expected;
            }
            for (int size : new int[]{0, 1, 55, 56, 63, 64, 65}) {
                byte[] sample = Arrays.copyOf(bytes, size);
                var hash = new UpstreamAssetSha256();
                hash.update(sample, sample.length);
                if (!Arrays.equals(hash.finish(), MessageDigest.getInstance("SHA-256").digest(sample))) {
                    throw new AssertionError("Digest differs from Java at length " + size);
                }
            }
            if (!Arrays.equals(client.cache.add(bytes), large.byteHash)
                    || !Arrays.equals(client.cache.get(large.stringHash).readBytes(), bytes)) {
                throw new AssertionError("The embedded stock asset path must use the same cache key and bytes");
            }
            System.out.println("UPSTREAM_SERVER_ASSETS cold=2 cached=0 damaged=1 hash=verified");
        } finally {
            Vars.assetCache = previousCache;
            Vars.assetCacheDirectory = previousDirectory;
            root.deleteDirectory();
        }
    }
}
