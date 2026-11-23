package port.render;

import arc.Core;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.g2d.Batch;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.SpriteBatch;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mat;
import arc.math.Mathf;
import arc.util.Disposable;

import java.util.Arrays;

/** Offscreen target for the original LogicDisplay's Arc draw calls. */
public final class UpstreamLogicFramebuffer implements Disposable {
    @FunctionalInterface
    public interface AtlasPixels {
        int sample(Texture texture, int x, int y);
    }

    public static final class RasterTexture extends Texture {
        private static int nextId;
        public final int id = --nextId;
        public final int[] pixels;
        public int revision;
        private boolean disposed;

        public RasterTexture(int width, int height) {
            this.width = width;
            this.height = height;
            pixels = new int[Math.multiplyExact(width, height)];
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }

    private static final RasterBatch raster = new RasterBatch();
    private final RasterTexture texture;

    public UpstreamLogicFramebuffer(int width, int height) {
        texture = new RasterTexture(width, height);
    }

    public static void atlasPixels(AtlasPixels pixels) {
        raster.atlasPixels = pixels;
    }

    public int getWidth() {
        return texture.width;
    }

    public int getHeight() {
        return texture.height;
    }

    public RasterTexture getTexture() {
        return texture;
    }

    public void begin() {
        raster.begin(texture);
    }

    public void begin(Color background) {
        begin();
        // OpenGL framebuffer rows start at the bottom. Stock LogicDisplay
        // flips the display texture when it draws it onto the world.
        Arrays.fill(texture.pixels, background.rgba());
    }

    public void clear(float r, float g, float b, float a) {
        int rgba = Math.round(255 * Mathf.clamp(r)) << 24
            | Math.round(255 * Mathf.clamp(g)) << 16
            | Math.round(255 * Mathf.clamp(b)) << 8
            | Math.round(255 * Mathf.clamp(a));
        Arrays.fill(texture.pixels, rgba);
    }

    public void end() {
        raster.end(texture);
    }

    public boolean isDisposed() {
        return texture.isDisposed();
    }

    @Override
    public void dispose() {
        texture.dispose();
    }

    private static final class RasterBatch extends Batch {
        private final float[] x = new float[4], y = new float[4], u = new float[4], v = new float[4];
        private RasterTexture target;
        private Batch previous;
        private AtlasPixels atlasPixels;

        void begin(RasterTexture texture) {
            if (target != null || texture.isDisposed()) throw new IllegalStateException("Invalid Logic Display target");
            Draw.flush();
            transformMatrix.set(Draw.trans());
            colorPacked = Draw.getColorPacked();
            blending = Blending.normal;
            previous = Core.batch;
            target = texture;
            Core.batch = this;
        }

        void end(RasterTexture texture) {
            if (target != texture) throw new IllegalStateException("Mismatched Logic Display target");
            texture.revision++;
            Core.batch = previous;
            previous = null;
            target = null;
        }

        @Override
        protected void draw(Texture source, float[] vertices, int offset, int count) {
            final int stride = SpriteBatch.VERTEX_SIZE;
            for (int i = offset; i < offset + count; i += SpriteBatch.SPRITE_SIZE) {
                for (int corner = 0; corner < 4; corner++) {
                    int vertex = i + corner * stride;
                    position(corner, vertices[vertex], vertices[vertex + 1]);
                    u[corner] = vertices[vertex + 3];
                    v[corner] = vertices[vertex + 4];
                }
                quad(source, Color.floatToIntColor(vertices[i + 2]));
            }
        }

        @Override
        protected void draw(TextureRegion region, float left, float bottom, float originX, float originY,
                            float width, float height, float rotation) {
            float sin = Mathf.sinDeg(rotation), cos = Mathf.cosDeg(rotation);
            float pivotX = left + originX, pivotY = bottom + originY;
            for (int corner = 0; corner < 4; corner++) {
                boolean right = corner >= 2, top = corner == 1 || corner == 2;
                float px = (right ? width : 0f) - originX, py = (top ? height : 0f) - originY;
                position(corner, pivotX + cos * px - sin * py, pivotY + sin * px + cos * py);
                u[corner] = right ? region.u2 : region.u;
                v[corner] = top ? region.v : region.v2;
            }
            quad(region.texture, Color.floatToIntColor(colorPacked));
        }

        private void position(int corner, float px, float py) {
            float[] m = transformMatrix.val;
            x[corner] = m[Mat.M00] * px + m[Mat.M01] * py + m[Mat.M02];
            y[corner] = m[Mat.M10] * px + m[Mat.M11] * py + m[Mat.M12];
        }

        private void quad(Texture source, int tint) {
            var white = Core.atlas.white();
            boolean solid = source == white.texture;
            for (int i = 0; solid && i < 4; i++) {
                solid = u[i] >= white.u && u[i] <= white.u2 && v[i] >= white.v && v[i] <= white.v2;
            }
            triangle(source, tint, solid, 0, 1, 2);
            triangle(source, tint, solid, 2, 3, 0);
        }

        private void triangle(Texture source, int tint, boolean solid, int a, int b, int c) {
            float area = edge(a, b, x[c], y[c]);
            if (area == 0f || !Float.isFinite(area)) return;
            if (area < 0f) {
                int swap = b;
                b = c;
                c = swap;
                area = -area;
            }
            int minX = Math.max(0, (int)Math.floor(Math.min(x[a], Math.min(x[b], x[c]))));
            int minY = Math.max(0, (int)Math.floor(Math.min(y[a], Math.min(y[b], y[c]))));
            int maxX = Math.min(target.width, (int)Math.ceil(Math.max(x[a], Math.max(x[b], x[c]))));
            int maxY = Math.min(target.height, (int)Math.ceil(Math.max(y[a], Math.max(y[b], y[c]))));
            boolean edgeA = inclusive(b, c), edgeB = inclusive(c, a), edgeC = inclusive(a, b);
            for (int py = minY; py < maxY; py++) {
                for (int px = minX; px < maxX; px++) {
                    float wa = edge(b, c, px + 0.5f, py + 0.5f);
                    float wb = edge(c, a, px + 0.5f, py + 0.5f);
                    float wc = edge(a, b, px + 0.5f, py + 0.5f);
                    if (!covered(wa, edgeA) || !covered(wb, edgeB) || !covered(wc, edgeC)) continue;
                    int rgba = -1;
                    if (!solid) {
                        float tu = (wa * u[a] + wb * u[b] + wc * u[c]) / area;
                        float tv = (wa * v[a] + wb * v[b] + wc * v[c]) / area;
                        rgba = sample(source, tu, tv);
                    }
                    int index = py * target.width + px;
                    target.pixels[index] = blend(rgba, tint, target.pixels[index]);
                }
            }
        }

        private int sample(Texture source, float tu, float tv) {
            int tx = Mathf.clamp((int)(tu * source.width), 0, source.width - 1);
            int ty = Mathf.clamp((int)(tv * source.height), 0, source.height - 1);
            if (source instanceof RasterTexture rasterTexture) {
                return rasterTexture.pixels[ty * source.width + tx];
            }
            return atlasPixels.sample(source, tx, ty);
        }

        private boolean inclusive(int a, int b) {
            return y[a] > y[b] || (y[a] == y[b] && x[a] < x[b]);
        }

        private static boolean covered(float edge, boolean inclusive) {
            return edge > 0f || (edge == 0f && inclusive);
        }

        private float edge(int a, int b, float px, float py) {
            return (x[b] - x[a]) * (py - y[a]) - (y[b] - y[a]) * (px - x[a]);
        }

        private int blend(int rgba, int tint, int previous) {
            int red = multiply(rgba >>> 24, tint & 255);
            int green = multiply(rgba >>> 16 & 255, tint >>> 8 & 255);
            int blue = multiply(rgba >>> 8 & 255, tint >>> 16 & 255);
            int alpha = multiply(rgba & 255, tint >>> 24);
            if (blending == Blending.disabled || alpha == 255) {
                return red << 24 | green << 16 | blue << 8 | alpha;
            }
            int inverse = 255 - alpha;
            return composite(red, previous >>> 24, alpha, inverse) << 24
                | composite(green, previous >>> 16 & 255, alpha, inverse) << 16
                | composite(blue, previous >>> 8 & 255, alpha, inverse) << 8
                | (alpha + multiply(previous & 255, inverse));
        }

        private static int multiply(int value, int factor) {
            return (value * factor + 127) / 255;
        }

        private static int composite(int color, int previous, int alpha, int inverse) {
            return (color * alpha + previous * inverse + 127) / 255;
        }

        @Override
        protected void flush() {
            // Draw calls rasterize immediately, including before a clear or transform.
        }
    }
}
