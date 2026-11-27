package port.render;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.Seq;

/** Collects the stock world light requests for the handheld render target. */
public final class UpstreamBuildingLights {
    private static final float[] segment = new float[24];
    private static final Vec2 perpendicular = new Vec2();
    private static final Vec2 axis = new Vec2();
    private static final Seq<Runnable> shapes = new Seq<>();
    private static final Seq<CircleLight> circles = new Seq<>();
    private static int circleCount;
    private static boolean drawing;
    private static boolean frame;
    private static boolean collecting;

    private static final class CircleLight {
        float x, y, radius, color;
    }

    private UpstreamBuildingLights() {
    }

    public static void begin() {
        drawing = true;
    }

    public static void end() {
        drawing = false;
    }

    public static void beginFrame(boolean enabled) {
        clearFrame();
        frame = true;
        collecting = enabled;
    }

    public static void defer(Runnable draw) {
        if (collecting) shapes.add(draw);
    }

    public static void flushFrame() {
        if (!collecting) return;
        Draw.reset();
        try {
            // Marker callbacks can enqueue a segment after their own geometry.
            for (int i = 0; i < shapes.size; i++) shapes.get(i).run();
            for (int i = 0; i < circleCount; i++) {
                CircleLight light = circles.get(i);
                drawCircle(light.x, light.y, light.radius, light.color);
            }
            Draw.flush();
        } finally {
            collecting = false;
            Draw.reset();
        }
    }

    public static void clearFrame() {
        shapes.clear();
        circleCount = 0;
        collecting = false;
        frame = false;
    }

    public static void line(float x, float y, float x2, float y2, float stroke, Color tint, float alpha) {
        if (alpha <= 0f || stroke <= 0f || (!collecting && (frame || !drawing))) return;
        float packed = Color.toFloatBits(tint.r, tint.g, tint.b, alpha);
        if (collecting) shapes.add(() -> drawLine(x, y, x2, y2, stroke, packed));
        else drawLine(x, y, x2, y2, stroke, packed);
    }

    private static void drawLine(float x, float y, float x2, float y2, float stroke, float packed) {
        TextureRegion middle = Core.atlas.find("circle-mid"), end = Core.atlas.find("circle-end");
        if (!middle.found() || !end.found()) return;
        try {
            Draw.color(packed);
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
            Draw.color();
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
        if (region == null || !region.found() || opacity <= 0f || (!collecting && (frame || !drawing))) return;
        float packed = color.toFloatBits();
        float xscl = Draw.xscl, yscl = Draw.yscl;
        if (collecting) shapes.add(() -> drawTexture(x, y, region, rotation, packed, opacity, xscl, yscl));
        else drawTexture(x, y, region, rotation, packed, opacity, xscl, yscl);
    }

    private static void drawTexture(float x, float y, TextureRegion region, float rotation,
                                    float packed, float opacity, float xscl, float yscl) {
        Draw.color(packed);
        Draw.alpha(opacity);
        Draw.scl(xscl, yscl);
        try {
            Draw.rect(region, x, y, rotation);
        } finally {
            Draw.scl();
            Draw.color();
        }
    }

    public static void radial(float x, float y, float radius, Color color, float opacity) {
        if (radius <= 0f || opacity <= 0f || (!collecting && (frame || !drawing))) return;
        float packed = Color.toFloatBits(color.r, color.g, color.b, opacity);
        if (collecting) {
            if (circleCount == circles.size) circles.add(new CircleLight());
            CircleLight light = circles.get(circleCount++);
            light.x = x;
            light.y = y;
            light.radius = radius;
            light.color = packed;
        } else {
            drawCircle(x, y, radius, packed);
        }
    }

    private static void drawCircle(float x, float y, float radius, float packed) {
        var region = Core.atlas.find("circle-shadow");
        if (!region.found()) return;
        try {
            Draw.color(packed);
            Draw.rect(region, x, y, radius * 2f, radius * 2f);
        } finally {
            Draw.color();
        }
    }
}
