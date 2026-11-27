package port.render;

import arc.Core;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
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

    public static void line(float x, float y, float x2, float y2, float stroke, Color tint, float alpha) {
        if (!drawing || alpha <= 0f || stroke <= 0f) return;
        float layer = Draw.z();
        Draw.z(Layer.effect);
        Draw.blend(Blending.additive);
        try {
            Draw.color(tint, alpha);
            Lines.stroke(stroke);
            Lines.line(x, y, x2, y2);
        } finally {
            Draw.blend();
            Draw.color();
            Draw.z(layer);
        }
    }

    public static void textured(float x, float y, TextureRegion region, float rotation, Color color, float opacity) {
        if (!drawing || region == null || !region.found() || opacity <= 0f) return;
        float layer = Draw.z();
        Draw.z(Layer.effect);
        Draw.blend(Blending.additive);
        try {
            Draw.color(color, opacity);
            Draw.rect(region, x, y, rotation);
        } finally {
            Draw.blend();
            Draw.color();
            Draw.z(layer);
        }
    }

    public static void radial(float x, float y, float radius, Color color, float opacity) {        if (!drawing || radius <= 0f || opacity <= 0f) return;
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
