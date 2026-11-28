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
    private int buffers;

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

        var rules = Vars.state.rules;
        boolean lighting = rules.lighting;
        float ambient = rules.ambientLight.a;
        rules.lighting = true;
        rules.ambientLight.a = 0.5f;
        try {
            UpstreamBuildingLights.begin(viewport.left, viewport.bottom, viewport.width(), viewport.height());
            try {
                Drawf.light(600f, 200f, 620f, 208f, 4f, Color.red, 1f);
                Drawf.light(610f, 210f, region, 0f, Color.green, 1f);
            } finally {
                UpstreamBuildingLights.end();
            }
        } finally {
            rules.lighting = lighting;
            rules.ambientLight.a = ambient;
        }
        if (target.quads == 0) {
            throw new AssertionError("Stock line light drew no geometry");
        }
        if (target.sprites == 0) {
            throw new AssertionError("Stock textured light drew no geometry");
        }
        if (UpstreamBuildingLights.coverageAt(610f, 204f) <= 0f) {
            throw new AssertionError("Stock light buffer captured no line/textured coverage");
        }
        if (target.buffers == 0) {
            throw new AssertionError("Stock light buffer was not composited");
        }
        System.out.println("UPSTREAM_LIGHT_PATHS line=ok textured=ok buffer=ok quads=" + target.quads
            + " sprites=" + target.sprites + " buffers=" + target.buffers);
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
    @Override public void drawLogicDisplay(port.render.UpstreamLogicFramebuffer.RasterTexture texture,
                                           float[] vertices, int offset, arc.graphics.Blending blending) {
        buffers++;
    }
    @Override public void drawSolidQuad(float[] vertices, int offset, arc.graphics.Blending blending) {
        quads++;
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, arc.graphics.Blending blending) {
        sprites++;
    }
}
