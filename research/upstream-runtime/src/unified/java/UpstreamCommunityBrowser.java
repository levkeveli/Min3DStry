import arc.Core;
import arc.Settings;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.core.Version;
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
    private String search = "";
    private boolean showHidden;

    UpstreamCommunityBrowser(UpstreamSinglePlayerApp menus, Settings settings) {
        this.menus = menus;
        this.settings = settings;
    }

    UpstreamServerAddress choose() throws InterruptedException {
        var bundle = Core.bundle;
        boolean refresh = enabled();
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
            if (refresh && enabled()) refreshDirectory();
            refresh = false;
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
            final int controls = 4;
            int row = menus.chooseTitlePagedRow(title, visible.size + controls, 0, index -> switch (index) {
                case 0 -> bundle.get("hosts.refresh");
                case 1 -> bundle.get("search") + (search.isEmpty() ? "" : " " + search);
                case 2 -> bundle.get(showHidden ? "server.hidden" : "servers.showhidden");
                case 3 -> bundle.get("setting.communityservers.name");
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
                case 2 -> showHidden = !showHidden;
                case 3 -> setEnabled(false);
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
            if ((showHidden || !group.hidden()) && group.name.toLowerCase(Locale.ROOT).contains(search)) {
                visible.add(group);
            }
        }
        visible.sort(group -> group.prioritized ? 0 : group.favorite() ? 1 : 2);
        return visible;
    }

    private UpstreamServerAddress chooseGroup(ServerGroup group) {
        var bundle = Core.bundle;
        final int controls = 2;
        while (!menus.titleExitRequested()) {
            int row = menus.chooseTitlePagedRow(label(group), group.addresses.length + controls, 0, index -> {
                if (index == 0) return group.favorite() ? "★" : "☆";
                if (index == 1) return bundle.get(group.hidden() ? "server.hidden" : "server.shown");
                return group.addresses[index - controls];
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
                String endpoint = group.addresses[row - controls];
                var address = UpstreamServerAddress.parse(endpoint);
                if (address != null && chooseRow(bundle.get("joingame.title"), 1,
                    new String[]{bundle.get("ok"), bundle.get("cancel")},
                    Strings.stripColors(bundle.format("servers.connect.confirm", endpoint)).split("\n")) == 0) {
                    return address;
                }
            }
        }
        return null;
    }

    private String label(ServerGroup group) {
        var name = group.name.isEmpty() && group.addresses.length > 0 ? group.addresses[0] : group.name;
        return (group.favorite() ? "* " : "") + Strings.stripColors(name).replace('\n', ' ').replace('\r', ' ');
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

    private void refreshDirectory() throws InterruptedException {
        byte[] response = new byte[UpstreamCommunityDirectory.MAX_DIRECTORY_BYTES];
        String[] urls = Version.type.equals("bleeding-edge") || Vars.forceBeServers
            ? Vars.serverJsonBeURLs : Vars.serverJsonURLs;
        for (String url : urls) {
            byte[] endpoint = url.getBytes(StandardCharsets.US_ASCII);
            if (Native.start(Address.ofData(endpoint), endpoint.length) == 0) continue;
            try {
                int size;
                boolean canceled = false;
                while ((size = Native.poll(Address.ofData(response), response.length)) == 0) {
                    menus.showTitleStatus(Strings.stripColors(Core.bundle.get("hosts.discovering.any")));
                    int input = UpstreamSinglePlayerInput.poll();
                    if (input < 0) menus.requestTitleExit();
                    if (input < 0 || (input & UpstreamSinglePlayerInput.B_PRESSED) != 0) {
                        Native.cancel();
                        canceled = true;
                    }
                    Thread.sleep(16);
                }
                if (canceled || menus.titleExitRequested()) return;
                if (size > 0) {
                    groups = UpstreamCommunityDirectory.install(settings, Arrays.copyOf(response, size));
                    System.out.println("UPSTREAM_COMMUNITY_DIRECTORY fetched=" + groups.size);
                    return;
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
