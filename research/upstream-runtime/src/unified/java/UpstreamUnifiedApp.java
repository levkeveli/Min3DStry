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
            final int hostButtons = UpstreamSinglePlayerInput.X_HELD | UpstreamSinglePlayerInput.Y_HELD;
            if ((input & hostButtons) == hostButtons) {
                offline(args, new UpstreamHostedSession(true));
            } else if ((input & UpstreamSinglePlayerInput.X_HELD) != 0) {
                join(args);
            } else if ((input & UpstreamSinglePlayerInput.Y_HELD) != 0) {
                offline(new String[]{"--title"});
            } else {
                offline(args);
            }
        } else if (args[0].equals("--host")) {
            offline(Arrays.copyOfRange(args, 1, args.length), new UpstreamHostedSession(true));
        } else if (args[0].equals("--join")) {
            if (args.length > 2) throw new IllegalArgumentException("Expected --join [endpoint-file]");
            join(Arrays.copyOfRange(args, 1, args.length));
        } else {
            offline(args);
        }
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
        offline(args, new UpstreamHostedSession(false));
    }

    private static void offline(String[] args, UpstreamSinglePlayerApp.SessionHooks session) throws IOException {
        System.out.println("UPSTREAM_BOOT mode=offline");
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
            }, session);
        } finally {
            Arm11Audio.close();
        }
    }
}
