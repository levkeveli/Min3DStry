package port.render;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import mindustry.ctype.UnlockableContent;
import mindustry.game.MapObjectives.ObjectiveMarker;
import mindustry.world.blocks.logic.CanvasBlock.CanvasBuild;
import mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild;
import mindustry.world.blocks.logic.TileableLogicDisplay;
import mindustry.world.blocks.logic.TileableLogicDisplay.TileableLogicDisplayBuild;

/** Supplies atlas regions for the pinned TextureMarker draw and update methods. */
public final class UpstreamMarkerTexture {
    public static boolean deferLookup(Object texture) {
        return Core.atlas == null || (texture instanceof LogicDisplayBuild && Core.batch == null);
    }

    public static void prepare(ObjectiveMarker marker, Object texture, TextureRegion region, float layer) {
        if (texture instanceof LogicDisplayBuild display) {
            var root = displayRoot(display);
            if (root == null) {
                marker.setTexture("error");
                return;
            }
            // Tiled displays can replace their root and backing texture after a block update.
            if (root.buffer == null || root.buffer.isDisposed() || region.texture != root.buffer.getTexture()) {
                marker.setTexture(texture);
            }
            Draw.z(layer);
            root.processCommands();
        }
        // Stationary canvases can be on a tile without joining Groups.build.
        // Refresh their pixels even when only a marker is in the viewport.
        if (texture instanceof CanvasBuild canvas && canvas.isValid()) canvas.updateTexture();
    }

    public static void lookup(Object texture, TextureRegion target) {
        if (texture instanceof String name) {
            target.set(Core.atlas.find(name));
        } else if (texture instanceof UnlockableContent content) {
            if (content.fullIcon == null) content.loadIcon();
            target.set(content.fullIcon);
        } else if (texture instanceof LogicDisplayBuild display && displayRoot(display) != null) {
            var root = display.rootDisplay;
            if (root.buffer != null && root.buffer.isDisposed()) root.buffer = null;
            root.ensureBuffer();
            root.getBufferRegion(target);
        } else if (texture instanceof CanvasBuild canvas && canvas.isValid()) {
            canvas.updateTexture();
            target.set(canvas.texture);
        } else {
            target.set(Core.atlas.find("error"));
        }
    }

    private static LogicDisplayBuild displayRoot(LogicDisplayBuild display) {
        if (!display.isAdded() || !display.isValid()) return null;
        var root = display.rootDisplay;
        if (root instanceof TileableLogicDisplayBuild tiled) {
            int limit = ((TileableLogicDisplay)tiled.block).maxDisplayDimensions;
            if (tiled.tilesWidth > limit || tiled.tilesHeight > limit) return null;
        }
        return root;
    }
}
