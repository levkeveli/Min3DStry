package port.render;

import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;

/** A batch that implements the pinned construction shaders without desktop GL. */
public interface UpstreamUnitBuildTarget {
    void drawUnitBuild(TextureRegion region, float x, float y, float rotation, float progress,
                       Color tint, float alpha, float phase);

    void drawBlockBuild(TextureRegion region, float x, float y, float rotation, float progress,
                        float alpha, float time);
}
