import arc.Core;
import arc.files.Fi;
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
    private static final int VIEW_WIDTH = 16;
    private static final int VIEW_HEIGHT = 12;

    public static void main(String[] args) throws IOException {
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(args.length == 0 ? args : new String[]{args[0]});
        Core.atlas = readAtlas(atlasFile(args));
        Draw.scl = 1f / Core.atlas.find("scale_marker").width;
        Vars.headless = false;
        for (var block : Vars.content.blocks()) {
            if (block instanceof Floor floor && block != Blocks.air) floor.load();
        }
        var batch = new TerrainBatch();
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
        for (int y = bottom; y < bottom + VIEW_HEIGHT; y++) {
            for (int x = left; x < left + VIEW_WIDTH; x++) {
                Tile tile = Vars.world.tile(x, y);
                tile.floor().drawBase(tile);
            }
        }
        if (batch.commands < VIEW_WIDTH * VIEW_HEIGHT || batch.copper == 0 || batch.edges == 0) {
            throw new IllegalStateException("Incomplete upstream terrain trace");
        }
        System.out.println("UPSTREAM_TERRAIN viewport=" + left + "," + bottom + "+" +
            VIEW_WIDTH + "x" + VIEW_HEIGHT + " commands=" + batch.commands + " copper=" +
            batch.copper + " edges=" + batch.edges + " hash=" +
            Long.toUnsignedString(batch.hash, 16));
    }

    private static Fi atlasFile(String[] args) {
        if (args.length < 2) return new Fi("/3ds/min3dstry/sprites.aatls");
        return new Fi(args[1]);
    }

    private static TextureAtlas readAtlas(Fi atlasFile) {
        var data = new TextureAtlasData(atlasFile, atlasFile.parent(), false);
        var atlas = new TextureAtlas();
        for (var page : data.getPages()) {
            var texture = new AtlasPageTexture(page.textureFile.name(), page.width, page.height);
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
        final String pageName;

        AtlasPageTexture(String pageName, int width, int height) {
            this.pageName = pageName;
            this.width = width;
            this.height = height;
        }
    }

    private static final class TerrainBatch extends Batch {
        final Seq<AtlasRegion> edgeSources = Core.atlas.getRegions().select(r -> r.name.endsWith("-edge"));
        long hash = 0xcbf29ce484222325L;
        int commands;
        int copper;
        int edges;

        @Override
        protected void draw(Texture texture, float[] vertices, int offset, int count) {
            throw new IllegalStateException("Unexpected raw vertices in the terrain probe");
        }

        @Override
        protected void draw(TextureRegion region, float x, float y, float originX, float originY,
                            float width, float height, float rotation) {
            if (region == Core.atlas.find("error")) throw new IllegalStateException("Missing terrain sprite");
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

            hash = mix(hash, ((AtlasPageTexture) region.texture).pageName.hashCode());
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
