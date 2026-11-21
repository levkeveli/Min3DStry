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
import mindustry.game.MapObjectives.PointMarker;
import mindustry.gen.Groups;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.maps.Maps;
import mindustry.net.Net;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public final class UpstreamWorldLoadProbe {
    public static void main(String[] args) throws IOException {
        load(args);
    }

    static ProbeApplication load(String[] args) throws IOException {
        return load(args, () -> {});
    }

    static ProbeApplication load(String[] args, Runnable beforeMap) throws IOException {
        Fi file = new Fi(args.length == 0 ? "/3ds/min3dstry/glacier.msav" : args[0]);
        var app = initialize(file.parent());
        beforeMap.run();
        var map = MapIO.createMap(file, false);
        // Glacier uses randomized upstream map filters. A fixed seed allows a
        // direct comparison of the resulting terrain on each platform.
        Mathf.rand.setSeed(160_008L);
        Vars.world.loadMap(map);
        app.runPosted();
        if (Vars.state.map != map) {
            throw new IllegalStateException("Glacier did not load");
        }
        verifyWorld();
        if (args.length > 1) {
            loadCurrentSave(app, new Fi(args[1]));
        }
        return app;
    }

    static ProbeApplication initialize(Fi mapDirectory) {
        var app = new ProbeApplication();
        Core.app = app;
        Core.settings = new Settings();
        Vars.headless = true;
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Vars.emptyTile = new Tile(Short.MAX_VALUE - 20, Short.MAX_VALUE - 20);
        Groups.init();
        Vars.world = new World();
        Vars.net = new Net(null);
        Vars.logic = new Logic();

        Vars.customMapDirectory = mapDirectory;
        Vars.maps = new Maps();
        return app;
    }

    static void loadCurrentSave(ProbeApplication app, Fi file) throws IOException {
        try (var input = SaveIO.getStream(file)) {
            SaveIO.readHeader(input);
            if (input.readInt() != 13) {
                throw new IllegalStateException("Expected a v13 save");
            }
        }
        SaveIO.load(file);
        app.runPosted();
        verifyWorld();
        if (!(Vars.state.markers.get(37) instanceof PointMarker marker)
                || marker.pos.x != 80 || marker.pos.y != 152) {
            throw new IllegalStateException("Current-format marker differs after reload");
        }
        System.out.println("UPSTREAM_SAVE version=13 marker=ok");
    }

    private static void verifyWorld() {
        if (Vars.world.width() != 150 || Vars.world.height() != 250) {
            throw new IllegalStateException("Glacier dimensions differ");
        }
        var stats = Vars.state.stats;
        var rules = Vars.state.rules;
        if (stats.buildingsBuilt != 9 || stats.buildingsDeconstructed != 9
                || rules.enemyCoreBuildRadius != 832 || rules.spawns.isEmpty()
                || rules.spawns.first().type != UnitTypes.dagger || rules.spawns.first().end != 10) {
            throw new IllegalStateException("Glacier save metadata differs");
        }
        String fingerprint = reportTerrain(true);
        if (!fingerprint.equals("2e7b9f883994de6c")) {
            System.out.println("UPSTREAM_WORLD_MISMATCH " + fingerprint);
            throw new IllegalStateException("Glacier content fingerprint differs: " + fingerprint);
        }
        System.out.println("UPSTREAM_WORLD_FINGERPRINT " + fingerprint);
    }

    static String reportTerrain(boolean verifyFixture) {
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
                if (verifyFixture && (tile.x != 81 || (tile.y != 26 && tile.y != 224)
                        || !tile.block().name.equals("core-nucleus") || !(tile.build instanceof CoreBuild))) {
                    throw new IllegalStateException("Unexpected Glacier building origin or type");
                }
                buildings++;
                fingerprint = mix(fingerprint, tile.build.team.id);
                fingerprint = mix(fingerprint, tile.build.rotation);
                System.out.println("UPSTREAM_WORLD_BUILDING " + tile.x + " " + tile.y + " "
                    + tile.block().name + " " + tile.build.getClass().getName());
            }
        }
        if (verifyFixture && (tiles != 37_500 || buildings != 2)) {
            throw new IllegalStateException("Glacier terrain or buildings differ");
        }
        var observed = new StringBuilder(16);
        for (int shift = 60; shift >= 0; shift -= 4) {
            observed.append(Character.forDigit((int)(fingerprint >>> shift) & 15, 16));
        }
        System.out.println("UPSTREAM_WORLD width=" + Vars.world.width() + " height=" + Vars.world.height()
            + " tiles=" + tiles + " buildings=" + buildings);
        return observed.toString();
    }

    private static long mix(long hash, int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * 0x100000001b3L;
    }

    static final class ProbeApplication implements Application {
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
