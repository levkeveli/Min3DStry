import arc.Core;
import java.io.IOException;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.core.Version;
import mindustry.net.Net;

/** Starts the pinned v160.8 NetServer around the unified game's loaded local world. */
final class UpstreamHostedSession implements UpstreamSinglePlayerApp.SessionHooks {
    private final boolean onStartup;
    private UpstreamHostedNetProvider provider;
    private Net offlineNet;
    private NetServer offlineServer;

    UpstreamHostedSession(boolean onStartup) {
        this.onStartup = onStartup;
    }

    @Override
    public void open() throws IOException {
        if (onStartup) startHosting();
    }

    @Override public boolean canHost() { return true; }
    @Override public boolean hosting() { return provider != null; }

    @Override
    public void toggleHosting() throws IOException {
        if (hosting()) close();
        else startHosting();
    }

    private void startHosting() throws IOException {
        Version.number = 8;
        Version.build = 160;
        Version.revision = 8;
        Version.type = "official";
        Version.modifier = "release";
        offlineNet = Vars.net;
        offlineServer = Vars.netServer;
        int port = Core.settings.getInt("port", Vars.port);
        var next = new UpstreamHostedNetProvider(32);
        try {
            Vars.net = new Net(next);
            Vars.netServer = new NetServer();
            Vars.net.host(port);
            provider = next;
        } catch (IOException | RuntimeException failure) {
            next.closeServer();
            Vars.net = offlineNet;
            Vars.netServer = offlineServer;
            throw failure;
        }
        System.out.println("UPSTREAM_HOST_READY version=" + Version.buildString() + " port=" + port
            + " map=" + Vars.state.map.name() + " tiles=" + Vars.world.width() + "x" + Vars.world.height());
    }

    @Override
    public void tick() {
        if (!hosting()) return;
        String phase = "transport";
        try {
            provider.update();
            phase = "stock-server";
            Vars.netServer.update();
        } catch (RuntimeException failure) {
            System.out.println("UPSTREAM_HOST_TICK_ERROR phase=" + phase);
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!hosting()) return;
        try {
            Vars.net.closeServer();
        } finally {
            provider.closeServer();
            provider = null;
            Vars.net = offlineNet;
            Vars.netServer = offlineServer;
        }
    }
}
