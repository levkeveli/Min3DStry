import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.struct.Seq;
import mindustry.net.ServerGroup;
import mindustry.ui.dialogs.JoinDialog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Compares the native title directory storage with the original Java v160.8 classes. */
public final class UpstreamCommunityDirectoryReference {
    public static void main(String[] args) throws ReflectiveOperationException, IOException {
        var settings = new Settings();
        settings.setDataDirectory(new Fi(args[1]));
        settings.load();
        Core.settings = settings;
        if (args[0].equals("--check-replay")) {
            var group = new ServerGroup("Replay Group", new String[]{"127.0.0.1"});
            if (!group.favorite() || !settings.getBool("server-disclaimer", false)) {
                throw new AssertionError("Stock settings did not read the community favorite and acknowledgement");
            }
            System.out.println("UPSTREAM_COMMUNITY_READBACK favorite=ok disclaimer=ok");
            return;
        }
        if (!args[0].equals("--check-cache")) throw new IllegalArgumentException("Expected a directory check");

        String first = """
            [{"name":"Old","address":"old.example"},
             {"name":"Other","address":["a.example","b.example:16690"]},
             {"name":"Priority","addresses":["new.example","lab.example:16691"],"prioritized":true}]
            """;
        var parseStock = JoinDialog.class.getDeclaredMethod("parseServerString", String.class);
        parseStock.setAccessible(true);
        @SuppressWarnings("unchecked")
        var expected = (Seq<ServerGroup>)parseStock.invoke(null, first);
        var installed = UpstreamCommunityDirectory.install(settings, first.getBytes(StandardCharsets.UTF_8));
        expectSame(expected, installed);
        expectSame(expected, UpstreamCommunityDirectory.loadCache(settings));
        try {
            UpstreamCommunityDirectory.install(settings, "{truncated".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("Invalid refresh overwrote the cache");
        } catch (IOException rejected) {
            expectSame(expected, UpstreamCommunityDirectory.loadCache(settings));
        }
        String replacement = "[{\"name\":\"New\",\"address\":\"new.example\"}]";
        UpstreamCommunityDirectory.install(settings, replacement.getBytes(StandardCharsets.UTF_8));
        if (!UpstreamCommunityDirectory.loadCache(settings).first().name.equals("New")) {
            throw new AssertionError("Valid refresh did not publish the replacement");
        }
        settings.getDataDirectory().child("server_list.json").writeString("{truncated", false, "UTF-8");
        expectSame(expected, UpstreamCommunityDirectory.loadCache(settings));
        System.out.println("UPSTREAM_COMMUNITY_CACHE stock-schema=ok refresh=ok backup=ok");
    }

    private static void expectSame(Seq<ServerGroup> expected, Seq<ServerGroup> actual) {
        if (expected.size != actual.size) throw new AssertionError("Directory group count differs from stock");
        for (var group : expected) {
            var match = actual.find(candidate -> candidate.name.equals(group.name));
            if (match == null || match.prioritized != group.prioritized || !Arrays.equals(match.addresses, group.addresses)) {
                throw new AssertionError("Directory group differs from stock: " + group.name);
            }
        }
    }
}
