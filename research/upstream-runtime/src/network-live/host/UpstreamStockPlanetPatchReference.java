import arc.files.Fi;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Planets;
import mindustry.core.NetClient;
import mindustry.game.Universe;
import mindustry.gen.Player;
import mindustry.gen.WorldDataBeginCallPacket;
import mindustry.mod.DataAssetCache;
import mindustry.mod.DataBundleLoader;
import mindustry.mod.Mods;
import mindustry.world.blocks.distribution.Duct;

public final class UpstreamStockPlanetPatchReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected stock Glacier and patched Erekir WorldStreams");
        var first = new Fi(args[0]);
        var second = new Fi(args[1]);
        var app = UpstreamWorldLoadProbe.initialize(first.parent());
        Vars.mods = new Mods();
        Vars.universe = new Universe();
        Vars.assetCache = new DataAssetCache();
        Vars.netClient = NetClient.forSnapshotDispatch();
        Vars.net.setClientConnected();
        Vars.player = Player.create();

        var bundles = new DataBundleLoader();
        var duct = (Duct) Blocks.duct;
        float originalSpeed = duct.speed;
        try (var stream = first.read()) {
            UpstreamLiveNetworkProbe.loadSessionWorld(app, stream, bundles);
        }
        new WorldDataBeginCallPacket().handleClient();
        if (Vars.state.rules.planet != Planets.serpulo) throw new AssertionError("Expected stock reset rules before rotation");
        try (var stream = second.read()) {
            UpstreamLiveNetworkProbe.loadSessionWorld(app, stream, bundles);
        }
        var patches = Vars.state.data.getPatches();
        if (patches.size != 1 || patches.first().error || duct.speed != 7.5f
                || !Vars.state.map.name().equals("Caldera")) {
            throw new AssertionError("The original client must keep the Erekir-only gameplay patch sent by the server");
        }

        new WorldDataBeginCallPacket().handleClient();
        try (var stream = first.read()) {
            UpstreamLiveNetworkProbe.loadSessionWorld(app, stream, bundles);
        }
        if (!Vars.state.data.getPatches().isEmpty() || duct.speed != originalSpeed) {
            throw new AssertionError("The previous world's stock data patch was retained");
        }
        System.out.println("UPSTREAM_STOCK_PLANET_PATCH Glacier=stock Caldera=7.5 GlacierAgain=restored");
    }
}
