import java.nio.charset.StandardCharsets;
import mindustry.Vars;

public final class UpstreamServerAddressReference {
    public static void main(String[] args) {
        check("127.0.0.1", "127.0.0.1", Vars.port);
        check(" game.example.org:16574 ", "game.example.org", 16574);
        check("my-server.lan.:65535", "my-server.lan.", 65535);
        check("localhost:1", "localhost", 1);
        check("a".repeat(253) + ":65535", "a".repeat(253), 65535);
        for (var invalid : new String[]{"", "  ", ":6567", "host:", "host:0", "host:65536",
            "host:+12", "host:12:34", "host:655367", "[::1]:6567", "host name",
            "http://host", "---", "a".repeat(254)}) {
            byte[] input = invalid.getBytes(StandardCharsets.UTF_8);
            if (UpstreamServerAddress.parseInput(input, input.length) != null) {
                throw new AssertionError("Accepted invalid address: " + invalid);
            }
        }
        byte[] nonAscii = "é.example".getBytes(StandardCharsets.UTF_8);
        if (UpstreamServerAddress.parseInput(nonAscii, nonAscii.length) != null) {
            throw new AssertionError("Accepted a non-ASCII name on the IPv4 transport");
        }
        System.out.println("UPSTREAM_SERVER_ADDRESS_REFERENCE default=" + Vars.port + " explicit=ok invalid=rejected");
    }

    private static void check(String text, String host, int port) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        var result = UpstreamServerAddress.parseInput(input, input.length);
        if (result == null || !result.host().equals(host) || result.port() != port) {
            throw new AssertionError("Unexpected endpoint for " + text + ": " + result);
        }
    }
}
