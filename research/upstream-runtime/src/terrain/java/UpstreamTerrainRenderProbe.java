import java.io.IOException;
import mindustry.Vars;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

public final class UpstreamTerrainRenderProbe implements UpstreamTerrainDrawProbe.TerrainTarget {
    public static void main(String[] args) throws IOException {
        UpstreamTerrainDrawProbe.capture(args, new UpstreamTerrainRenderProbe());
    }

    @Override
    public void begin(int left, int bottom, int width, int height) {
        if (NativeTerrain.begin(left, bottom, width, height, Vars.tilesize) == 0) {
            throw new IllegalStateException("Unable to start Citro2D terrain frame");
        }
    }

    @Override
    public void draw(int page, int left, int top, int width, int height,
                     float x, float y, float drawWidth, float drawHeight, int color) {
        if (NativeTerrain.draw(page, left, top, width, height, x, y, drawWidth, drawHeight, color) == 0) {
            throw new IllegalStateException("Citro2D could not draw an upstream terrain region: "
                + page + "," + left + "," + top + "+" + width + "x" + height);
        }
    }

    @Override
    public void end() {
        int draws = NativeTerrain.end();
        if (draws == 0) throw new IllegalStateException("Citro2D terrain frame has no draws");
        System.out.println("UPSTREAM_TERRAIN_FRAME draws=" + draws);
    }

    @Include(value = "m3ds_terrain_renderer.h", isSystem = true)
    private static final class NativeTerrain {
        @Import(name = "m3ds_terrain_begin")
        static native int begin(int left, int bottom, int width, int height, int tileSize);

        @Import(name = "m3ds_terrain_draw")
        static native int draw(int page, int left, int top, int width, int height,
                               float x, float y, float drawWidth, float drawHeight, int color);

        @Import(name = "m3ds_terrain_end")
        static native int end();
    }
}
