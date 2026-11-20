import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.math.Mathf;
import arc.struct.Seq;
import java.io.IOException;
import java.util.ArrayDeque;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.core.World;
import mindustry.gen.Groups;
import mindustry.io.MapIO;
import mindustry.maps.Maps;
import mindustry.net.Net;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public final class UpstreamWorldLoadProbe {
    public static void main(String[] args) throws IOException {
        var app = new ProbeApplication();
        Core.app = app;
        Core.settings = new Settings();
        Vars.headless = true;
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Groups.init();
        Vars.world = new World();
        Vars.net = new Net(null);
        Vars.logic = new Logic();

        Fi file = new Fi(args.length == 0 ? "/3ds/min3dstry/glacier.msav" : args[0]);
        Vars.customMapDirectory = file.parent();
        Vars.maps = new Maps();
        var map = MapIO.createMap(file, false);
        // Glacier uses randomized upstream map filters. A fixed seed allows a
        // direct comparison of the resulting terrain on each platform.
        Mathf.rand.setSeed(160_008L);
        Vars.world.loadMap(map);
        app.runPosted();
        if (Vars.state.map != map || Vars.world.width() != 150 || Vars.world.height() != 250) {
            throw new IllegalStateException("Glacier did not load");
        }
        var stats = Vars.state.stats;
        var rules = Vars.state.rules;
        if (stats.buildingsBuilt != 9 || stats.buildingsDeconstructed != 9
                || rules.enemyCoreBuildRadius != 832 || rules.spawns.isEmpty()
                || rules.spawns.first().type != UnitTypes.dagger || rules.spawns.first().end != 10) {
            throw new IllegalStateException("Glacier save metadata differs");
        }
        int tiles = 0;
        int buildings = 0;
        long fingerprint = 0xcbf29ce484222325L;
        for (var tile : Vars.world.tiles) {
            if (tile == null) {
                throw new IllegalStateException("Missing tile");
            }
            tiles++;
            fingerprint = mix(fingerprint, tile.floor().id);
            fingerprint = mix(fingerprint, tile.overlay().id);
            fingerprint = mix(fingerprint, tile.block().id);
            fingerprint = mix(fingerprint, tile.build == null ? -1 : tile.build.pos());
            if (tile.build != null && tile.build.tile == tile) {
                if (tile.x != 81 || (tile.y != 26 && tile.y != 224)
                        || !tile.block().name.equals("core-nucleus") || !(tile.build instanceof CoreBuild)) {
                    throw new IllegalStateException("Unexpected Glacier building origin or type");
                }
                buildings++;
                fingerprint = mix(fingerprint, tile.build.team.id);
                fingerprint = mix(fingerprint, tile.build.rotation);
                System.out.println("UPSTREAM_WORLD_BUILDING " + tile.x + " " + tile.y + " "
                    + tile.block().name + " " + tile.build.getClass().getName());
            }
        }
        if (tiles != 37_500 || buildings != 2) {
            throw new IllegalStateException("Glacier terrain or buildings differ");
        }
        String observed = Long.toUnsignedString(fingerprint, 16);
        if (fingerprint != 0x2e7b9f883994de6cL) {
            throw new IllegalStateException("Glacier content fingerprint differs: " + observed);
        }
        System.out.println("UPSTREAM_WORLD width=150 height=250 tiles=" + tiles + " buildings=" + buildings);
        System.out.println("UPSTREAM_WORLD_FINGERPRINT " + observed);
    }

    private static long mix(long hash, int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * 0x100000001b3L;
    }

    private static final class ProbeApplication implements Application {
        private final Seq<ApplicationListener> listeners = new Seq<>();
        private final ArrayDeque<Runnable> posted = new ArrayDeque<>();

        @Override public Seq<ApplicationListener> getListeners() { return listeners; }
        @Override public ApplicationType getType() { return ApplicationType.headless; }
        @Override public String getClipboardText() { return ""; }
        @Override public void setClipboardText(String text) {}
        @Override public void post(Runnable job) { posted.add(job); }
        @Override public void exit() {}

        void runPosted() {
            while (!posted.isEmpty()) {
                posted.remove().run();
            }
        }
    }
}
