import arc.graphics.g2d.TextureRegion;
import arc.util.Strings;
import java.util.ArrayList;
import java.util.List;
import mindustry.core.UI;
import mindustry.ctype.UnlockableContent;
import mindustry.game.MapObjectives.BuildCountObjective;
import mindustry.game.MapObjectives.CoreItemObjective;
import mindustry.game.MapObjectives.ItemObjective;
import mindustry.game.MapObjectives.MapObjective;
import mindustry.game.MapObjectives.ProduceObjective;
import mindustry.game.MapObjectives.ResearchObjective;
import mindustry.game.MapObjectives.UnitCountObjective;
import mindustry.game.Rules;

/** Adapts scene label markup and inline content glyphs for the 3DS objective panel. */
final class UpstreamObjectiveHud {
    record Row(String text, String contentName, TextureRegion icon) {}

    static {
        UI.loadColors();
    }

    static List<Row> collect(Rules rules) {
        var rows = new ArrayList<Row>();
        if (rules.mission != null && !rules.mission.isEmpty()) {
            rows.add(new Row(plain(rules.mission), "none", null));
            return rows;
        }
        for (var objective : rules.objectives) {
            // Match the pinned HudFragment's visibility and ordering.
            if (!objective.qualified() || objective.hidden) continue;
            String text = objective.text();
            if (text == null || text.isEmpty()) continue;
            var content = contentIcon(objective);
            if (content != null) {
                String glyph = content.emoji();
                if (!glyph.isEmpty()) text = text.replace(glyph, "");
                if (content.uiIcon == null) content.loadIcon();
            }
            String label = plain(text);
            if (!label.isEmpty()) {
                var icon = content == null ? null : content.uiIcon;
                rows.add(new Row(label, content == null ? "none" : content.name,
                    icon != null && icon.found() ? icon : null));
            }
        }
        return rows;
    }

    private static String plain(String text) {
        var plain = new StringBuilder();
        for (String line : Strings.stripColors(text).split("\n")) {
            if (plain.length() > 0) plain.append('\n');
            plain.append(line.strip());
        }
        return plain.toString();
    }

    private static UnlockableContent contentIcon(MapObjective objective) {
        if (objective instanceof ItemObjective item) return item.item;
        if (objective instanceof CoreItemObjective item) return item.item;
        if (objective instanceof BuildCountObjective build) return build.block;
        if (objective instanceof UnitCountObjective unit) return unit.unit;
        if (objective instanceof ResearchObjective research) return research.content;
        if (objective instanceof ProduceObjective produce) return produce.content;
        return null;
    }
}
