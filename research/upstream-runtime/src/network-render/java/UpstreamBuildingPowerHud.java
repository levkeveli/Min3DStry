import arc.Core;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.math.Mathf;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.core.UI;
import port.render.UpstreamLabelText;

import java.nio.charset.StandardCharsets;

/** Handheld view of the pinned Block.setBars power entry. */
final class UpstreamBuildingPowerHud {
    private static final float width = 148f, height = 19f;
    private String label;
    private byte[] encodedLabel;
    private int[] encodedGlyphs;
    private int lastBuilding = -1;
    private float fraction;

    static boolean showsPower(Building building, Team viewer) {
        return building != null && building.team == viewer && building.power != null
            && building.block.consPower != null;
    }

    float draw(UpstreamTerrainRenderProbe target, UpstreamLiveBuildControls controls, Team viewer, float y) {
        if (y + height > 236f) return y;
        var building = Vars.world.build(controls.cursorX(), controls.cursorY());
        if (!showsPower(building, viewer)) {
            lastBuilding = -1;
            return y;
        }

        String name = building.block.powerBarLabel(building);
        if (!name.equals(label)) {
            label = name;
            encodedLabel = (name + "\0").getBytes(StandardCharsets.UTF_8);
            encodedGlyphs = UpstreamHudLabels.encodeLabel(UpstreamLabelText.parse(UI.formatIcons(name)));
        }

        float value = Mathf.clamp(building.block.powerBarFraction(building));
        if (Float.isNaN(value)) value = 0f;
        if (lastBuilding != building.id()) {
            lastBuilding = building.id();
            fraction = value;
        } else {
            fraction = Mathf.lerpDelta(fraction, value, 0.15f);
        }
        drawPatch(target, Core.atlas.find("bar"), y, width, 0xff191919);
        if (fraction > 0f) drawPatch(target, Core.atlas.find("bar-top"), y, width * fraction, Pal.powerBar.abgr());
        target.hudLabel(encodedLabel, encodedGlyphs, 2f, y, width, height);
        return y + height + 3f;
    }

    private static void drawPatch(UpstreamTerrainRenderProbe target, AtlasRegion sprite, float y, float fill, int tint) {
        if (!sprite.found() || sprite.splits == null) return;
        int left = sprite.splits[0], right = sprite.splits[1], top = sprite.splits[2], bottom = sprite.splits[3];
        float scale = height / sprite.height;
        float drawnWidth = Math.max(sprite.width * scale, fill);
        for (int row = 0; row < 3; row++) {
            int sy = row == 0 ? 0 : row == 1 ? top : sprite.height - bottom;
            int sh = row == 0 ? top : row == 1 ? sprite.height - top - bottom : bottom;
            for (int column = 0; column < 3; column++) {
                int sx = column == 0 ? 0 : column == 1 ? left : sprite.width - right;
                int sw = column == 0 ? left : column == 1 ? sprite.width - left - right : right;
                float x = column == 0 ? 0f : column == 1 ? left * scale : drawnWidth - right * scale;
                float dw = column == 1 ? drawnWidth - (left + right) * scale : sw * scale;
                float clipped = Math.min(dw, fill - x);
                if (clipped <= 0f || sh <= 0 || sw <= 0) continue;
                int pixels = Math.min(sw, (int)Math.ceil(sw * clipped / dw));
                target.hudSprite(sprite, sx, sy, pixels, sh, 2f + x, y + sy * scale, clipped, sh * scale, tint);
            }
        }
    }
}
