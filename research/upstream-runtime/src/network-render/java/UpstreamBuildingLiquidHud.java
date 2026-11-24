import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.core.UI;
import port.render.UpstreamLabelText;

import java.nio.charset.StandardCharsets;

/** Handheld view of the pinned dynamic Block.addLiquidBar entry. */
final class UpstreamBuildingLiquidHud {
    private static final float inset = 4f, width = 148f, height = 19f;
    private String label;
    private byte[] encodedLabel;
    private int[] encodedGlyphs;
    private int lastBuilding = -1;
    private float fraction;

    static boolean showsLiquid(Building building, Team viewer) {
        return building != null && building.team == viewer && building.liquids != null
            && building.block.hasDynamicLiquidBar && building.block.liquidCapacity > 0f;
    }

    float draw(UpstreamTerrainRenderProbe target, UpstreamLiveBuildControls controls, Team viewer, float y) {
        if (y + height > 236f) return y;
        var building = Vars.world.build(controls.cursorX(), controls.cursorY());
        if (!showsLiquid(building, viewer)) {
            lastBuilding = -1;
            return y;
        }

        String name = building.block.liquidBarLabel(building);
        if (!name.equals(label)) {
            label = name;
            var formatted = UpstreamLabelText.parse(UI.formatIcons(name));
            encodedLabel = (formatted.plain() + "\0").getBytes(StandardCharsets.UTF_8);
            encodedGlyphs = UpstreamHudLabels.encodeLabel(formatted);
        }

        float value = Mathf.clamp(building.block.liquidBarFraction(building));
        if (Float.isNaN(value)) value = 0f;
        if (lastBuilding != building.id()) {
            lastBuilding = building.id();
            fraction = value;
        } else {
            fraction = Mathf.lerpDelta(fraction, value, 0.15f);
        }
        Color color = building.block.liquidBarColor(building);
        UpstreamBuildingPowerHud.drawPatch(target, Core.atlas.find("bar"), y, width, 0xff191919);
        if (fraction > 0f) {
            UpstreamBuildingPowerHud.drawPatch(
                target, Core.atlas.find("bar-top"), y, width * fraction, color.abgr());
        }
        target.hudLabel(encodedLabel, encodedGlyphs, inset, y, width, height);
        return y + height + 3f;
    }
}
