import arc.Settings;
import arc.files.Fi;
import arc.struct.Seq;
import java.io.IOException;
import java.util.Arrays;
import mindustry.ui.dialogs.JoinDialog;

/** Verifies settings compatibility with the unmodified v160.8 JoinDialog.Server. */
public final class UpstreamSavedServersReference {
    public static void main(String[] args) throws IOException {
        if (args.length == 3) {
            var directory = new Fi(args[1]);
            int port = Integer.parseInt(args[2]);
            var stock = new Settings();
            stock.setDataDirectory(directory);
            stock.load();
            if (args[0].equals("--write-fixture")) {
                stock.putJson("servers", JoinDialog.Server.class,
                    Seq.with(server("retired.example", 6567), server("127.0.0.1", port)));
                stock.manualSave();
            } else if (args[0].equals("--check-fixture")) {
                Seq<?> entries = stock.getJson("servers", Seq.class, JoinDialog.Server.class, Seq::new);
                if (entries.size != 1) throw new AssertionError("Expected one saved server after deletion");
                expect((JoinDialog.Server)entries.first(), "127.0.0.1", port);
                System.out.println("UPSTREAM_SAVED_SERVERS_REPLAY_READBACK stock=true entries=1 port=" + port);
            } else {
                throw new IllegalArgumentException("Expected --write-fixture or --check-fixture");
            }
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("Expected a settings directory");
        var directory = new Fi(args[0]);
        var stock = new Settings();
        stock.setDataDirectory(directory);
        stock.load();
        stock.putJson("servers", JoinDialog.Server.class, Seq.with(
            server("127.0.0.1", 6567), server("::1", 6568), server("retired.example", 65535)));
        stock.put("locale", "en");
        stock.manualSave();

        var port = new Settings();
        port.setDataDirectory(directory);
        port.load();
        var entries = UpstreamSavedServers.load(port);
        if (entries.size() != 3 || !new UpstreamServerAddress("127.0.0.1", 6567).equals(entries.get(0).address())
                || !entries.get(1).label().equals("[::1]:6568") || entries.get(1).address() != null
                || entries.get(2).port() != 65535) {
            throw new AssertionError("The original desktop server list did not load");
        }
        entries.set(0, new UpstreamSavedServers.Entry(new UpstreamServerAddress("example.net", 16567)));
        entries.remove(2);
        entries.add(new UpstreamSavedServers.Entry(new UpstreamServerAddress("192.168.1.40", 6567)));
        UpstreamSavedServers.save(port, entries);

        var reopened = new Settings();
        reopened.setDataDirectory(directory);
        reopened.loadValues();
        Seq<?> servers = reopened.getJson("servers", Seq.class, JoinDialog.Server.class, Seq::new);
        if (servers.size != 3 || !reopened.getString("locale", "").equals("en")) {
            throw new AssertionError("The original desktop reader did not preserve the list or the other settings");
        }
        expect((JoinDialog.Server)servers.get(0), "example.net", 16567);
        expect((JoinDialog.Server)servers.get(1), "::1", 6568);
        expect((JoinDialog.Server)servers.get(2), "192.168.1.40", 6567);

        byte[] damaged = {1, 2, 3};
        reopened.put("servers", damaged);
        try {
            UpstreamSavedServers.load(reopened);
            throw new AssertionError("Malformed server data should be left untouched");
        } catch (IOException expected) {
            if (!Arrays.equals(reopened.getBytes("servers", null), damaged)) {
                throw new AssertionError("Read replaced the malformed setting");
            }
        }
        System.out.println("UPSTREAM_SAVED_SERVERS_REFERENCE stock-read=ok stock-write=ok ipv6=preserved malformed=preserved");
    }

    private static JoinDialog.Server server(String ip, int port) {
        var entry = new JoinDialog.Server();
        entry.ip = ip;
        entry.port = port;
        return entry;
    }

    private static void expect(JoinDialog.Server entry, String ip, int port) {
        if (!ip.equals(entry.ip) || port != entry.port) throw new AssertionError("Unexpected stock endpoint");
    }
}
