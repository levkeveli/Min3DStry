import arc.Core;
import arc.Events;
import arc.Settings;
import arc.audio.Audio;
import arc.files.Fi;
import arc.util.I18NBundle;
import arc.util.Strings;
import java.io.IOException;
import java.util.Arrays;
import mindustry.game.EventType.WorldLoadEvent;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;
import port.audio.Arm11Audio;

/** Select one upstream game session before initializing its content and platform services. */
public final class UpstreamUnifiedApp {
    @Include(value = "m3ds_server_address.h", isSystem = true)
    private static final class NativeInput {
        @Import(name = "m3ds_read_server_address")
        static native int serverAddress(Address buffer, int capacity, int invalid);
    }

    private UpstreamUnifiedApp() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length == 0) {
            int input = UpstreamSinglePlayerInput.poll();
            if (input < 0) return;
            if ((input & UpstreamSinglePlayerInput.X_HELD) != 0) {
                join(args);
            } else if ((input & (UpstreamSinglePlayerInput.L_HELD | UpstreamSinglePlayerInput.CAMPAIGN_HELD)) != 0
                    || ((input & UpstreamSinglePlayerInput.Y_HELD) == 0 && directStartup())) {
                offline(args);
            } else {
                offline(new String[]{"--title"});
            }
        } else if (args[0].equals("--join")) {
            if (args.length > 2) throw new IllegalArgumentException("Expected --join [endpoint-file]");
            join(Arrays.copyOfRange(args, 1, args.length));
        } else if (args[0].equals("--direct")) {
            offline(Arrays.copyOfRange(args, 1, args.length));
        } else {
            offline(args);
        }
    }

    private static boolean directStartup() {
        var preference = new Fi("/3ds/min3dstry/startup.txt");
        return preference.exists() && !preference.isDirectory() && preference.length() <= 16
            && preference.readString("UTF-8").trim().equals("direct");
    }

    private static void join(String[] endpointArgs) throws IOException, InterruptedException {
        join(endpointArgs, null, false, null, null, null);
    }

    private static boolean join(String[] endpointArgs, UpstreamWorldLoadProbe.ProbeApplication preparedApp,
                                boolean atlasReady, I18NBundle titleBundle, Settings settings,
                                UpstreamSinglePlayerApp menus) throws IOException, InterruptedException {
        var endpoint = UpstreamLiveNetworkProbe.endpointFile(endpointArgs);
        var atlas = new Fi("/romfs/sprites.aatls");
        if (endpoint.exists() && !endpoint.isDirectory()) {
            System.out.println("UPSTREAM_BOOT mode=join");
            UpstreamRenderedNetworkProbe.run(endpoint.parent(), endpoint.readString(), atlas, preparedApp, atlasReady, titleBundle, settings);
            return true;
        } else if (endpointArgs.length != 0) {
            System.out.println("No server endpoint file at " + endpoint.path());
        } else {
            var address = menus == null ? readServerAddress() : chooseLanAddress(menus);
            if (address != null) {
                System.out.println("UPSTREAM_BOOT mode=join");
                UpstreamRenderedNetworkProbe.run(endpoint.parent(), address.host() + " " + address.port(), atlas,
                    preparedApp, atlasReady, titleBundle, settings);
                return true;
            }
        }
        return false;
    }

    private static boolean joinFromTitle(UpstreamWorldLoadProbe.ProbeApplication preparedApp, boolean atlasReady,
                                         Settings settings, UpstreamSinglePlayerApp menus)
            throws IOException {
        try {
            return join(new String[0], preparedApp, atlasReady, Core.bundle, settings, menus);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while joining", interrupted);
        }
    }

    private static UpstreamServerAddress chooseLanAddress(UpstreamSinglePlayerApp menus)
            throws IOException, InterruptedException {
        var bundle = Core.bundle;
        try (var discovery = new UpstreamLanDiscovery()) {
            boolean refresh = true;
            int selected = 0;
            while (true) {
                if (refresh) {
                    menus.showTitleStatus(bundle.get("hosts.discovering"));
                    discovery.start(System.currentTimeMillis());
                    while (discovery.poll(System.currentTimeMillis())) {
                        int input = UpstreamSinglePlayerInput.poll();
                        if (input < 0) menus.requestTitleExit();
                        if (input < 0 || (input & UpstreamSinglePlayerInput.B_PRESSED) != 0) return null;
                        Thread.sleep(16);
                    }
                    System.out.println("UPSTREAM_LAN_SCAN hosts=" + discovery.results().size());
                    refresh = false;
                    selected = 0;
                }
                var hosts = discovery.results();
                String title = Strings.stripColors(bundle.get(hosts.isEmpty() ? "hosts.none" : "servers.local"));
                selected = menus.chooseTitlePagedRow(title, hosts.size() + 2, selected, index -> switch (index) {
                    case 0 -> bundle.get("joingame.ip");
                    case 1 -> bundle.get("hosts.refresh");
                    default -> oneLine(hosts.get(index - 2).name);
                });
                if (selected == -2) menus.requestTitleExit();
                if (selected < 0) return null;
                if (selected == 0) {
                    var address = readServerAddress();
                    if (address != null) return address;
                    continue;
                }
                if (selected == 1) {
                    refresh = true;
                    continue;
                }

                var host = hosts.get(selected - 2);
                String players = host.players + (host.playerLimit > 0 ? "/" + host.playerLimit : "");
                String mode = host.modeName == null ? host.mode.toString() : oneLine(host.modeName);
                System.out.println("UPSTREAM_LAN_SELECTED address=" + host.address + " port=" + host.port
                    + " map=" + oneLine(host.mapname) + " players=" + host.players + " version=" + host.version);
                int build = UpstreamLiveNetworkProbe.BUILD;
                boolean compatible = host.version == -1 || host.version == build;
                var details = compatible ? new String[]{oneLine(host.mapname) + " / " + mode,
                    bundle.format("players", players), oneLine(bundle.format("server.version", host.version, host.versionType)),
                    host.address + ":" + host.port}
                    : Strings.stripColors(bundle.format("server.versions", build, host.version)).split("\\n");
                String[] actions = compatible ? new String[]{bundle.get("joingame.title"), bundle.get("back")}
                    : new String[]{bundle.get("back")};
                int action = menus.chooseTitleRow(oneLine(host.name), 0, actions, details);
                if (action == -2) {
                    menus.requestTitleExit();
                    return null;
                }
                if (action == 0 && compatible) return new UpstreamServerAddress(host.address, host.port);
            }
        }
    }

    private static String oneLine(String text) {
        return Strings.stripColors(text).replace('\n', ' ').replace('\r', ' ');
    }

    private static UpstreamServerAddress readServerAddress() throws IOException {
        // One byte for the terminator and up to four UTF-8 bytes per input character.
        byte[] text = new byte[4 * 259 + 1];
        int invalid = 0;
        System.out.println("UPSTREAM_SERVER_ADDRESS status=opened");
        while (true) {
            int length = NativeInput.serverAddress(Address.ofData(text), text.length, invalid);
            if (length == 0) {
                System.out.println("UPSTREAM_SERVER_ADDRESS status=canceled");
                return null;
            }
            if (length < 0 || length >= text.length) {
                throw new IOException("The 3DS server address keyboard could not open");
            }
            var address = UpstreamServerAddress.parseInput(text, length);
            if (address != null) {
                System.out.println("UPSTREAM_SERVER_ADDRESS status=confirmed");
                return address;
            }
            invalid = 1;
            System.out.println("UPSTREAM_SERVER_ADDRESS status=invalid");
        }
    }

    private static void offline(String[] args) throws IOException, InterruptedException {
        System.out.println("UPSTREAM_BOOT mode=" + (args.length > 0 && args[0].equals("--title") ? "title" : "offline"));
        var hooks = new UpstreamSinglePlayerApp.AudioHooks() {
                boolean opened;
                @Override
                public Audio open() {
                    opened = true;
                    Arm11Audio.open();
                    return Core.audio instanceof Arm11Audio audio ? audio : UpstreamSinglePlayerApp.AudioHooks.super.open();
                }

                @Override
                public void prepareWorld() {
                    Arm11Audio.prepareMusicControl();
                    Events.on(WorldLoadEvent.class, event -> Arm11Audio.reset());
                }

                @Override
                public void leaveWorld() {
                    Arm11Audio.reset();
                }

                @Override
                public void update(long now) {
                    var camera = Core.camera;
                    Arm11Audio.updateCamera(camera.position.x, camera.position.y, camera.width, camera.height);
                    Arm11Audio.updateClientAudio(now);
                }
        };
        try {
            UpstreamSinglePlayerApp.run(args, hooks, UpstreamUnifiedApp::joinFromTitle);
        } finally {
            if (hooks.opened) Arm11Audio.close();
        }
    }
}
