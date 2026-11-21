import java.io.IOException;
import mindustry.Vars;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

public final class UpstreamTerrainRenderProbe implements UpstreamTerrainDrawProbe.TerrainTarget {
    private final boolean reportFrame;

    UpstreamTerrainRenderProbe(boolean reportFrame) {
        this.reportFrame = reportFrame;
    }

    public static void main(String[] args) throws IOException {
        UpstreamTerrainDrawProbe.capture(args, new UpstreamTerrainRenderProbe(true));
    }

    void initialize() {
        int result = NativeTerrain.initialize();
        if (result != 1) throw new IllegalStateException("Unable to initialize Citro2D terrain: " + result);
    }

    void buildOverlay(int column, int row, int block, int rotation, boolean removing) {
        if (NativeTerrain.buildOverlay(column, row, block, rotation, removing ? 1 : 0, Vars.tilesize) == 0) {
            throw new IllegalStateException("Unable to draw the Citro2D build palette");
        }
    }

    @Override
    public void begin(int left, int bottom, int width, int height) {
        int result = NativeTerrain.begin(left, bottom, width, height, Vars.tilesize);
        if (result != 1) {
            throw new IllegalStateException("Unable to start Citro2D terrain frame: " + result);
        }
    }

    @Override
    public void draw(int page, int left, int top, int width, int height,
                     float x, float y, float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color) {
        if (NativeTerrain.draw(page, left, top, width, height, x, y, originX, originY,
                drawWidth, drawHeight, rotation, color) == 0) {
            throw new IllegalStateException("Citro2D could not draw an upstream terrain region: "
                + page + "," + left + "," + top + "+" + width + "x" + height);
        }
    }

    @Override
    public void end() {
        int draws = NativeTerrain.end();
        if (draws == 0) throw new IllegalStateException("Citro2D terrain frame has no draws");
        if (reportFrame) System.out.println("UPSTREAM_TERRAIN_FRAME draws=" + draws);
    }

    @Include(value = "m3ds_terrain_renderer.h", isSystem = true)
    private static final class NativeTerrain {
        @Import(name = "m3ds_terrain_initialize")
        static native int initialize();

        @Import(name = "m3ds_terrain_begin")
        static native int begin(int left, int bottom, int width, int height, int tileSize);

        @Import(name = "m3ds_terrain_draw")
        static native int draw(int page, int left, int top, int width, int height,
                               float x, float y, float originX, float originY, float drawWidth,
                               float drawHeight, float rotation, int color);

        @Import(name = "m3ds_terrain_end")
        static native int end();

        @Import(name = "m3ds_terrain_build_overlay")
        static native int buildOverlay(int column, int row, int block, int rotation, int removing, int tileSize);
    }
}
