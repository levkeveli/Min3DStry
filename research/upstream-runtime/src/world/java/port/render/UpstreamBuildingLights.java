package port.render;

import arc.Core;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import mindustry.graphics.Layer;

/** Routes stock radial light requests during the handheld building pass. */
public final class UpstreamBuildingLights {
    private static boolean drawing;

    private UpstreamBuildingLights() {
    }

    public static void begin() {
        drawing = true;
    }

    public static void end() {
        drawing = false;
    }

    public static void radial(float x, float y, float radius, Color color, float opacity) {
        if (!drawing || radius <= 0f || opacity <= 0f) return;
        var region = Core.atlas.find("circle-shadow");
        if (!region.found()) return;
        float layer = Draw.z();
        Draw.z(Layer.effect);
        Draw.blend(Blending.additive);
        try {
            Draw.color(color, opacity);
            Draw.rect(region, x, y, radius * 2f, radius * 2f);
        } finally {
            Draw.blend();
            Draw.color();
            Draw.z(layer);
        }
    }
}
