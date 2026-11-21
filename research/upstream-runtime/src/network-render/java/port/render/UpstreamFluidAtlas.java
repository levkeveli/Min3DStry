package port.render;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import mindustry.type.Liquid;

/** Atlas frames used by the upstream Renderer for liquid and gas animation. */
public final class UpstreamFluidAtlas {
    private static TextureRegion[][] frames;

    public static void load() {
        String[] types = {"liquid", "gas"};
        var textures = new TextureRegion[types.length][Liquid.animationFrames];
        for (int type = 0; type < types.length; type++) {
            for (int frame = 0; frame < Liquid.animationFrames; frame++) {
                String name = "fluid-" + types[type] + "-" + frame;
                var region = Core.atlas.find(name);
                if (!region.found()) throw new IllegalStateException("Missing upstream fluid sprite " + name);
                textures[type][frame] = region;
            }
        }
        frames = textures;
    }

    public static boolean ready() {
        return frames != null;
    }

    public static TextureRegion[][] frames() {
        return frames;
    }
}
