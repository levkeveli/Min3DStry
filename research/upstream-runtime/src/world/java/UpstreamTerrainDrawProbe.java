import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.g2d.Batch;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.SpriteBatch;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.struct.Seq;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.gen.Building;
import mindustry.graphics.CacheLayer;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import port.render.UpstreamCanvasTexture;
import port.render.UpstreamLogicFramebuffer.RasterTexture;
import port.render.WeatherNoiseTexture;

/** Captures the upstream terrain draw calls before they reach a GPU. */
public final class UpstreamTerrainDrawProbe {
    static final int VIEW_WIDTH = 16;
    static final int VIEW_HEIGHT = 12;

    public static void main(String[] args) throws IOException {
        capture(args, null);
    }

    interface TerrainTarget {
        void begin(int left, int bottom, int width, int height);
        void draw(int page, int left, int top, int width, int height,
                  float x, float y, float originX, float originY, float drawWidth, float drawHeight,
                  float rotation, int color, int mixColor, Blending blending);
        void drawSolidQuad(float[] vertices, int offset, Blending blending);
        default void drawTexturedQuad(int page, int pageWidth, int pageHeight, float[] vertices, int offset, Blending blending) {
            throw new IllegalStateException("Unsupported Arc textured geometry");
        }
        default void drawCanvas(UpstreamCanvasTexture texture, float x, float y, float originX, float originY,
                                float width, float height, float rotation, int color, int mixColor, Blending blending) {
            throw new IllegalStateException("Unsupported Arc Canvas texture");
        }
        default void drawCanvasQuad(UpstreamCanvasTexture texture, float[] vertices, int offset, Blending blending) {
            throw new IllegalStateException("Unsupported Arc Canvas quad");
        }
        default void drawLogicDisplay(RasterTexture texture, float[] vertices, int offset, Blending blending) {
            throw new IllegalStateException("Unsupported Arc Logic Display texture");
        }
        default void drawWeatherNoise(WeatherNoiseTexture texture, float x, float y, float width, float height,
                                      float u, float v, float u2, float v2, int tint, int mix, Blending blending) {
            throw new IllegalStateException("Unsupported Arc weather noise texture");
        }
        void end();
    }

    static void capture(String[] args, TerrainTarget target) throws IOException {
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(mapArgs(args));
        loadAtlas(atlasFile(args), null);
        var viewport = traceViewport(target);
        viewport.draw(() -> {});
        var batch = viewport.batch;
        if (batch.commands < VIEW_WIDTH * VIEW_HEIGHT || batch.copper == 0 || batch.edges == 0) {
            throw new IllegalStateException("Incomplete upstream terrain trace");
        }
        System.out.println("UPSTREAM_TERRAIN viewport=" + viewport.left + "," + viewport.bottom + "+" +
            VIEW_WIDTH + "x" + VIEW_HEIGHT + " commands=" + batch.commands + " copper=" +
            batch.copper + " edges=" + batch.edges + " hash=" +
            Long.toUnsignedString(batch.hash, 16));
    }

    static String[] mapArgs(String[] args) {
        return args.length == 0 ? args : new String[]{args[0]};
    }

    static void loadAtlas(Fi atlas, Runnable prepareLiveAtlas) {
        Core.atlas = readAtlas(atlas);
        if (prepareLiveAtlas != null) prepareLiveAtlas.run();
        Draw.scl = 1f / Core.atlas.find("scale_marker").width;
        boolean wasHeadless = Vars.headless;
        Vars.headless = false;
        try {
            for (var block : Vars.content.blocks()) {
                if (block == Blocks.air) continue;
                if (block instanceof Floor || prepareLiveAtlas != null) {
                    block.load();
                }
                if (prepareLiveAtlas != null) block.loadIcon();
            }
        } finally {
            Vars.headless = wasHeadless;
        }
    }

    static Viewport liveViewport(TerrainTarget target) {
        var batch = new TerrainBatch(target, false);
        Core.batch = batch;
        return new Viewport(0, 0, batch, true);
    }

    private static Viewport traceViewport(TerrainTarget target) {
        var batch = new TerrainBatch(target, true);
        Core.batch = batch;

        Tile copper = null;
        for (Tile tile : Vars.world.tiles) {
            if (tile.overlay() == Blocks.oreCopper) {
                copper = tile;
                break;
            }
        }
        if (copper == null) throw new IllegalStateException("Glacier has no copper");
        int left = Math.max(0, Math.min(copper.x - VIEW_WIDTH / 2, Vars.world.width() - VIEW_WIDTH));
        int bottom = Math.max(0, Math.min(copper.y - VIEW_HEIGHT / 2, Vars.world.height() - VIEW_HEIGHT));
        return new Viewport(left, bottom, batch, false);
    }

    static final class Viewport {
        int left;
        int bottom;
        private final TerrainBatch batch;
        private final boolean drawStaticBlocks;

        Viewport(int left, int bottom, TerrainBatch batch, boolean drawStaticBlocks) {
            this.left = left;
            this.bottom = bottom;
            this.batch = batch;
            this.drawStaticBlocks = drawStaticBlocks;
        }

        void moveTo(int x, int y) {
            left = Mathf.clamp(x, 0, Math.max(0, Vars.world.width() - VIEW_WIDTH));
            bottom = Mathf.clamp(y, 0, Math.max(0, Vars.world.height() - VIEW_HEIGHT));
        }

        boolean contains(Building building) {
            var tile = building.tile;
            int low = (building.block.size - 1) / 2;
            int high = building.block.size / 2;
            return tile.x + high >= left && tile.x - low < left + VIEW_WIDTH
                && tile.y + high >= bottom && tile.y - low < bottom + VIEW_HEIGHT;
        }

        void draw(Runnable buildings) {
            draw(null, buildings);
        }

        void draw(Runnable debris, Runnable buildings) {
            if (batch.target != null) batch.target.begin(left, bottom, VIEW_WIDTH, VIEW_HEIGHT);
            Draw.reset();
            Draw.z(0f);
            int top = Math.min(bottom + VIEW_HEIGHT, Vars.world.height());
            int right = Math.min(left + VIEW_WIDTH, Vars.world.width());
            for (int y = bottom; y < top; y++) {
                for (int x = left; x < right; x++) {
                    Tile tile = Vars.world.tile(x, y);
                    tile.floor().drawBase(tile);
                }
            }
            if (debris != null) debris.run();
            if (drawStaticBlocks) {
                for (int y = bottom; y < top; y++) {
                    for (int x = left; x < right; x++) {
                        Tile tile = Vars.world.tile(x, y);
                        if (tile.build == null && tile.block() != Blocks.air) tile.block().drawBase(tile);
                    }
                }
            }
            buildings.run();
            if (batch.target != null) batch.target.end();
        }
    }

    private static Fi atlasFile(String[] args) {
        if (args.length < 2) return new Fi("/3ds/min3dstry/sprites.aatls");
        return new Fi(args[1]);
    }

    private static TextureAtlas readAtlas(Fi atlasFile) {
        var data = new TextureAtlasData(atlasFile, atlasFile.parent(), false);
        var atlas = new TextureAtlas();
        for (int index = 0; index < data.getPages().size; index++) {
            var page = data.getPages().get(index);
            var texture = new AtlasPageTexture(index, page.textureFile.name(), page.width, page.height);
            for (var entry : data.getRegions()) {
                if (entry.page != page) continue;
                if (entry.rotate) throw new IllegalStateException("Unexpected atlas rotation");
                AtlasRegion region = atlas.addRegion(entry.name, texture, entry.left, entry.top,
                    entry.width, entry.height);
                region.offsetX = entry.offsetX;
                region.offsetY = entry.offsetY;
                region.originalWidth = entry.originalWidth;
                region.originalHeight = entry.originalHeight;
                region.splits = entry.splits;
                region.pads = entry.pads;
            }
        }
        if (!atlas.setErrorRegion("error")) throw new IllegalStateException("Atlas has no error region");
        return atlas;
    }

    private static final class AtlasPageTexture extends Texture {
        final int index;
        final String pageName;

        AtlasPageTexture(int index, String pageName, int width, int height) {
            this.index = index;
            this.pageName = pageName;
            this.width = width;
            this.height = height;
        }
    }

    static int pageIndex(TextureRegion region) {
        return pageIndex(region.texture);
    }

    static int pageIndex(Texture texture) {
        return ((AtlasPageTexture) texture).index;
    }

    private static final class TerrainBatch extends Batch {
        // Arc SpriteBatch queues draw requests while sorting. A deferred callback can
        // draw immediately during the flush, and equal depths retain submission order.
        private record DrawRequest(float depth, Blending blending, Runnable draw) {}
        private final Seq<DrawRequest> requests = new Seq<>();
        private boolean sort, flushing;

        final Seq<AtlasRegion> edgeSources;
        final TerrainTarget target;
        long hash = 0xcbf29ce484222325L;
        int commands;
        int copper;
        int edges;

        TerrainBatch(TerrainTarget target, boolean trace) {
            this.target = target;
            edgeSources = trace ? Core.atlas.getRegions().select(r -> r.name.endsWith("-edge")) : null;
        }

        @Override
        protected void draw(Texture texture, float[] vertices, int offset, int count) {
            if (!(texture instanceof AtlasPageTexture || texture instanceof UpstreamCanvasTexture || texture instanceof RasterTexture)
                    || offset < 0 || count < 0
                    || offset > vertices.length - count || count % SpriteBatch.SPRITE_SIZE != 0) {
                throw new IllegalStateException("Unsupported Arc vertex batch");
            }
            if (sort && !flushing) {
                // Arc reuses the Fill vertex array for the next command.
                float[] snapshot = Arrays.copyOfRange(vertices, offset, offset + count);
                draw(() -> emitQuads(texture, snapshot, 0, snapshot.length));
            } else {
                emitQuads(texture, vertices, offset, count);
            }
        }

        private void emitQuads(Texture texture, float[] vertices, int offset, int count) {
            if (texture instanceof RasterTexture display) {
                if (target != null && !display.isDisposed()) {
                    for (int i = offset; i < offset + count; i += SpriteBatch.SPRITE_SIZE) {
                        target.drawLogicDisplay(display, vertices, i, blending);
                    }
                }
                return;
            }
            if (texture instanceof UpstreamCanvasTexture canvas) {
                if (target != null && !canvas.isDisposed()) {
                    for (int i = offset; i < offset + count; i += SpriteBatch.SPRITE_SIZE) {
                        target.drawCanvasQuad(canvas, vertices, i, blending);
                    }
                }
                return;
            }
            var page = (AtlasPageTexture) texture;
            var white = Core.atlas.white();
            for (int i = offset; i < offset + count; i += SpriteBatch.SPRITE_SIZE) {
                boolean solid = page == white.texture;
                for (int v = i; solid && v < i + SpriteBatch.SPRITE_SIZE; v += SpriteBatch.VERTEX_SIZE) {
                    float u = vertices[v + 3], t = vertices[v + 4];
                    solid = u >= white.u && u <= white.u2 && t >= white.v && t <= white.v2
                        && vertices[v + 5] == Color.clearFloatBits;
                }
                if (solid) {
                    if (target != null) target.drawSolidQuad(vertices, i, blending);
                } else if (target != null && !emitTexturedRectangle(page, vertices, i)) {
                    target.drawTexturedQuad(page.index, page.width, page.height, vertices, i, blending);
                }
            }
        }

        // Arc Lines submits a rectangle through Fill.quad, with the top-left UV
        // first. Recover the sprite transform from the UV corners instead of
        // assuming the same vertex order as SpriteBatch's region draw.
        private boolean emitTexturedRectangle(AtlasPageTexture page, float[] v, int a) {
            int b = a + SpriteBatch.VERTEX_SIZE, c = b + SpriteBatch.VERTEX_SIZE, d = c + SpriteBatch.VERTEX_SIZE;
            float u0 = v[a + 3], u1 = v[d + 3], t0 = v[a + 4], t1 = v[b + 4];
            float tint = v[a + 2], mixTint = v[a + 5];
            if (v[b + 3] != u0 || v[c + 3] != u1 || v[c + 4] != t1 || v[d + 4] != t0
                    || v[b + 2] != tint || v[c + 2] != tint || v[d + 2] != tint
                    || v[b + 5] != mixTint || v[c + 5] != mixTint || v[d + 5] != mixTint) {
                return false;
            }
            int leftFirst = u0 <= u1 ? a : d, leftSecond = u0 <= u1 ? b : c;
            int bottomLeft = t0 <= t1 ? leftSecond : leftFirst;
            int topLeft = t0 <= t1 ? leftFirst : leftSecond;
            int bottomRight = a + d - bottomLeft, topRight = a + d - topLeft;
            float x = v[bottomLeft], y = v[bottomLeft + 1];
            float dx = v[bottomRight] - x, dy = v[bottomRight + 1] - y;
            float ux = v[topLeft] - x, uy = v[topLeft + 1] - y;
            float width = Mathf.len(dx, dy);
            if (width == 0f) return false;
            float height = (dx * uy - dy * ux) / width;
            float error = 0.005f; // Leave room for rounding at the far edge of a large map.
            if (Math.abs((dx * ux + dy * uy) / width) > error
                    || Math.abs(v[topRight] - x - dx - ux) > error
                    || Math.abs(v[topRight + 1] - y - dy - uy) > error) {
                return false;
            }
            int left = Math.round(Math.min(u0, u1) * page.width);
            int right = Math.round(Math.max(u0, u1) * page.width);
            int top = Math.round(Math.min(t0, t1) * page.height);
            int bottom = Math.round(Math.max(t0, t1) * page.height);
            if (height == 0f || right <= left || bottom <= top) return false;
            float rotation = (float)Math.toDegrees(Math.atan2(dy, dx));
            target.draw(page.index, left, top, right - left, bottom - top, x, y, 0f, 0f, width, height,
                rotation, Color.floatToIntColor(tint), Color.floatToIntColor(mixTint), blending);
            return true;
        }

        @Override
        protected void draw(TextureRegion region, float x, float y, float originX, float originY,
                            float width, float height, float rotation) {
            // OpenGL discards degenerate quads, including an empty payload crop.
            if (region.width == 0 || region.height == 0 || width == 0f || height == 0f) return;
            if (region.texture instanceof WeatherNoiseTexture noise) {
                if (target != null) {
                    int tint = Color.floatToIntColor(colorPacked), mix = Color.floatToIntColor(mixColorPacked);
                    float u = region.u, v = region.v, u2 = region.u2, v2 = region.v2;
                    draw(() -> target.drawWeatherNoise(noise, x, y, width, height, u, v, u2, v2,
                        tint, mix, blending));
                }
                return;
            }
            if (region.texture instanceof RasterTexture display) {
                if (target != null && !display.isDisposed()) {
                    // Preserve UVs for flipped framebuffer regions and individual display tiles.
                    float[] vertices = new float[SpriteBatch.SPRITE_SIZE];
                    float sin = Mathf.sinDeg(rotation), cos = Mathf.cosDeg(rotation);
                    for (int corner = 0; corner < 4; corner++) {
                        boolean right = corner >= 2, top = corner == 1 || corner == 2;
                        float px = (right ? width : 0f) - originX, py = (top ? height : 0f) - originY;
                        int i = corner * SpriteBatch.VERTEX_SIZE;
                        vertices[i] = x + originX + cos * px - sin * py;
                        vertices[i + 1] = y + originY + sin * px + cos * py;
                        vertices[i + 2] = colorPacked;
                        vertices[i + 3] = right ? region.u2 : region.u;
                        vertices[i + 4] = top ? region.v : region.v2;
                        vertices[i + 5] = mixColorPacked;
                    }
                    draw(() -> target.drawLogicDisplay(display, vertices, 0, blending));
                }
                return;
            }
            if (region.texture instanceof UpstreamCanvasTexture canvas) {
                if (target != null && !canvas.isDisposed()) {
                    int color = Color.floatToIntColor(colorPacked), mixColor = Color.floatToIntColor(mixColorPacked);
                    draw(() -> target.drawCanvas(canvas, x, y, originX, originY, width, height, rotation, color, mixColor, blending));
                }
                return;
            }
            if (region == Core.atlas.find("error")) throw new IllegalStateException("Missing terrain sprite");
            var page = (AtlasPageTexture) region.texture;
            if (edgeSources != null) collect(region, page, x, y, originX, originY, width, height, rotation);
            if (target != null) {
                int left = region.getX(), top = region.getY();
                int sourceWidth = region.width, sourceHeight = region.height;
                int color = Color.floatToIntColor(colorPacked);
                int mixColor = Color.floatToIntColor(mixColorPacked);
                if (sort && !flushing) {
                    draw(() -> target.draw(page.index, left, top, sourceWidth, sourceHeight,
                        x, y, originX, originY, width, height, rotation, color, mixColor, blending));
                } else {
                    target.draw(page.index, left, top, sourceWidth, sourceHeight,
                        x, y, originX, originY, width, height, rotation, color, mixColor, blending);
                }
            }
        }

        private void collect(TextureRegion region, AtlasPageTexture page, float x, float y,
                             float originX, float originY, float width, float height, float rotation) {
            commands++;
            if (region instanceof AtlasRegion named) {
                if (named.name.startsWith("ore-copper")) copper++;
            } else {
                for (AtlasRegion source : edgeSources) {
                    if (region.texture == source.texture && region.u >= source.u && region.u2 <= source.u2
                            && region.v >= source.v && region.v2 <= source.v2) {
                        edges++;
                        break;
                    }
                }
            }

            hash = mix(hash, page.pageName.hashCode());
            for (float value : new float[]{x, y, originX, originY, width, height, rotation,
                    region.u, region.v, region.u2, region.v2, z, colorPacked, mixColorPacked}) {
                hash = mix(hash, Float.floatToRawIntBits(value));
            }
        }

        private static long mix(long hash, int word) {
            return (hash ^ Integer.toUnsignedLong(word)) * 0x100000001b3L;
        }

        @Override
        protected void draw(Runnable draw) {
            if (sort && !flushing) requests.add(new DrawRequest(z, blending, draw));
            else draw.run();
        }

        @Override
        protected void setSort(boolean enabled) {
            if (sort != enabled) flush();
            sort = enabled;
        }

        @Override
        protected void setBlending(Blending mode) {
            blending = mode;
        }

        @Override
        protected void flush() {
            if (flushing || requests.isEmpty()) return;
            float colorBefore = colorPacked, mixBefore = mixColorPacked;
            Blending blendBefore = blending;
            flushing = true;
            try {
                requests.sort(Comparator.comparingDouble(DrawRequest::depth));
                for (var request : requests) {
                    blending = request.blending();
                    request.draw().run();
                }
            } finally {
                requests.clear();
                colorPacked = colorBefore;
                mixColorPacked = mixBefore;
                blending = blendBefore;
                flushing = false;
            }
        }
    }
}
