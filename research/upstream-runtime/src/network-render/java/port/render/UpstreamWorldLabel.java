package port.render;

import arc.graphics.g2d.Draw;
import mindustry.gen.WorldLabel;

/** Citro2D entry point for the pinned WorldLabel call made by objective markers. */
public final class UpstreamWorldLabel {
    @FunctionalInterface
    public interface Target {
        void draw(UpstreamLabelText.Label text, float x, float y, int flags, float size, int align, int lineAlign);
    }

    private static Target target;
    private static float displayScale;

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
        var rendered = UpstreamLabelText.parse(text);
        if (rendered.characters().isEmpty()) return;
        float depth = Draw.z();
        Draw.draw(layer, () -> target.draw(rendered, x, y, flags, size / zoom, align, lineAlign));
        Draw.z(depth);
    }
}
