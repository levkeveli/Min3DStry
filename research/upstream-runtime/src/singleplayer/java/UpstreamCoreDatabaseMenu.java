import arc.Core;
import arc.graphics.g2d.TextureRegion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import mindustry.content.Planets;
import mindustry.ctype.UnlockableContent;

/** Shows the stock database with the same handheld list controls as Custom Game. */
final class UpstreamCoreDatabaseMenu {
    private record Row(String label, UpstreamCoreDatabase.Entry entry) {}
    private record DrawRow(byte[] label, int[] characters, TextureRegion icon) {}
    private static final int visibleRows = 5;
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
        int selected = 0, previousX = 0, previousY = 0;
        int renderedSelection = -1;
        List<DrawRow> visible = List.of();
        UpstreamSinglePlayerInput.poll();
        while (true) {
            long nextFrame = System.nanoTime() + 16_666_667L;
            int input = UpstreamSinglePlayerInput.poll();
            if (input < 0) return false;
            if ((input & UpstreamSinglePlayerInput.B_PRESSED) != 0) return true;
            int x = (byte)input, y = (byte)(input >> 8);
            int direction = x > 60 && previousX <= 20 ? 1 : x < -60 && previousX >= -20 ? -1 : 0;
            if (direction != 0) {
                tab = Math.floorMod(tab + direction, tabs.size());
                rows = rows(tabs.get(tab), query);
                selected = 0;
                renderedSelection = -1;
            }
            if ((input & UpstreamSinglePlayerInput.X_PRESSED) != 0) {
                var answer = UpstreamTerrainRenderProbe.readDatabaseSearch(query);
                if (answer != null) {
                    query = answer;
                    rows = rows(tabs.get(tab), query);
                    selected = 0;
                    renderedSelection = -1;
                }
            }
            if (y > 60 && previousY <= 20) selected = Math.max(0, selected - 1);
            if (y < -60 && previousY >= -20) selected = Math.min(Math.max(0, rows.size() - 1), selected + 1);
            previousX = x;
            previousY = y;
            if ((input & UpstreamSinglePlayerInput.A_PRESSED) != 0 && !rows.isEmpty()) {
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
            draw(visible, 0, visible.size());
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
        if (content.description != null) wrap(lines, content.displayDescription());
        if (content.details != null) {
            wrap(lines, content.unlocked() || !content.hideDetails ? content.details : Core.bundle.get("unlock.incampaign"));
        }
        if (content.credit != null) wrap(lines, Core.bundle.format("content.createdby", content.credit));
        var display = new ArrayList<DrawRow>();
        display.add(encode(content.localizedName, icon(row.entry())));
        for (var line : lines) display.add(encode(line, null));
        int first = 0, previousY = 0;
        UpstreamSinglePlayerInput.poll();
        while (true) {
            long nextFrame = System.nanoTime() + 16_666_667L;
            int input = UpstreamSinglePlayerInput.poll();
            if (input < 0) return false;
            if ((input & UpstreamSinglePlayerInput.B_PRESSED) != 0) return true;
            int y = (byte)(input >> 8);
            if (y > 60 && previousY <= 20) first = Math.max(0, first - 1);
            if (y < -60 && previousY >= -20) first = Math.min(Math.max(0, lines.size() - 7), first + 1);
            previousY = y;

            draw(display, first + 1, 7);
            while (System.nanoTime() < nextFrame) Thread.yield();
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
        visible.add(encode("X  " + Core.bundle.get("players.search") + (search.isEmpty() ? "" : "  " + search), null));
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

    private void draw(List<DrawRow> rows, int first, int count) {
        target.begin(0, 0, UpstreamTerrainDrawProbe.VIEW_WIDTH, UpstreamTerrainDrawProbe.VIEW_HEIGHT);
        try {
            float y = present(rows.get(0), 6f);
            for (int i = Math.max(1, first); i < Math.min(first + count, rows.size()); i++) y = present(rows.get(i), y);
        } finally {
            target.end();
        }
    }

    private float present(DrawRow row, float y) {
        return target.drawObjective(row.label(), row.characters(), row.icon(), y);
    }

    private static DrawRow encode(String text, TextureRegion icon) {
        String label = arc.util.Strings.stripColors(text);
        return new DrawRow((label + "\0").getBytes(StandardCharsets.UTF_8),
            UpstreamSinglePlayerApp.encodePlainMenuLabel(label), icon);
    }
}
