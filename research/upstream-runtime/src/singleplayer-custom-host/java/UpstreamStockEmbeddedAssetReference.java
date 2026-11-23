import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
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
        Core.bundle = I18NBundle.createBundle(fixture.child("stock"), Locale.ENGLISH);
        String stockWave = Core.bundle.get("stats.wave");
        var session = UpstreamSinglePlayerSession.create(profile, "en");
        try {
            var map = MapIO.createMap(fixture.child("embedded.msav"), false);
            session.startCustomGame(map, Gamemode.sandbox);
            verifyAsset(key, expected);
            verifyMapLocale();
            var save = fixture.child("roundtrip.msav");
            session.save(save);
            Vars.assetCache = new UpstreamAssetCache(profile.child("assetCache"));
            if (!session.start(null, save, "stock-embedded-asset")) {
                throw new AssertionError("Cannot reopen the stock-format offline save");
            }
            verifyAsset(key, expected);
            verifyMapLocale();
            session.startCustomGame(MapIO.createMap(fixture.child("glacier.msav"), false), Gamemode.sandbox);
            verifyStockLocale(stockWave);
            session.start(null, save, "stock-embedded-map-locale");
            verifyMapLocale();
        } finally {
            session.shutdown();
        }
        verifyStockLocale(stockWave);
        System.out.println("UPSTREAM_EMBEDDED_MAP_ASSET bytes=" + expected.length + " stock=embedded disk=verified reload=ok");
        System.out.println("UPSTREAM_OFFLINE_MAP_LOCALE imported=ok resumed=ok switched=restored exit=restored");
    }

    private static void verifyAsset(String key, byte[] expected) {
        var images = Vars.state.data.getImages();
        if (!"Glacier".equals(Vars.state.map.name()) || images.size != 1
                || !images.first().path.equals("fixture.png") || !key.equals(images.first().stringHash)
                || !images.first().isCached() || !Arrays.equals(images.first().getCacheFileNoNull().readBytes(), expected)) {
            throw new AssertionError("Stock image path, key or bytes changed");
        }
    }

    private static void verifyMapLocale() {
        if (!"Embedded Glacier wave".equals(Core.bundle.get("stats.wave"))
                || !"Glacier".equals(Core.bundle.get("fixture.map"))) {
            throw new AssertionError("Offline map locale was not loaded from the original asset");
        }
    }

    private static void verifyStockLocale(String originalWave) {
        if (!originalWave.equals(Core.bundle.get("stats.wave")) || Core.bundle.has("fixture.map")) {
            throw new AssertionError("Offline map locale was not cleared");
        }
    }
}
