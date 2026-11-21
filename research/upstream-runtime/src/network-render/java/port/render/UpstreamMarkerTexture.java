package port.render;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import mindustry.ctype.UnlockableContent;
import mindustry.world.blocks.logic.CanvasBlock.CanvasBuild;
import mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild;

/** Supplies atlas regions for the pinned TextureMarker draw and update methods. */
public final class UpstreamMarkerTexture {
    public static boolean deferLookup(Object texture) {
        // Canvas and Logic Display need GPU resources outside the paged sprite atlas.
        return Core.atlas == null || texture instanceof CanvasBuild || texture instanceof LogicDisplayBuild;
    }

    public static void lookup(Object texture, TextureRegion target) {
        if (texture instanceof String name) {
            target.set(Core.atlas.find(name));
        } else if (texture instanceof UnlockableContent content) {
            if (content.fullIcon == null) content.loadIcon();
            target.set(content.fullIcon);
        } else {
            target.set(Core.atlas.find("error"));
        }
    }
}
