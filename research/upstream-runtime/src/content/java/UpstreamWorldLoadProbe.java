import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.struct.Seq;
import java.io.IOException;
import java.util.ArrayDeque;
import mindustry.Vars;
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
        Vars.world.loadMap(map);
        app.runPosted();
        if (Vars.state.map != map || Vars.world.width() != 150 || Vars.world.height() != 250) {
            throw new IllegalStateException("Glacier did not load");
        }
        int tiles = 0;
        int buildings = 0;
        for (var tile : Vars.world.tiles) {
            if (tile == null) {
                throw new IllegalStateException("Missing tile");
            }
            tiles++;
            if (tile.build != null && tile.build.tile == tile) {
                if (tile.x != 81 || (tile.y != 26 && tile.y != 224)
                        || !tile.block().name.equals("core-nucleus") || !(tile.build instanceof CoreBuild)) {
                    throw new IllegalStateException("Unexpected Glacier building origin or type");
                }
                buildings++;
                System.out.println("UPSTREAM_WORLD_BUILDING " + tile.x + " " + tile.y + " "
                    + tile.block().name + " " + tile.build.getClass().getName());
            }
        }
        if (tiles != 37_500 || buildings != 2) {
            throw new IllegalStateException("Glacier terrain or buildings differ");
        }
        System.out.println("UPSTREAM_WORLD width=150 height=250 tiles=" + tiles + " buildings=" + buildings);
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
