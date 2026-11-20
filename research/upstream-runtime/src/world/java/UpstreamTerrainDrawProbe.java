import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.g2d.Batch;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import arc.graphics.g2d.TextureRegion;
import arc.struct.Seq;
import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.graphics.CacheLayer;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;

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
                  float rotation, int color);
        void end();
    }

    static void capture(String[] args, TerrainTarget target) throws IOException {
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(mapArgs(args));
        var viewport = prepare(args, target, true);
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

    static Viewport prepare(String[] args, TerrainTarget target, boolean trace) {
        Core.atlas = readAtlas(atlasFile(args));
        Draw.scl = 1f / Core.atlas.find("scale_marker").width;
        boolean wasHeadless = Vars.headless;
        Vars.headless = false;
        try {
            for (var block : Vars.content.blocks()) {
                if (block instanceof Floor floor && block != Blocks.air) floor.load();
            }
        } finally {
            Vars.headless = wasHeadless;
        }
        var batch = new TerrainBatch(target, trace);
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
        return new Viewport(left, bottom, batch);
    }

    static final class Viewport {
        final int left;
        final int bottom;
        private final TerrainBatch batch;

        Viewport(int left, int bottom, TerrainBatch batch) {
            this.left = left;
            this.bottom = bottom;
            this.batch = batch;
        }

        void draw(Runnable buildings) {
            if (batch.target != null) batch.target.begin(left, bottom, VIEW_WIDTH, VIEW_HEIGHT);
            Draw.reset();
            Draw.z(0f);
            for (int y = bottom; y < bottom + VIEW_HEIGHT; y++) {
                for (int x = left; x < left + VIEW_WIDTH; x++) {
                    Tile tile = Vars.world.tile(x, y);
                    tile.floor().drawBase(tile);
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

    private static final class TerrainBatch extends Batch {
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
            throw new IllegalStateException("Unexpected raw vertices in the terrain probe");
        }

        @Override
        protected void draw(TextureRegion region, float x, float y, float originX, float originY,
                            float width, float height, float rotation) {
            if (region == Core.atlas.find("error")) throw new IllegalStateException("Missing terrain sprite");
            var page = (AtlasPageTexture) region.texture;
            if (edgeSources != null) collect(region, page, x, y, originX, originY, width, height, rotation);
            if (target != null) {
                if (mixColorPacked != Color.clearFloatBits) {
                    throw new IllegalStateException("Unexpected terrain mix color");
                }
                target.draw(page.index, region.getX(), region.getY(), region.width, region.height,
                    x, y, originX, originY, width, height, rotation, Color.floatToIntColor(colorPacked));
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
        protected void flush() {}
    }
}
