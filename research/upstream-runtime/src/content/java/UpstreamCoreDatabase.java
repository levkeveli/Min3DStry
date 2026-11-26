import arc.struct.ObjectSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.ctype.UnlockableContent;

/** Catalog and filtering rules from the pinned v160.8 DatabaseDialog. */
final class UpstreamCoreDatabase {
    record Entry(UnlockableContent content, boolean unlocked) {}
    record Group(String category, String tag, List<Entry> entries) {}

    private static final Comparator<Entry> playingOrder = Comparator
        .comparing((Entry entry) -> entry.content().isBanned())
        .thenComparingInt(entry -> entry.content().id);

    List<UnlockableContent> tabs() {
        var tabs = new ObjectSet<UnlockableContent>();
        for (var type : Vars.content.getContentMap()) {
            for (var content : type) {
                if (content instanceof UnlockableContent item) tabs.addAll(item.databaseTabs);
            }
        }
        var result = new ArrayList<UnlockableContent>();
        result.add(Planets.sun);
        for (var tab : tabs.toSeq().sort()) result.add(tab);
        return result;
    }

    UnlockableContent initialTab(List<UnlockableContent> tabs) {
        if (Vars.state.isCampaign() && tabs.contains(Vars.state.getPlanet())) return Vars.state.getPlanet();
        if (Vars.state.isGame() && tabs.contains(Vars.state.rules.planet)) return Vars.state.rules.planet;
        return Planets.sun;
    }

    List<Group> groups(UnlockableContent tab, String query) {
        String search = query.toLowerCase();
        var groups = new LinkedHashMap<String, LinkedHashMap<String, List<Entry>>>();
        for (var type : Vars.content.getContentMap()) {
            for (var content : type) {
                if (!(content instanceof UnlockableContent item)) continue;
                String category = item.databaseCategory == null ? item.getContentType().name() : item.databaseCategory;
                String tag = item.databaseTag == null ? "default" : item.databaseTag;
                // Establish first-seen category and tag order even where the first content is filtered out.
                var entries = groups.computeIfAbsent(category, key -> new LinkedHashMap<>())
                    .computeIfAbsent(tag, key -> new ArrayList<>());
                if (item.isHidden() || item.hideDatabase
                        || (tab != Planets.sun && !item.allDatabaseTabs && !item.databaseTabs.contains(tab))
                        || (!search.isEmpty() && !item.localizedName.toLowerCase(Locale.ROOT).contains(search))) continue;
                entries.add(new Entry(item, (!Vars.state.isCampaign() && !Vars.state.isMenu()) || item.unlocked()));
            }
        }

        var result = new ArrayList<Group>();
        groups.forEach((category, tags) -> tags.forEach((tag, entries) -> {
            if (!entries.isEmpty()) {
                if (Vars.state.isGame()) entries.sort(playingOrder);
                result.add(new Group(category, tag, entries));
            }
        }));
        return result;
    }
}
