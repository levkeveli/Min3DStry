import arc.Settings;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.serialization.Jval;
import mindustry.net.Host;
import mindustry.net.ServerGroup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Stock v160.8 JoinDialog directory schema and search, independent of desktop widgets. */
final class UpstreamCommunityDirectory {
    static final String CACHE_NAME = "server_list.json";
    static final String ENABLED_SETTING = "communityservers";
    static final String SORT_PING_SETTING = "sort-servers-ping";
    static final int MAX_DIRECTORY_BYTES = 256 * 1024;

    private UpstreamCommunityDirectory() {
    }

    static Seq<ServerGroup> loadCache(Settings settings) throws IOException {
        Fi cache = settings.getDataDirectory().child(CACHE_NAME);
        Fi backup = backupOf(cache);
        try {
            if (cache.exists()) return readCache(cache);
        } catch (IOException invalid) {
            if (!backup.exists()) throw invalid;
        }
        return backup.exists() ? readCache(backup) : new Seq<>();
    }

    static Seq<ServerGroup> install(Settings settings, byte[] bytes) throws IOException {
        var groups = decode(bytes);
        Fi cache = settings.getDataDirectory().child(CACHE_NAME);
        Fi pending = cache.sibling(CACHE_NAME + ".pending");
        Fi backup = backupOf(cache);
        try {
            pending.writeBytes(bytes);
            if (cache.exists()) {
                if (validCache(cache)) {
                    remove(backup);
                    if (!cache.file().renameTo(backup.file())) {
                        throw new IOException("Could not back up the stock server directory");
                    }
                } else {
                    remove(cache);
                }
            }
            if (!pending.file().renameTo(cache.file())) {
                // The previous directory is still available through loadCache.
                throw new IOException("Could not install the stock server directory");
            }
            return groups;
        } finally {
            if (pending.exists()) pending.delete();
        }
    }

    private static Fi backupOf(Fi cache) {
        return cache.sibling(CACHE_NAME + ".backup");
    }

    private static void remove(Fi file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Could not remove " + file.path());
    }

    private static boolean validCache(Fi file) {
        try {
            readCache(file);
            return true;
        } catch (IOException invalid) {
            return false;
        }
    }

    private static Seq<ServerGroup> readCache(Fi file) throws IOException {
        if (file.isDirectory() || file.length() > MAX_DIRECTORY_BYTES) {
            throw new IOException("Invalid stock server directory cache " + file.path());
        }
        try {
            return decode(file.readBytes());
        } catch (RuntimeException failure) {
            throw new IOException("Could not read the stock server directory cache " + file.path(), failure);
        }
    }

    private static Seq<ServerGroup> decode(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_DIRECTORY_BYTES) {
            throw new IOException("Stock server directory exceeds the supported size");
        }
        try {
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed stock server directory", malformed);
        }
    }

    // Adapted from pinned Mindustry JoinDialog.parseServerString (GPL-3.0).
    static Seq<ServerGroup> parse(String text) {
        Seq<ServerGroup> groups = new Seq<>();
        for (Jval group : Jval.read(text).asArray()) {
            String name = group.getString("name", "");
            boolean prioritized = group.getBool("prioritized", false);
            Jval address = group.get("address");
            Jval addresses = group.get("addresses");
            if (addresses == null && address != null && address.isArray()) addresses = address;
            String[] endpoints = addresses == null
                ? new String[]{group.getString("address", "<invalid>")}
                : addresses.asArray().map(Jval::asString).toArray(String.class);
            groups.add(new ServerGroup(name, endpoints, prioritized));
        }
        groups.shuffle();
        return groups;
    }

    static String normalizeSearch(String text) {
        return text.trim().replaceAll(" +", " ").toLowerCase(Locale.ROOT);
    }

    static boolean matches(ServerGroup group, Host host, String search) {
        return search.isEmpty() || group.name.toLowerCase(Locale.ROOT).contains(search)
            || matches(host.name, search) || matches(host.description, search)
            || matches(host.mapname, search) || matches(host.modeName, search);
    }

    private static boolean matches(String text, String search) {
        return text != null && Strings.stripColors(text.toLowerCase(Locale.ROOT)).contains(search);
    }
}
