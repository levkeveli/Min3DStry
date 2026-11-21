package port.render;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import mindustry.ctype.UnlockableContent;
import mindustry.world.blocks.logic.CanvasBlock.CanvasBuild;
import mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild;

/** Supplies atlas regions for the pinned TextureMarker draw and update methods. */
public final class UpstreamMarkerTexture {
    public static boolean deferLookup(Object texture) {
        return Core.atlas == null || texture instanceof LogicDisplayBuild;
    }

    public static boolean prepareForDraw(Object texture) {
        if (deferLookup(texture)) return false;
        // Stationary canvases can be on a tile without joining Groups.build.
        // Refresh their pixels even when only a marker is in the viewport.
        if (texture instanceof CanvasBuild canvas && canvas.isValid()) canvas.updateTexture();
        return true;
    }

    public static void lookup(Object texture, TextureRegion target) {
        if (texture instanceof String name) {
            target.set(Core.atlas.find(name));
        } else if (texture instanceof UnlockableContent content) {
            if (content.fullIcon == null) content.loadIcon();
            target.set(content.fullIcon);
        } else if (texture instanceof CanvasBuild canvas && canvas.isValid()) {
            canvas.updateTexture();
            target.set(canvas.texture);
        } else {
            target.set(Core.atlas.find("error"));
        }
    }
}
