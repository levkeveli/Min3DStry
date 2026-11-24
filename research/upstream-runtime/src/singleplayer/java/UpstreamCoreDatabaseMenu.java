import arc.Core;
import arc.graphics.g2d.TextureRegion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.core.UI;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Iconc;
import mindustry.world.meta.Stats;
import port.render.UpstreamLabelText;

/** Shows the stock database with the same handheld list controls as Custom Game. */
final class UpstreamCoreDatabaseMenu {
    private record Row(String label, UpstreamCoreDatabase.Entry entry) {}
    private record DrawRow(byte[] label, int[] characters, TextureRegion icon) {}
    private static final int visibleRows = 5;
    private static final int detailRows = 7;
    private final UpstreamCoreDatabase database = new UpstreamCoreDatabase();
    private final UpstreamTerrainRenderProbe target;

    UpstreamCoreDatabaseMenu(UpstreamTerrainRenderProbe target) {
        this.target = target;
    }

    boolean run() {
        var tabs = database.tabs();
        int tab = tabs.indexOf(database.initialTab(tabs));
        String query = "";
        var rows = rows(tabs.get(tab), query);
        int selected = 0, previousX = 0;
        var vertical = new VerticalNavigation();
        int renderedSelection = -1;
        List<DrawRow> visible = List.of();
        byte[] help = ("A  " + Core.bundle.get("info.title") + "\0X  " + Core.bundle.get("players.search")
            + "\0B  " + Core.bundle.get("back") + "\0")
            .getBytes(StandardCharsets.UTF_8);
        boolean shown = false;
        UpstreamSinglePlayerInput.poll();
        while (true) {
            long nextFrame = System.nanoTime() + 16_666_667L;
            int input = UpstreamSinglePlayerInput.poll();
            if (input < 0) return false;
            int touch = UpstreamTerrainRenderProbe.menuHelpAction(UpstreamSinglePlayerInput.touch(), 3);
            if ((input & UpstreamSinglePlayerInput.B_PRESSED) != 0 || touch == 2) {
                System.out.println("UPSTREAM_DATABASE_CLOSE");
                return true;
            }
            int x = (byte)input, y = (byte)(input >> 8);
            int direction = x > 60 && previousX <= 20 ? 1 : x < -60 && previousX >= -20 ? -1 : 0;
            if (direction != 0) {
                tab = Math.floorMod(tab + direction, tabs.size());
                rows = rows(tabs.get(tab), query);
                selected = 0;
                renderedSelection = -1;
                System.out.println("UPSTREAM_DATABASE_TAB tab=" + tabs.get(tab).name + " rows=" + rows.size());
            }
            if ((input & UpstreamSinglePlayerInput.X_PRESSED) != 0 || touch == 1) {
                var answer = UpstreamTerrainRenderProbe.readDatabaseSearch(query);
                if (answer != null) {
                    query = answer;
                    rows = rows(tabs.get(tab), query);
                    selected = 0;
                    renderedSelection = -1;
                }
            }
            selected = vertical.move(y, selected, rows.size() - 1);
            previousX = x;
            if (((input & UpstreamSinglePlayerInput.A_PRESSED) != 0 || touch == 0) && !rows.isEmpty()) {
                var row = rows.get(selected);
                if (row.entry() != null && row.entry().unlocked()) {
                    if (!details(row)) return false;
                    continue;
                }
            }
            if (selected != renderedSelection) {
                String title = Core.bundle.get("database") + " / " +
                    (tabs.get(tab) == Planets.sun ? Core.bundle.get("all") : tabs.get(tab).localizedName);
                visible = visibleRows(title, query, rows, selected);
                renderedSelection = selected;
            }
            draw(visible, 0, visible.size(), help, 3);
            if (!shown) {
                System.out.println("UPSTREAM_DATABASE_OPEN tab=" + tabs.get(tab).name + " rows=" + rows.size());
                shown = true;
            }
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    private List<Row> rows(UnlockableContent tab, String search) {
        var rows = new ArrayList<Row>();
        for (var group : database.groups(tab, search)) {
            String category = Core.bundle.get("database-category." + group.category(), group.category());
            String heading = group.tag().equals("default") ? category
                : category + " / " + Core.bundle.get("database-tag." + group.tag(), group.tag());
            rows.add(new Row(heading, null));
            for (var entry : group.entries()) {
                String name = entry.unlocked() ? entry.content().localizedName : Core.bundle.get("unlock.incampaign");
                rows.add(new Row(name, entry));
            }
        }
        return rows;
    }

    private boolean details(Row row) {
        var content = row.entry().content();
        var lines = new ArrayList<String>();
        var stats = content.computeStats();
        boolean hasStats = !stats.toMap().isEmpty();
        if (Vars.state.isGame() && Vars.state.data.isPatched(content)) {
            wrap(lines, Core.bundle.get("database.patched"));
        }
        if (content.description != null) {
            if (hasStats) lines.add(Core.bundle.get("category.purpose"));
            wrap(lines, UI.formatIcons(content.displayDescription()));
            if (hasStats && !stats.useCategories) lines.add(Core.bundle.get("category.general"));
        }
        addStats(lines, stats, content);
        if (content.details != null) {
            wrap(lines, content.unlocked() || !content.hideDetails ? UI.formatIcons(content.details)
                : Iconc.lock + " " + Core.bundle.get("unlock.incampaign"));
        }
        if (content.credit != null) wrap(lines, Core.bundle.format("content.createdby", content.credit));
        var display = new ArrayList<DrawRow>();
        display.add(encode(content.localizedName, icon(row.entry())));
        for (var line : lines) display.add(encode(line, null));
        int first = 0;
        var vertical = new VerticalNavigation();
        byte[] help = ("B  " + Core.bundle.get("back") + "\0").getBytes(StandardCharsets.UTF_8);
        boolean shown = false;
        UpstreamSinglePlayerInput.poll();
        while (true) {
            long nextFrame = System.nanoTime() + 16_666_667L;
            int input = UpstreamSinglePlayerInput.poll();
            if (input < 0) return false;
            if ((input & UpstreamSinglePlayerInput.B_PRESSED) != 0
                    || UpstreamTerrainRenderProbe.menuHelpAction(UpstreamSinglePlayerInput.touch(), 1) == 0) return true;
            int y = (byte)(input >> 8);
            first = vertical.move(y, first, lines.size() - detailRows);
            draw(display, first + 1, detailRows, help, 1);
            if (!shown) {
                System.out.println("UPSTREAM_DATABASE_DETAIL content=" + content.name + " rows=" + lines.size());
                shown = true;
            }
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    private static final class VerticalNavigation {
        private int direction;
        private long nextStep;

        int move(int input, int selected, int last) {
            int held = input > 60 ? -1 : input < -60 ? 1 : 0;
            long now = System.nanoTime();
            if (held == 0 || (held == direction && now < nextStep)) {
                direction = held;
                return selected;
            }
            nextStep = now + (held == direction ? 85_000_000L : 325_000_000L);
            direction = held;
            return Math.max(0, Math.min(last, selected + held));
        }
    }

    private static void addStats(List<String> lines, Stats stats, UnlockableContent content) {
        var categories = stats.toMap();
        for (var category : categories.keys()) {
            var entries = categories.get(category);
            boolean categoryShown = false;
            for (var stat : entries.keys()) {
                var text = new StringBuilder();
                for (var value : entries.get(stat)) {
                    String label = value.handheldText();
                    if (label == null) label = UpstreamCoreDatabaseStats.fallback(stat, content);
                    if (label == null || label.isEmpty()) continue;
                    if (text.length() > 0) text.append("  ");
                    text.append(label);
                }
                if (text.length() == 0) continue;
                if (stats.useCategories && !categoryShown) {
                    lines.add(category.localized());
                    categoryShown = true;
                }
                wrap(lines, stat.localized() + ": " + text);
            }
        }
    }

    private static void wrap(List<String> lines, String text) {
        for (String paragraph : arc.util.Strings.stripColors(text).split("\n", -1)) {
            var line = new StringBuilder();
            for (String word : paragraph.split("\\s+")) {
                if (line.length() > 0 && line.length() + word.length() + 1 > 38) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            lines.add(line.toString());
        }
    }

    private List<DrawRow> visibleRows(String title, String search, List<Row> rows, int selected) {
        var visible = new ArrayList<DrawRow>();
        visible.add(encode(title, null));
        if (!search.isEmpty()) visible.add(encode(Core.bundle.get("players.search") + "  " + search, null));
        if (rows.isEmpty()) {
            visible.add(encode(Core.bundle.get("none.found"), null));
        } else {
            int start = selected / visibleRows * visibleRows;
            for (int i = start; i < Math.min(start + visibleRows, rows.size()); i++) {
                var row = rows.get(i);
                visible.add(encode((i == selected ? "> " : "  ") + row.label(), icon(row.entry())));
            }
        }
        return visible;
    }

    private static TextureRegion icon(UpstreamCoreDatabase.Entry entry) {
        if (entry == null) return null;
        if (!entry.unlocked()) {
            var lock = Core.atlas.find("lock-small");
            return lock.found() ? lock : null;
        }
        var content = entry.content();
        if (content.uiIcon == null) content.loadIcon();
        return content.uiIcon != null && content.uiIcon.found() ? content.uiIcon : null;
    }

    private void draw(List<DrawRow> rows, int first, int count, byte[] help, int helpCount) {
        target.begin(0, 0, UpstreamTerrainDrawProbe.VIEW_WIDTH, UpstreamTerrainDrawProbe.VIEW_HEIGHT);
        try {
            float y = present(rows.get(0), 6f);
            for (int i = Math.max(1, first); i < Math.min(first + count, rows.size()); i++) y = present(rows.get(i), y);
            target.drawMenuHelp(help, helpCount);
        } finally {
            target.end();
        }
    }

    private float present(DrawRow row, float y) {
        return target.drawObjective(row.label(), row.characters(), row.icon(), y);
    }

    private static DrawRow encode(String text, TextureRegion icon) {
        var label = UpstreamLabelText.parse(text);
        return new DrawRow((label.plain() + "\0").getBytes(StandardCharsets.UTF_8),
            UpstreamHudLabels.encodeLabel(label), icon);
    }
}
