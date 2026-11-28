package port.render;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import mindustry.graphics.Layer;
import port.render.UpstreamLogicFramebuffer.RasterTexture;

/**
 * Software light buffer for the handheld world pass.
 *
 * The desktop Renderer accumulates light coverage into a low-resolution target
 * and composites it with the map ambient light. The handheld renderer has no
 * fragment shader for that composite, so the same low-resolution coverage is
 * rasterized here from the stock circle-shadow falloff and uploaded as a
 * texture: transparent inside lights, darkened toward the ambient colour
 * outside. Existing additive light sprites still provide the light colour.
 */
public final class UpstreamLightBuffer {
    private static final int SCALE = 4;
    /** Radial alpha of the stock circle-shadow sprite, sampled from the pinned asset. */
    private static final int[] FALLOFF = {
        255,255,255,255,255,255,254,254,253,253,252,251,250,249,247,245,244,242,
        240,238,236,233,229,225,221,218,214,210,205,202,196,190,186,176,171,164,
        158,150,142,135,126,119,106,100,92,84,78,70,65,58,54,44,39,35,29,26,21,
        18,14,11,8,5,4,2,1
    };

    private RasterTexture texture;
    private TextureRegion region;
    private float[] coverage;
    private int width, height;
    private int left, bottom, tilesW, tilesH;
    private boolean active;

    public void begin(int left, int bottom, int tilesW, int tilesH) {
        this.left = left;
        this.bottom = bottom;
        this.tilesW = Math.max(1, tilesW);
        this.tilesH = Math.max(1, tilesH);
        int w = Math.max(1, (this.tilesW * 8 + SCALE - 1) / SCALE);
        int h = Math.max(1, (this.tilesH * 8 + SCALE - 1) / SCALE);
        if (texture == null || width != w || height != h) {
            if (texture != null) texture.dispose();
            texture = new RasterTexture(w, h);
            region = new TextureRegion(texture);
            coverage = new float[w * h];
            width = w;
            height = h;
        }
        java.util.Arrays.fill(coverage, 0f);
        active = true;
    }

    public void addRadial(float x, float y, float radius, Color color, float opacity) {
        if (!active || radius <= 0f || opacity <= 0f) return;
        float originX = (left - 0.5f) * 8f;
        float originY = (bottom - 0.5f) * 8f;
        float bx = (x - originX) / SCALE;
        float by = (y - originY) / SCALE;
        float br = radius / SCALE;
        int x0 = Math.max(0, (int)Math.floor(bx - br));
        int x1 = Math.min(width - 1, (int)Math.ceil(bx + br));
        int y0 = Math.max(0, (int)Math.floor(by - br));
        int y1 = Math.min(height - 1, (int)Math.ceil(by + br));
        for (int py = y0; py <= y1; py++) {
            float dy = py + 0.5f - by;
            for (int px = x0; px <= x1; px++) {
                float dx = px + 0.5f - bx;
                float d = (float)Math.sqrt(dx * dx + dy * dy) / br;
                if (d >= 1f) continue;
                int index = (int)(d * (FALLOFF.length - 1));
                float src = opacity * FALLOFF[index] / 255f;
                int slot = py * width + px;
                if (src > coverage[slot]) coverage[slot] = src;
            }
        }
    }

    public void addSegment(float x, float y, float x2, float y2, float stroke, float opacity) {
        if (!active || opacity <= 0f) return;
        float originX = (left - 0.5f) * 8f;
        float originY = (bottom - 0.5f) * 8f;
        float ax = (x - originX) / SCALE, ay = (y - originY) / SCALE;
        float bx = (x2 - originX) / SCALE, by = (y2 - originY) / SCALE;
        float sr = Math.max(1f, stroke / SCALE);
        int x0 = Math.max(0, (int)Math.floor(Math.min(ax, bx) - sr));
        int x1 = Math.min(width - 1, (int)Math.ceil(Math.max(ax, bx) + sr));
        int y0 = Math.max(0, (int)Math.floor(Math.min(ay, by) - sr));
        int y1 = Math.min(height - 1, (int)Math.ceil(Math.max(ay, by) + sr));
        float vx = bx - ax, vy = by - ay;
        float len = vx * vx + vy * vy;
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                float t = len == 0f ? 0f
                    : Math.max(0f, Math.min(1f, ((px + 0.5f - ax) * vx + (py + 0.5f - ay) * vy) / len));
                float dx = px + 0.5f - (ax + vx * t), dy = py + 0.5f - (ay + vy * t);
                if (dx * dx + dy * dy > sr * sr) continue;
                int slot = py * width + px;
                if (opacity > coverage[slot]) coverage[slot] = opacity;
            }
        }
    }

    public void addRect(float x, float y, float w, float h, float opacity) {
        if (!active || opacity <= 0f) return;
        float originX = (left - 0.5f) * 8f;
        float originY = (bottom - 0.5f) * 8f;
        int x0 = Math.max(0, (int)Math.floor((x - w / 2f - originX) / SCALE));
        int x1 = Math.min(width - 1, (int)Math.ceil((x + w / 2f - originX) / SCALE));
        int y0 = Math.max(0, (int)Math.floor((y - h / 2f - originY) / SCALE));
        int y1 = Math.min(height - 1, (int)Math.ceil((y + h / 2f - originY) / SCALE));
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                int slot = py * width + px;
                if (opacity > coverage[slot]) coverage[slot] = opacity;
            }
        }
    }

    /**
     * Composites the coverage with the map ambient light and draws the result
     * over the viewport. Returns the number of covered buffer pixels.
     */
    public int end(Color ambient) {
        active = false;
        if (texture == null) return 0;
        int lit = 0;
        for (int i = 0; i < coverage.length; i++) {
            float cov = coverage[i];
            float alpha = Math.max(0f, Math.min(1f, ambient.a * (1f - cov)));
            if (cov > 0f) lit++;
            int r = Math.round(Math.max(0f, Math.min(1f, ambient.r)) * 255f);
            int g = Math.round(Math.max(0f, Math.min(1f, ambient.g)) * 255f);
            int b = Math.round(Math.max(0f, Math.min(1f, ambient.b)) * 255f);
            texture.pixels[i] = r << 24 | g << 16 | b << 8 | Math.round(alpha * 255f);
        }
        texture.revision++;
        if (lit == 0 && ambient.a <= 0.0001f) return 0;

        float originX = (left - 0.5f) * 8f;
        float originY = (bottom - 0.5f) * 8f;
        float w = tilesW * 8f, h = tilesH * 8f;
        float z = Layer.light, blend = 0f;
        Draw.z(z);
        Draw.blend();
        Draw.color();
        Draw.rect(region, originX + w / 2f, originY + h / 2f, w, h);
        Draw.reset();
        return lit;
    }

    /** Test hook: coverage at a world position after the last composite. */
    public float coverageAt(float x, float y) {
        if (texture == null) return 0f;
        float originX = (left - 0.5f) * 8f, originY = (bottom - 0.5f) * 8f;
        int px = (int)((x - originX) / SCALE), py = (int)((y - originY) / SCALE);
        if (px < 0 || py < 0 || px >= width || py >= height) return 0f;
        return coverage[py * width + px];
    }

    public RasterTexture texture() { return texture; }
}
