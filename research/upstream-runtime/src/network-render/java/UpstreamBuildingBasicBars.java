import arc.Core;
import arc.math.Mathf;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.core.UI;
import port.render.UpstreamLabelText;

import java.nio.charset.StandardCharsets;

/** Handheld view of the pinned Block.setBars health and items entries. */
final class UpstreamBuildingBasicBars {
    private static final float inset = 4f, width = 148f, height = 19f;
    private final BarState health = new BarState();
    private final BarState items = new BarState();

    static boolean showsHealth(Building building, Team viewer) {
        return building != null && building.team == viewer && building.block.health > 0f;
    }

    static boolean showsItems(Building building, Team viewer) {
        return building != null && building.team == viewer && building.items != null
            && building.block.hasItems && building.block.configurable && building.block.itemCapacity > 0;
    }

    static float healthFraction(Building building) {
        return building.healthf();
    }

    static String healthLabel() {
        return Core.bundle.get("stat.health");
    }

    static float itemFraction(Building building) {
        return building.items.total() / (float)building.block.itemCapacity;
    }

    static String itemLabel(Building building) {
        return Core.bundle.format("bar.items", building.items.total());
    }

    float drawHealth(UpstreamTerrainRenderProbe target, UpstreamLiveBuildControls controls, Team viewer, float y) {
        return draw(target, controls, viewer, y, true, health);
    }

    float drawItems(UpstreamTerrainRenderProbe target, UpstreamLiveBuildControls controls, Team viewer, float y) {
        return draw(target, controls, viewer, y, false, items);
    }

    private float draw(UpstreamTerrainRenderProbe target, UpstreamLiveBuildControls controls, Team viewer,
                       float y, boolean isHealth, BarState state) {
        if (y + height > 236f) return y;
        var building = Vars.world.build(controls.cursorX(), controls.cursorY());
        if (isHealth ? !showsHealth(building, viewer) : !showsItems(building, viewer)) {
            state.building = -1;
            return y;
        }

        String name = isHealth ? healthLabel() : itemLabel(building);
        if (!name.equals(state.label)) {
            state.label = name;
            var formatted = UpstreamLabelText.parse(UI.formatIcons(name));
            state.encodedLabel = (formatted.plain() + "\0").getBytes(StandardCharsets.UTF_8);
            state.encodedGlyphs = UpstreamHudLabels.encodeLabel(formatted);
        }

        float value = Mathf.clamp(isHealth ? healthFraction(building) : itemFraction(building));
        if (Float.isNaN(value)) value = 0f;
        if (state.building != building.id()) {
            state.building = building.id();
            state.fraction = value;
        } else {
            state.fraction = Mathf.lerpDelta(state.fraction, value, 0.15f);
        }
        int tint = (isHealth ? Pal.health : Pal.items).abgr();
        UpstreamBuildingPowerHud.drawPatch(target, Core.atlas.find("bar"), y, width, 0xff191919);
        if (state.fraction > 0f) {
            UpstreamBuildingPowerHud.drawPatch(
                target, Core.atlas.find("bar-top"), y, width * state.fraction, tint);
        }
        target.hudLabel(state.encodedLabel, state.encodedGlyphs, inset, y, width, height);
        return y + height + 3f;
    }

    private static final class BarState {
        private int building = -1;
        private String label;
        private byte[] encodedLabel;
        private int[] encodedGlyphs;
        private float fraction;
    }
}
