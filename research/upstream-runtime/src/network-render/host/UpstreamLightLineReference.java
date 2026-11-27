import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Drawf;
import port.render.UpstreamBuildingLights;
import port.render.UpstreamFluidAtlas;

/**
 * Verifies the patched non-radial light paths when the desktop Renderer is
 * absent: Drawf.light segment and textured-region calls should reach
 * UpstreamBuildingLights instead of returning early.
 */
public final class UpstreamLightLineReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private int quads;
    private int sprites;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Expected Glacier, sprites.aatls, block_colors.png");
        }
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(new String[]{args[0]}, () -> Vars.indexer = new BlockIndexer());
        UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), UpstreamFluidAtlas::load);
        Vars.content.loadColors(new Fi(args[2]));

        var target = new UpstreamLightLineReference();
        var viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        viewport.moveTo(73, 20);

        var region = Core.atlas.find("circle-shadow");
        if (!region.found()) throw new AssertionError("Missing stock light texture");

        UpstreamBuildingLights.begin();
        try {
            Drawf.light(600f, 200f, 620f, 208f, 4f, Color.red, 1f);
            if (target.quads + target.sprites != 3) {
                throw new AssertionError("Stock line light requires a center strip and both end caps");
            }
            int spritesBefore = target.sprites;
            Drawf.light(610f, 210f, region, 0f, Color.green, 1f);
            if (target.sprites != spritesBefore + 1) {
                throw new AssertionError("Stock textured light drew no sprite");
            }
        } finally {
            UpstreamBuildingLights.end();
        }
        System.out.println("UPSTREAM_LIGHT_PATHS line=ok textured=ok quads=" + target.quads
            + " sprites=" + target.sprites);
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
    @Override public void drawSolidQuad(float[] vertices, int offset, arc.graphics.Blending blending) {
        throw new AssertionError("Stock light strips are textured");
    }

    @Override public void drawTexturedQuad(int page, int pageWidth, int pageHeight, float[] vertices, int offset,
                                           arc.graphics.Blending blending) {
        quads++;
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, arc.graphics.Blending blending) {
        sprites++;
    }
}
