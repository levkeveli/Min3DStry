import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.core.UI;
import port.render.UpstreamLabelText;

import java.nio.charset.StandardCharsets;

/** Handheld view of the pinned Block.addLiquidBar entries. */
final class UpstreamBuildingLiquidHud {
    private static final float inset = 4f, width = 148f, height = 19f;
    private String[] labels = new String[0];
    private byte[][] encodedLabels = new byte[0][];
    private int[][] encodedGlyphs = new int[0][];
    private float[] fractions = new float[0];
    private int lastBuilding = -1;
    private int lastCount = -1;

    static boolean showsLiquid(Building building, Team viewer) {
        return building != null && building.team == viewer && building.liquids != null
            && building.block.liquidBarCount() > 0;
    }

    float draw(UpstreamTerrainRenderProbe target, UpstreamLiveBuildControls controls, Team viewer, float y) {
        if (y + height > 236f) return y;
        var building = Vars.world.build(controls.cursorX(), controls.cursorY());
        if (!showsLiquid(building, viewer)) {
            lastBuilding = -1;
            return y;
        }

        int count = building.block.liquidBarCount();
        boolean fresh = building.id() != lastBuilding || count != lastCount;
        if (fresh) {
            lastBuilding = building.id();
            lastCount = count;
            labels = new String[count];
            encodedLabels = new byte[count][];
            encodedGlyphs = new int[count][];
            fractions = new float[count];
        }

        for (int i = 0; i < count; i++) {
            if (y + height > 236f) break;
            String name = building.block.liquidBarLabel(building, i);
            if (!name.equals(labels[i])) {
                labels[i] = name;
                var formatted = UpstreamLabelText.parse(UI.formatIcons(name));
                encodedLabels[i] = (formatted.plain() + "\0").getBytes(StandardCharsets.UTF_8);
                encodedGlyphs[i] = UpstreamHudLabels.encodeLabel(formatted);
            }

            float value = Mathf.clamp(building.block.liquidBarFraction(building, i));
            if (Float.isNaN(value)) value = 0f;
            fractions[i] = fresh ? value : Mathf.lerpDelta(fractions[i], value, 0.15f);

            Color color = building.block.liquidBarColor(building, i);
            UpstreamBuildingPowerHud.drawPatch(target, Core.atlas.find("bar"), y, width, 0xff191919);
            if (fractions[i] > 0f) {
                UpstreamBuildingPowerHud.drawPatch(
                    target, Core.atlas.find("bar-top"), y, width * fractions[i], color.abgr());
            }
            target.hudLabel(encodedLabels[i], encodedGlyphs[i], inset, y, width, height);
            y += height + 3f;
        }
        return y;
    }
}
