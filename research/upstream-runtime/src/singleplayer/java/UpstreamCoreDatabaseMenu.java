import arc.Core;
import arc.graphics.g2d.TextureRegion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import mindustry.content.Planets;
import mindustry.ctype.UnlockableContent;

/** Shows the stock database with the same handheld list controls as Custom Game. */
final class UpstreamCoreDatabaseMenu {
    private record Row(String label, TextureRegion icon, UpstreamCoreDatabase.Entry entry) {}
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
            }
            if ((input & UpstreamSinglePlayerInput.X_PRESSED) != 0) {
                var answer = UpstreamTerrainRenderProbe.readDatabaseSearch(query);
                if (answer != null) {
                    query = answer;
                    rows = rows(tabs.get(tab), query);
                    selected = 0;
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
            String title = Core.bundle.get("database") + " / " +
                (tabs.get(tab) == Planets.sun ? Core.bundle.get("all") : tabs.get(tab).localizedName);
            draw(title, query, rows, selected);
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    private List<Row> rows(UnlockableContent tab, String search) {
        var rows = new ArrayList<Row>();
        for (var group : database.groups(tab, search)) {
            String category = Core.bundle.get("database-category." + group.category(), group.category());
            String heading = group.tag().equals("default") ? category
                : category + " / " + Core.bundle.get("database-tag." + group.tag(), group.tag());
            rows.add(new Row(heading, null, null));
            for (var entry : group.entries()) {
                TextureRegion icon = null;
                String name = Core.bundle.get("unlock.incampaign");
                if (entry.unlocked()) {
                    var content = entry.content();
                    name = content.localizedName;
                    if (content.uiIcon == null) content.loadIcon();
                    if (content.uiIcon != null && content.uiIcon.found()) icon = content.uiIcon;
                } else {
                    var lock = Core.atlas.find("lock-small");
                    if (lock.found()) icon = lock;
                }
                rows.add(new Row(name, icon, entry));
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

            target.begin(0, 0, UpstreamTerrainDrawProbe.VIEW_WIDTH, UpstreamTerrainDrawProbe.VIEW_HEIGHT);
            try {
                float top = drawRow(content.localizedName, row.icon(), 6f);
                for (int i = first; i < Math.min(first + 7, lines.size()); i++) {
                    top = drawRow(lines.get(i), null, top);
                }
            } finally {
                target.end();
            }
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

    private void draw(String title, String search, List<Row> rows, int selected) {
        target.begin(0, 0, UpstreamTerrainDrawProbe.VIEW_WIDTH, UpstreamTerrainDrawProbe.VIEW_HEIGHT);
        try {
            float y = drawRow(title, null, 6f);
            y = drawRow("X  " + Core.bundle.get("players.search") + (search.isEmpty() ? "" : "  " + search), null, y);
            if (rows.isEmpty()) {
                drawRow(Core.bundle.get("none.found"), null, y);
            } else {
                int start = selected / visibleRows * visibleRows;
                for (int i = start; i < Math.min(start + visibleRows, rows.size()); i++) {
                    var row = rows.get(i);
                    y = drawRow((selected == i ? "> " : "  ") + row.label(), row.icon(), y);
                }
            }
        } finally {
            target.end();
        }
    }

    private float drawRow(String text, TextureRegion icon, float y) {
        String label = arc.util.Strings.stripColors(text);
        return target.drawObjective((label + "\0").getBytes(StandardCharsets.UTF_8),
            UpstreamSinglePlayerApp.encodePlainMenuLabel(label), icon, y);
    }
}
