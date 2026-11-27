import arc.files.Fi;
import arc.graphics.Color;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Drawf;
import port.render.UpstreamBuildingLights;
import port.render.UpstreamFluidAtlas;

/**
 * Verifies the patched non-radial (line) light path when the desktop Renderer is
 * absent. Drawf.light(x, y, x2, y2, ...) should reach UpstreamBuildingLights
 * instead of returning early.
 */
public final class UpstreamLightLineReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private int quads;

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

        UpstreamBuildingLights.begin();
        try {
            Drawf.light(600f, 200f, 620f, 208f, 4f, Color.red, 1f);
        } finally {
            UpstreamBuildingLights.end();
        }
        if (target.quads == 0) {
            throw new AssertionError("Stock line light drew no geometry");
        }
        System.out.println("UPSTREAM_LIGHT_LINE stroke=4 quads=" + target.quads);
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
    @Override public void drawSolidQuad(float[] vertices, int offset, arc.graphics.Blending blending) {
        quads++;
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, arc.graphics.Blending blending) {
    }
}
