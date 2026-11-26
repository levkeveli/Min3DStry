import arc.Core;
import arc.graphics.Color;
import arc.struct.Seq;
import java.nio.charset.StandardCharsets;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.ui.Bar;
import port.render.UpstreamLabelText;

/** Displays the stock building-specific Bar providers in the handheld HUD. */
final class UpstreamBuildingSpecificBars {
    private static final float inset = 4f, width = 148f, height = 19f;
    private final Seq<Row> rows = new Seq<>();
    private final Color tint = new Color();
    private Building current;

    void clear() {
        current = null;
        rows.clear();
    }

    private void select(Building building) {
        clear();
        current = building;
        for (String key : building.block.barNames()) {
            if (commonBar(key)) continue;
            Bar bar = building.block.buildBar(key, building);
            if (bar != null && bar.hasValue()) rows.add(new Row(bar));
        }
    }

    private static boolean commonBar(String key) {
        return key.equals("health") || key.equals("power") || key.equals("items")
            || key.equals("liquid") || key.startsWith("liquid-");
    }

    float draw(UpstreamTerrainRenderProbe target, UpstreamLiveBuildControls controls, Team viewer, float y) {
        var building = Vars.world.build(controls.cursorX(), controls.cursorY());
        if (building == null || building.team != viewer) {
            clear();
            return y;
        }
        if (building != current) select(building);
        for (var row : rows) {
            if (y + height > 236f) break;
            row.bar.act(0f);
            row.bar.advanceValue();
            row.updateLabel();
            UpstreamBuildingPowerHud.drawPatch(target, Core.atlas.find("bar"), y, width, 0xff191919);
            float fill = width * row.bar.displayValue();
            if (fill > 0f) {
                var color = row.bar.displayColor(tint);
                UpstreamBuildingPowerHud.drawPatch(target, Core.atlas.find("bar-top"), y, fill, color.abgr());
            }
            target.hudLabel(row.encodedLabel, row.encodedGlyphs, inset, y, width, height);
            y += height + 3f;
        }
        return y;
    }

    private static final class Row {
        private final Bar bar;
        private String label;
        private byte[] encodedLabel;
        private int[] encodedGlyphs;

        private Row(Bar bar) {
            this.bar = bar;
        }

        private void updateLabel() {
            String name = bar.displayName().toString();
            if (name.equals(label)) return;
            label = name;
            var formatted = UpstreamLabelText.parse(UI.formatIcons(name));
            encodedLabel = (formatted.plain() + "\0").getBytes(StandardCharsets.UTF_8);
            encodedGlyphs = UpstreamHudLabels.encodeLabel(formatted);
        }
    }
}
