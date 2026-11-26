import arc.Core;
import java.io.IOException;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.core.Version;
import mindustry.net.Net;

/** Starts the pinned v160.8 NetServer around the unified game's loaded local world. */
final class UpstreamHostedSession implements UpstreamSinglePlayerApp.SessionHooks {
    private final UpstreamHostedNetProvider provider = new UpstreamHostedNetProvider(32);
    private boolean active;

    @Override
    public void open() throws IOException {
        Version.number = 8;
        Version.build = 160;
        Version.revision = 8;
        Version.type = "official";
        Version.modifier = "release";
        Vars.net = new Net(provider);
        Vars.netServer = new NetServer();
        int port = Core.settings.getInt("port", Vars.port);
        Vars.net.host(port);
        active = true;
        System.out.println("UPSTREAM_HOST_READY version=" + Version.buildString() + " port=" + port
            + " map=" + Vars.state.map.name() + " tiles=" + Vars.world.width() + "x" + Vars.world.height());
    }

    @Override
    public void tick() {
        provider.update();
        Vars.netServer.update();
    }

    @Override
    public void close() {
        if (active) {
            Vars.net.closeServer();
            active = false;
        }
    }
}
