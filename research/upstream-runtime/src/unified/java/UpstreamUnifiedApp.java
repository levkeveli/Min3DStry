import arc.files.Fi;
import java.io.IOException;
import java.util.Arrays;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

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
        boolean join = args.length > 0 && args[0].equals("--join");
        if (args.length == 0) {
            int input = UpstreamSinglePlayerInput.poll();
            if (input < 0) return;
            join = (input & UpstreamSinglePlayerInput.JOIN_HELD) != 0;
        }
        if (!join) {
            System.out.println("UPSTREAM_BOOT mode=offline");
            UpstreamSinglePlayerApp.main(args);
            return;
        }
        if (args.length > 2) throw new IllegalArgumentException("Expected --join [endpoint-file]");
        var endpoint = UpstreamLiveNetworkProbe.endpointFile(
            args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length));
        System.out.println("UPSTREAM_BOOT mode=join");
        var atlas = new Fi("/romfs/sprites.aatls");
        if (endpoint.exists() && !endpoint.isDirectory()) {
            UpstreamRenderedNetworkProbe.run(endpoint, atlas);
        } else if (args.length == 2) {
            System.out.println("No server endpoint file at " + endpoint.path());
        } else {
            var address = readServerAddress();
            if (address != null) {
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
}
