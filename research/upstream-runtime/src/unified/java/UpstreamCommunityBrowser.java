import arc.Core;
import arc.Settings;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.net.Host;
import mindustry.net.ServerGroup;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** New 3DS title menu for the original JoinDialog community directory. */
final class UpstreamCommunityBrowser {
    private final UpstreamSinglePlayerApp menus;
    private final Settings settings;
    private Seq<ServerGroup> groups = new Seq<>();
    private final ObjectMap<ServerGroup, Seq<UpstreamCommunityProbes.Result>> live = new ObjectMap<>();
    private String search = "";
    private boolean showHidden;

    UpstreamCommunityBrowser(UpstreamSinglePlayerApp menus, Settings settings) {
        this.menus = menus;
        this.settings = settings;
    }

    UpstreamServerAddress choose() throws InterruptedException {
        var bundle = Core.bundle;
        boolean refresh = enabled();
        boolean probe = refresh;
        if (refresh) {
            try {
                groups = UpstreamCommunityDirectory.loadCache(settings);
                System.out.println("UPSTREAM_COMMUNITY_DIRECTORY cache=" + groups.size);
            } catch (IOException invalid) {
                System.out.println("UPSTREAM_COMMUNITY_DIRECTORY cache=invalid");
            }
            // An existing cache can be browsed while offline. Refresh is also available explicitly.
            refresh = groups.isEmpty();
        }

        String title = bundle.get("servers.global");
        while (!menus.titleExitRequested()) {
            if (refresh && enabled()) probe |= refreshDirectory();
            refresh = false;
            if (probe && enabled() && !menus.titleExitRequested()) probeServers();
            probe = false;
            if (menus.titleExitRequested()) return null;

            if (!enabled()) {
                if (chooseRow(title, 0, new String[]{bundle.get("setting.communityservers.name"), bundle.get("back")}) != 0) {
                    return null;
                }
                setEnabled(true);
                refresh = true;
                continue;
            }
            var visible = visibleGroups();
            final int controls = 5;
            int row = menus.chooseTitlePagedRow(title, visible.size + controls, 0, index -> switch (index) {
                case 0 -> bundle.get("hosts.refresh");
                case 1 -> bundle.get("search") + (search.isEmpty() ? "" : " " + search);
                case 2 -> bundle.get(showHidden ? "server.hidden" : "servers.showhidden");
                case 3 -> bundle.get("setting.communityservers.name");
                case 4 -> bundle.get(sortPing() ? "servers.sortping" : "servers.sortplayers");
                default -> label(visible.get(index - controls));
            });
            if (row == -2) menus.requestTitleExit();
            if (row < 0) return null;
            switch (row) {
                case 0 -> refresh = true;
                case 1 -> {
                    String text = UpstreamTerrainRenderProbe.readServerSearch(search);
                    if (text != null) search = UpstreamCommunityDirectory.normalizeSearch(text);
                }
                case 2 -> {
                    showHidden = !showHidden;
                    probe = showHidden;
                }
                case 3 -> setEnabled(false);
                case 4 -> {
                    settings.put(UpstreamCommunityDirectory.SORT_PING_SETTING, !sortPing());
                    settings.manualSave();
                }
                default -> {
                    var address = chooseGroup(visible.get(row - controls));
                    if (address != null) return address;
                }
            }
        }
        return null;
    }

    private Seq<ServerGroup> visibleGroups() {
        var visible = new Seq<ServerGroup>();
        for (int i = 0; i < groups.size; i++) {
            var group = groups.get((i + groups.size / 2) % groups.size);
            if ((showHidden || !group.hidden()) && matches(group)) {
                visible.add(group);
            }
        }
        if (sortPing()) {
            visible.sort(group -> group.prioritized ? 0 : group.favorite() ? 1 : 2);
        } else {
            visible.sort((left, right) -> Integer.compare(playerRank(right), playerRank(left)));
        }
        return visible;
    }

    private boolean matches(ServerGroup group) {
        if (group.name.toLowerCase(Locale.ROOT).contains(search)) return true;
        var entries = live.get(group);
        return entries != null && entries.contains(entry -> UpstreamCommunityDirectory.matches(group, entry.host(), search));
    }

    private int playerRank(ServerGroup group) {
        int players = 0;
        var entries = live.get(group);
        if (entries != null) {
            for (var entry : entries) {
                Host host = entry.host();
                if (!Strings.stripColors(host.name).toLowerCase(Locale.ROOT).contains("hub")
                    && UpstreamCommunityDirectory.matches(group, host, search)) {
                    players = Math.max(players, host.players);
                }
            }
        }
        return players + (group.favorite() ? 9_999_999 : 0);
    }

    private boolean sortPing() {
        return settings.getBool(UpstreamCommunityDirectory.SORT_PING_SETTING, true);
    }

    private UpstreamServerAddress chooseGroup(ServerGroup group) {
        var bundle = Core.bundle;
        var entries = live.get(group);
        var visible = entries == null ? new Seq<UpstreamCommunityProbes.Result>()
            : entries.select(entry -> UpstreamCommunityDirectory.matches(group, entry.host(), search));
        if (sortPing()) visible.sort(entry -> entry.host().ping);
        else visible.sort(entry -> -entry.host().players);
        final int controls = 2;
        while (!menus.titleExitRequested()) {
            int row = menus.chooseTitlePagedRow(label(group), visible.size + controls, 0, index -> {
                if (index == 0) return group.favorite() ? "★" : "☆";
                if (index == 1) return bundle.get(group.hidden() ? "server.hidden" : "server.shown");
                Host host = visible.get(index - controls).host();
                return oneLine(host.name) + "  " + (sortPing() ? bundle.format("setting.milliseconds", host.ping)
                    : bundle.format("players", host.players));
            });
            if (row == -2) menus.requestTitleExit();
            if (row < 0) return null;
            if (row == 0) {
                group.setFavorite(!group.favorite());
                settings.manualSave();
            } else if (row == 1) {
                group.setHidden(!group.hidden());
                settings.manualSave();
                if (group.hidden() && !showHidden) return null;
            } else {
                var address = chooseServer(visible.get(row - controls));
                if (address != null) return address;
            }
        }
        return null;
    }

    private String label(ServerGroup group) {
        var name = group.name.isEmpty() && group.addresses.length > 0 ? group.addresses[0] : group.name;
        return (group.favorite() ? "* " : "") + oneLine(name);
    }

    private UpstreamServerAddress chooseServer(UpstreamCommunityProbes.Result entry) {
        var bundle = Core.bundle;
        Host host = entry.host();
        int build = UpstreamLiveNetworkProbe.BUILD;
        boolean compatible = host.version == -1 || host.version == build;
        var address = entry.address();
        String mode = host.modeName == null ? host.mode.toString() : host.modeName;
        var details = new String[]{
            oneLine(host.mapname) + " / " + oneLine(mode),
            bundle.format("players", host.players + (host.playerLimit > 0 ? "/" + host.playerLimit : "")),
            oneLine(bundle.format("server.version", host.version, host.versionType)),
            oneLine(host.description),
            address.host() + ":" + address.port()
        };
        System.out.println("UPSTREAM_COMMUNITY_HOST name=" + oneLine(host.name) + " map=" + oneLine(host.mapname)
            + " version=" + host.version + " port=" + host.port);
        if (!compatible) {
            chooseRow(oneLine(host.name), 0, new String[]{bundle.get("back")},
                Strings.stripColors(bundle.format("server.versions", build, host.version)).split("\n"));
            return null;
        }
        int action = chooseRow(oneLine(host.name), 0,
            new String[]{bundle.get("joingame.title"), bundle.get("server.add"), bundle.get("back")}, details);
        if (action == 1) {
            try {
                var saved = UpstreamSavedServers.load(settings);
                saved.add(new UpstreamSavedServers.Entry(address));
                UpstreamSavedServers.save(settings, saved);
            } catch (IOException failure) {
                chooseRow(bundle.get("error"), 0, new String[]{bundle.get("back")});
            }
        }
        if (action != 0) return null;
        if (!settings.getBool("server-disclaimer", false)) {
            int accepted = chooseRow(bundle.get("warning"), 1, new String[]{bundle.get("ok"), bundle.get("back")},
                Strings.stripColors(bundle.get("servers.disclaimer")).split("\n"));
            if (accepted != 0) return null;
            settings.put("server-disclaimer", true);
            settings.manualSave();
        }
        return address;
    }

    private static String oneLine(String text) {
        return Strings.stripColors(text).replace('\n', ' ').replace('\r', ' ');
    }

    private int chooseRow(String title, int selected, String[] options, String... headings) {
        int row = menus.chooseTitleRow(title, selected, options, headings);
        if (row == -2) menus.requestTitleExit();
        return row;
    }

    private boolean enabled() {
        return settings.getBool(UpstreamCommunityDirectory.ENABLED_SETTING, true);
    }

    private void setEnabled(boolean enabled) {
        settings.put(UpstreamCommunityDirectory.ENABLED_SETTING, enabled);
        settings.manualSave();
    }

    private void probeServers() throws InterruptedException {
        var results = UpstreamCommunityProbes.query(groups.select(group -> showHidden || !group.hidden()), this::cancelLoading);
        live.clear();
        for (var entry : results) live.get(entry.group(), Seq::new).add(entry);
        System.out.println("UPSTREAM_COMMUNITY_DIRECTORY hosts=" + results.size);
    }

    private boolean cancelLoading() {
        menus.showTitleStatus(Strings.stripColors(Core.bundle.get("hosts.discovering.any")));
        int input = UpstreamSinglePlayerInput.poll();
        if (input < 0) menus.requestTitleExit();
        return input < 0 || (input & UpstreamSinglePlayerInput.B_PRESSED) != 0;
    }

    private boolean refreshDirectory() throws InterruptedException {
        byte[] response = new byte[UpstreamCommunityDirectory.MAX_DIRECTORY_BYTES];
        String[] urls = "bleeding-edge".equals(Version.type) || Vars.forceBeServers
            ? Vars.serverJsonBeURLs : Vars.serverJsonURLs;
        for (int source = 0; source < urls.length; source++) {
            byte[] endpoint = urls[source].getBytes(StandardCharsets.US_ASCII);
            if (Native.start(Address.ofData(endpoint), endpoint.length) == 0) continue;
            try {
                int size;
                boolean canceled = false;
                while ((size = Native.poll(Address.ofData(response), response.length)) == 0) {
                    if (cancelLoading()) {
                        Native.cancel();
                        canceled = true;
                    }
                    Thread.sleep(16);
                }
                if (canceled || menus.titleExitRequested()) return false;
                if (size > 0) {
                    groups = UpstreamCommunityDirectory.install(settings, Arrays.copyOf(response, size));
                    System.out.println("UPSTREAM_COMMUNITY_DIRECTORY fetched=" + groups.size + " source=" + source);
                    return true;
                }
            } catch (IOException invalid) {
                System.out.println("UPSTREAM_COMMUNITY_DIRECTORY response=invalid");
            } catch (InterruptedException interrupted) {
                Native.cancel();
                while (Native.poll(Address.ofData(response), response.length) == 0) Thread.yield();
                throw interrupted;
            }
        }
        System.out.println("UPSTREAM_COMMUNITY_DIRECTORY refresh=failed cache=" + groups.size);
        return true;
    }

    @Include(value = "m3ds_community_http.h", isSystem = true)
    private static final class Native {
        @Import(name = "m3ds_community_http_start")
        static native int start(Address url, int length);

        @Import(name = "m3ds_community_http_poll")
        static native int poll(Address output, int capacity);

        @Import(name = "m3ds_community_http_cancel")
        static native void cancel();
    }
}
