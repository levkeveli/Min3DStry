package port.render;

import arc.graphics.g2d.Draw;
import arc.util.Strings;
import mindustry.core.UI;
import mindustry.gen.WorldLabel;

/** Citro2D entry point for the pinned WorldLabel call made by objective markers. */
public final class UpstreamWorldLabel {
    @FunctionalInterface
    public interface Target {
        void draw(String text, float x, float y, int flags, float size, int align, int lineAlign);
    }

    private static Target target;
    private static float displayScale;

    static {
        UI.loadColors();
    }

    public static void begin(Target target, float displayScale) {
        UpstreamWorldLabel.target = target;
        UpstreamWorldLabel.displayScale = displayScale;
    }

    public static void end() {
        target = null;
    }

    public static void drawAt(String text, float x, float y, float layer, int flags, float size, int align, int lineAlign) {
        if (text == null || target == null) return;
        float zoom = (flags & WorldLabel.flagAutoscale) != 0 ? 0.2f * displayScale + 0.05f : 1f;
        // Adapt Arc color markup and its private-use glyphs to the 3DS system font.
        String plain = Strings.stripColors(text).replace("[[", "[");
        var label = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char glyph = plain.charAt(i);
            if (glyph >= 0xE000 && glyph <= 0xF8FF) {
                if (i + 1 < plain.length() && plain.charAt(i + 1) == ' ') i++;
            } else {
                label.append(glyph);
            }
        }
        if (label.isEmpty()) return;
        String rendered = label.toString();
        float depth = Draw.z();
        Draw.draw(layer, () -> target.draw(rendered, x, y, flags, size / zoom, align, lineAlign));
        Draw.z(depth);
    }
}
