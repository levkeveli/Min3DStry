package port.render;

import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import mindustry.Vars;
import mindustry.graphics.Layer;

/** Routes stock light requests during the handheld building pass. */
public final class UpstreamBuildingLights {
    private static boolean drawing;
    private static final UpstreamLightBuffer buffer = new UpstreamLightBuffer();
    private static boolean aliased;

    private UpstreamBuildingLights() {
    }

    public static void begin(int left, int bottom, int tilesW, int tilesH) {
        // The stock light pass only runs when lighting is enabled; ambient
        // darkness additionally decides whether the composite is visible.
        var rules = Vars.state.rules;
        drawing = rules.lighting && Vars.enableLight;
        aliased = drawing && rules.ambientLight.a > 0.0001f;
        if (aliased) buffer.begin(left, bottom, tilesW, tilesH);
    }

    public static void end() {
        if (!drawing) return;
        drawing = false;
        if (aliased) buffer.end(Vars.state.rules.ambientLight);
        aliased = false;
    }

    /** True while a light accumulation frame is open. */
    public static boolean active() {
        return drawing;
    }

    /** True when the stock light pass would accumulate this frame. */
    public static boolean enabled() {
        return Vars.state.rules.lighting && Vars.enableLight
            && Vars.state.rules.ambientLight.a > 0.0001f;
    }

    /** Test hook: light coverage at a world position after the last frame. */
    public static float coverageAt(float x, float y) {
        return buffer.coverageAt(x, y);
    }

    /** Test hook: highest coverage in a small neighborhood around a building. */
    public static float coverageNear(float x, float y) {
        float best = 0f;
        for (int dx = -16; dx <= 16; dx += 8) {
            for (int dy = -16; dy <= 16; dy += 8) {
                best = Math.max(best, buffer.coverageAt(x + dx, y + dy));
            }
        }
        return best;
    }

    public static void line(float x, float y, float x2, float y2, float stroke, Color tint, float alpha) {
        if (!drawing || alpha <= 0f || stroke <= 0f) return;
        if (aliased) buffer.addSegment(x, y, x2, y2, stroke, alpha);
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
        if (aliased) {
            buffer.addRect(x, y, region.width, region.height, opacity);
        }
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

    public static void radial(float x, float y, float radius, Color color, float opacity) {
        if (!drawing || radius <= 0f || opacity <= 0f) return;
        if (aliased) buffer.addRadial(x, y, radius, color, opacity);
        TextureRegion region = arc.Core.atlas.find("circle-shadow");
        if (!region.found()) return;
        float layer = Draw.z();
        Draw.z(Layer.effect);
        Draw.blend(Blending.additive);
        Draw.color(color, opacity);
        Draw.rect(region, x, y, radius * 2f, radius * 2f);
        Draw.blend();
        Draw.color();
        Draw.z(layer);
    }
}
