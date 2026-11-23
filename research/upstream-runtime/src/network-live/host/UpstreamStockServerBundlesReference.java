import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.core.NetClient;
import mindustry.game.Universe;
import mindustry.gen.Player;
import mindustry.gen.WorldDataBeginCallPacket;
import mindustry.mod.DataAssetCache;
import mindustry.mod.DataBundleLoader;
import mindustry.mod.Mods;

public final class UpstreamStockServerBundlesReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected two captured worlds, the cache and a bundle stem");
        var first = new Fi(args[0]);
        var second = new Fi(args[1]);
        Core.bundle = I18NBundle.createBundle(new Fi(args[3]), Locale.ENGLISH);
        String originalWave = Core.bundle.get("stats.wave");
        var app = UpstreamWorldLoadProbe.initialize(first.parent());
        Vars.mods = new Mods();
        Vars.universe = new Universe();
        Vars.assetCacheDirectory = new Fi(args[2]);
        Vars.assetCache = new DataAssetCache();
        Vars.assetCache.load();
        Vars.netClient = NetClient.forSnapshotDispatch();
        Vars.net.setClientConnected();
        Vars.player = Player.create();

        var bundles = new DataBundleLoader();
        try {
            try (var stream = first.read()) {
                UpstreamLiveNetworkProbe.loadSessionWorld(app, stream, bundles);
            }
            if (!"Glacier".equals(Vars.state.map.name()) || !"Glacier wave".equals(Core.bundle.get("stats.wave"))
                    || !"Before rotation".equals(Core.bundle.get("fixture.base"))) {
                throw new AssertionError("The original bundle overlay did not apply to Glacier");
            }
            new WorldDataBeginCallPacket().handleClient();
            try (var stream = second.read()) {
                UpstreamLiveNetworkProbe.loadSessionWorld(app, stream, bundles);
            }
            if (!"Ancient Caldera".equals(Vars.state.map.name()) || !"Caldera wave".equals(Core.bundle.get("stats.wave"))
                    || !"After rotation".equals(Core.bundle.get("fixture.rotation"))) {
                throw new AssertionError("The original bundle overlay did not apply to Ancient Caldera");
            }
        } finally {
            bundles.unload();
        }
        if (!originalWave.equals(Core.bundle.get("stats.wave"))
                || Core.bundle.has("fixture.base") || Core.bundle.has("fixture.rotation")) {
            throw new AssertionError("Original locale was not restored after leaving the server");
        }
        System.out.println("UPSTREAM_STOCK_SERVER_BUNDLES Glacier=applied Caldera=replaced stock=restored");
    }
}
