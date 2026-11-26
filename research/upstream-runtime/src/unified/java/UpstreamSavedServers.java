import arc.Settings;
import arc.util.serialization.UBJsonReader;
import arc.util.serialization.UBJsonWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;

/** The original JoinDialog Server list in Arc's binary settings format. */
final class UpstreamSavedServers {
    private static final String key = "servers";

    record Entry(String ip, int port) {
        Entry(UpstreamServerAddress address) {
            this(address.host(), address.port());
        }

        String label() {
            String host = ip.contains(":") && !ip.startsWith("[") ? "[" + ip + "]" : ip;
            return (host + ":" + port).replace('\n', ' ').replace('\r', ' ').replace('\0', ' ');
        }

        UpstreamServerAddress address() {
            return UpstreamServerAddress.parse(label());
        }
    }

    static List<Entry> load(Settings settings) throws IOException {
        var entries = new ArrayList<Entry>();
        Object value = settings.get(key, null);
        if (value == null) return entries;
        if (!(value instanceof byte[] bytes)) throw new IOException("Expected stock servers setting data");
        try {
            var root = new UBJsonReader().parse(new ByteArrayInputStream(bytes));
            if (!root.isArray()) throw new IOException("Expected a stock server list");
            for (var row : root) {
                if (!row.isObject() || !row.has("ip") || !row.get("ip").isString()) {
                    throw new IOException("Expected a stock server record");
                }
                entries.add(new Entry(row.getString("ip"), row.getInt("port", Vars.port)));
            }
            return entries;
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid stock servers setting", malformed);
        }
    }

    static void save(Settings settings, List<Entry> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var writer = new UBJsonWriter(bytes)) {
            writer.array();
            for (var entry : entries) {
                writer.object().set("ip", entry.ip()).set("port", entry.port()).pop();
            }
            writer.pop();
        }
        settings.put(key, bytes.toByteArray());
        settings.manualSave();
    }
}
