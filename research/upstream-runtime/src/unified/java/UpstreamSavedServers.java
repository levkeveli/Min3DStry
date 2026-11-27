import arc.Settings;
import arc.util.serialization.UBJsonReader;
import arc.util.serialization.UBJsonWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;

/** The original JoinDialog Server list in Arc's binary settings format. */
final class UpstreamSavedServers {
    private static final String key = "servers";
    private static final String legacyKey = "server-list";

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
        if (settings.has(legacyKey)) return loadLegacy(settings);
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

    // JoinDialog gives the legacy key priority. Preserve LegacyIO's record layout
    // while requiring a complete list before allowing edits.
    private static List<Entry> loadLegacy(Settings settings) throws IOException {
        Object value = settings.get(legacyKey, null);
        if (!(value instanceof byte[] bytes)) throw new IOException("Expected legacy server list data");
        var entries = new ArrayList<Entry>();
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = input.readInt();
            if (count < 0) throw new IOException("Invalid legacy server count");
            if (count > 0) input.readUTF(); // Stock type name.
            if (count > input.available() / (Short.BYTES + Integer.BYTES)) {
                throw new IOException("Incomplete legacy server list");
            }
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(input.readUTF(), input.readInt()));
            }
        }
        return entries;
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
        if (settings.has(legacyKey)) settings.remove(legacyKey);
        settings.manualSave();
    }
}
