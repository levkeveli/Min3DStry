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
            try {
                UpstreamServerAddress.parse(invalid);
                throw new AssertionError("Accepted invalid address: " + invalid);
            } catch (IllegalArgumentException expected) {
                // The console asks again before starting the network session.
            }
        }
        System.out.println("UPSTREAM_SERVER_ADDRESS_REFERENCE default=" + Vars.port + " explicit=ok invalid=rejected");
    }

    private static void check(String text, String host, int port) {
        var result = UpstreamServerAddress.parse(text);
        if (!result.host().equals(host) || result.port() != port) {
            throw new AssertionError("Unexpected endpoint for " + text + ": " + result);
        }
    }
}
