import arc.files.Fi;
import java.io.IOException;
import java.util.Arrays;

/** Select one upstream game session before initializing its content and platform services. */
public final class UpstreamUnifiedApp {
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
            offline(args);
            return;
        }
        if (args.length > 2) throw new IllegalArgumentException("Expected --join [endpoint-file]");
        var endpoint = UpstreamLiveNetworkProbe.endpointFile(
            args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length));
        if (!endpoint.exists() || endpoint.isDirectory()) {
            System.out.println("No server endpoint file at " + endpoint.path());
            if (args.length == 0) offline(args);
            return;
        }
        System.out.println("UPSTREAM_BOOT mode=join");
        UpstreamRenderedNetworkProbe.run(endpoint, new Fi("/romfs/sprites.aatls"));
    }

    private static void offline(String[] args) throws IOException, InterruptedException {
        System.out.println("UPSTREAM_BOOT mode=offline");
        UpstreamSinglePlayerApp.main(args);
    }
}
