import arc.ApplicationCore;
import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.core.Logic;
import mindustry.core.NetServer;
import mindustry.core.World;
import mindustry.io.MapIO;
import mindustry.net.Net;

class UpstreamWorldBaseline {
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one path to a Mindustry map");
        }
        new HeadlessApplication(new ApplicationCore() {
            @Override public void setup() {
                Core.settings.setDataDirectory(new Fi("world-baseline-config"));
                Vars.headless = true;
                Vars.net = new Net(null);
                Vars.init();
                Vars.world = new World();
                Vars.content.createBaseContent();
                add(Vars.logic = new Logic());
                add(Vars.netServer = new NetServer());
                Vars.content.init();
            }

            @Override public void init() {
                super.init();
                try {
                    var map = MapIO.createMap(new Fi(args[0]), false);
                    Vars.world.loadMap(map);
                    int tiles = 0;
                    int buildings = 0;
                    for (var tile : Vars.world.tiles) {
                        if (tile.build != null && tile.build.tile == tile) buildings++;
                        tiles++;
                    }
                    System.out.printf("UPSTREAM_WORLD width=%d height=%d tiles=%d buildings=%d%n",
                        Vars.world.width(), Vars.world.height(), tiles, buildings);
                    Core.app.exit();
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }
        }, error -> { error.printStackTrace(); System.exit(1); });
    }
}
