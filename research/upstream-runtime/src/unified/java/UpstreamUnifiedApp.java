import arc.Core;
import arc.Events;
import arc.audio.Audio;
import arc.files.Fi;
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
        var endpoint = UpstreamLiveNetworkProbe.endpointFile(endpointArgs);
        var atlas = new Fi("/romfs/sprites.aatls");
        if (endpoint.exists() && !endpoint.isDirectory()) {
            System.out.println("UPSTREAM_BOOT mode=join");
            UpstreamRenderedNetworkProbe.run(endpoint, atlas);
        } else if (endpointArgs.length != 0) {
            System.out.println("No server endpoint file at " + endpoint.path());
        } else {
            var address = readServerAddress();
            if (address != null) {
                System.out.println("UPSTREAM_BOOT mode=join");
                UpstreamRenderedNetworkProbe.run(endpoint.parent(), address.host() + " " + address.port(), atlas);
            }
        }
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
        try {
            UpstreamSinglePlayerApp.run(args, new UpstreamSinglePlayerApp.AudioHooks() {
                @Override
                public Audio open() {
                    Arm11Audio.open();
                    return Core.audio instanceof Arm11Audio audio ? audio : UpstreamSinglePlayerApp.AudioHooks.super.open();
                }

                @Override
                public void prepareWorld() {
                    Arm11Audio.prepareMusicControl();
                    Events.on(WorldLoadEvent.class, event -> Arm11Audio.reset());
                }

                @Override
                public void update(long now) {
                    var camera = Core.camera;
                    Arm11Audio.updateCamera(camera.position.x, camera.position.y, camera.width, camera.height);
                    Arm11Audio.updateClientAudio(now);
                }
            });
        } finally {
            Arm11Audio.close();
        }
    }
}
