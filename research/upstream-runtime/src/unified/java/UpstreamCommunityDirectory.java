import arc.struct.Seq;
import arc.util.Strings;
import arc.util.serialization.Jval;
import mindustry.net.Host;
import mindustry.net.ServerGroup;

import java.util.Locale;

/** Stock v160.8 JoinDialog directory schema and search, independent of desktop widgets. */
final class UpstreamCommunityDirectory {
    static final String CACHE_NAME = "server_list.json";
    static final String ENABLED_SETTING = "communityservers";
    static final String SORT_PING_SETTING = "sort-servers-ping";

    private UpstreamCommunityDirectory() {
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
