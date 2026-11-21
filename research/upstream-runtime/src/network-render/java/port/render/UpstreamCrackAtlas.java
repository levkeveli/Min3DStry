package port.render;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import mindustry.graphics.BlockRenderer;

/** Supplies the same atlas regions as BlockRenderer's ClientLoadEvent listener. */
public final class UpstreamCrackAtlas {
    private static TextureRegion[][] regions;

    public static void load() {
        var textures = new TextureRegion[BlockRenderer.maxCrackSize][BlockRenderer.crackRegions];
        for (int size = 1; size <= textures.length; size++) {
            for (int frame = 0; frame < textures[size - 1].length; frame++) {
                String name = "cracks-" + size + "-" + frame;
                var region = Core.atlas.find(name);
                if (!region.found()) throw new IllegalStateException("Missing upstream crack sprite " + name);
                textures[size - 1][frame] = region;
            }
        }
        regions = textures;
    }

    public static TextureRegion[][] regions() {
        return regions;
    }
}
