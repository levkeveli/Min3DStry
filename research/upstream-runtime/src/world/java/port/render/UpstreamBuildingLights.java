package port.render;

import arc.Core;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import mindustry.graphics.Layer;

/** Routes stock radial light requests during the handheld building pass. */
public final class UpstreamBuildingLights {
    private static final float[] segment = new float[24];
    private static final Vec2 perpendicular = new Vec2();
    private static final Vec2 axis = new Vec2();
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
        TextureRegion middle = Core.atlas.find("circle-mid"), end = Core.atlas.find("circle-end");
        if (!middle.found() || !end.found()) return;
        float layer = Draw.z();
        Draw.z(Layer.effect);
        Draw.blend(Blending.additive);
        try {
            Draw.color(tint, alpha);
            // The pinned LightRenderer builds a textured strip and two caps.
            float rotation = Mathf.angleExact(x2 - x, y2 - y);
            perpendicular.trnsExact(rotation + 90f, stroke);
            axis.trnsExact(rotation, stroke);
            float lx1 = x - perpendicular.x, ly1 = y - perpendicular.y;
            float lx2 = x + perpendicular.x, ly2 = y + perpendicular.y;
            float lx3 = x2 + perpendicular.x, ly3 = y2 + perpendicular.y;
            float lx4 = x2 - perpendicular.x, ly4 = y2 - perpendicular.y;
            quad(middle, lx1, ly1, lx2, ly2, lx3, ly3, lx4, ly4);
            quad(end, lx4, ly4, lx3, ly3, lx3 + axis.x, ly3 + axis.y, lx4 + axis.x, ly4 + axis.y);
            quad(end, lx2, ly2, lx1, ly1, lx1 - axis.x, ly1 - axis.y, lx2 - axis.x, ly2 - axis.y);
        } finally {
            Draw.blend();
            Draw.color();
            Draw.z(layer);
        }
    }

    private static void quad(TextureRegion region, float x1, float y1, float x2, float y2,
                             float x3, float y3, float x4, float y4) {
        float color = Draw.getColorPacked();
        vertex(0, x1, y1, color, region.u, region.v2);
        vertex(6, x2, y2, color, region.u, region.v);
        vertex(12, x3, y3, color, region.u2, region.v);
        vertex(18, x4, y4, color, region.u2, region.v2);
        Draw.vert(region.texture, segment, 0, segment.length);
    }

    private static void vertex(int offset, float x, float y, float color, float u, float v) {
        segment[offset] = x;
        segment[offset + 1] = y;
        segment[offset + 2] = color;
        segment[offset + 3] = u;
        segment[offset + 4] = v;
        segment[offset + 5] = 0f;
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
