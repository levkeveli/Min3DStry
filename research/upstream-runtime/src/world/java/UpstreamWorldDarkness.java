import arc.math.geom.Rect;
import mindustry.core.World;
import mindustry.game.Rules;

/** Visible texels of the pinned v160.8 BlockRenderer darkness framebuffer. */
final class UpstreamWorldDarkness {
    private UpstreamWorldDarkness() {
    }

    static boolean sample(World world, Rules rules, int left, int bottom, int width, int height, byte[] alpha) {
        boolean visible = false;
        int offset = 0;
        // One texel outside each edge preserves linear interpolation at the view boundary.
        for (int row = height; row >= -1; row--) {
            int y = Math.max(0, Math.min(world.height() - 1, bottom + row));
            for (int col = -1; col <= width; col++) {
                int x = Math.max(0, Math.min(world.width() - 1, left + col));
                int shade = alpha(world, rules, x, y);
                alpha[offset++] = (byte) shade;
                visible |= shade != 0;
            }
        }
        return visible;
    }

    static int alpha(World world, Rules rules, int x, int y) {
        if (rules.limitMapArea && !Rect.contains(rules.limitX, rules.limitY, rules.limitWidth - 1,
                rules.limitHeight - 1, x, y)) return 255;
        float darkness = world.getDarkness(x, y);
        if (darkness <= 0f) return 0;

        // BlockRenderer writes brightness to its RGBA8 texture. darkness.frag inverts red into alpha.
        float brightness = 1f - Math.min((darkness + 0.5f) / 4f, 1f);
        return 255 - Math.round(255f * brightness);
    }
}
