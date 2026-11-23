import arc.files.Fi;
import java.security.MessageDigest;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.game.Gamemode;
import mindustry.io.MapIO;
import mindustry.mod.DataAssetCache;

public final class UpstreamStockEmbeddedAssetReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected the stock fixture directory");
        var fixture = new Fi(args[0]);
        var profile = fixture.child("profile");
        if (profile.exists()) throw new IllegalArgumentException("Expected a fresh fixture profile");
        var expected = fixture.child("expected.png").readBytes();
        String key = DataAssetCache.encodeHash(MessageDigest.getInstance("SHA-256").digest(expected));
        var session = UpstreamSinglePlayerSession.create(profile, "en");
        try {
            var map = MapIO.createMap(fixture.child("embedded.msav"), false);
            session.startCustomGame(map, Gamemode.sandbox);
            verifyAsset(key, expected);
            var save = fixture.child("roundtrip.msav");
            session.save(save);
            Vars.assetCache = new UpstreamAssetCache(profile.child("assetCache"));
            if (!session.start(null, save, "stock-embedded-asset")) {
                throw new AssertionError("Cannot reopen the stock-format offline save");
            }
            verifyAsset(key, expected);
        } finally {
            session.shutdown();
        }
        System.out.println("UPSTREAM_EMBEDDED_MAP_ASSET bytes=" + expected.length + " stock=embedded disk=verified reload=ok");
    }

    private static void verifyAsset(String key, byte[] expected) {
        var images = Vars.state.data.getImages();
        if (!"Glacier".equals(Vars.state.map.name()) || images.size != 1
                || !images.first().path.equals("fixture.png") || !key.equals(images.first().stringHash)
                || !images.first().isCached() || !Arrays.equals(images.first().getCacheFileNoNull().readBytes(), expected)) {
            throw new AssertionError("Stock image path, key or bytes changed");
        }
    }
}
