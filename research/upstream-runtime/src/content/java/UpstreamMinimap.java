import arc.math.Mathf;
import mindustry.Vars;

/** Samples the live world into an RGBA8 minimap grid using stock block colors. */
public final class UpstreamMinimap {
    private static final float STOCK_BASE_SIZE = 16f;
    private static final float STOCK_DEFAULT_ZOOM = 4f;

    record Region(float left, float bottom, float span) { }

    private UpstreamMinimap() {
    }

    public static byte[] sample(int size) {
        if (size <= 0 || Vars.world == null || Vars.world.width() <= 0 || Vars.world.height() <= 0) {
            return null;
        }
        int worldWidth = Vars.world.width();
        int worldHeight = Vars.world.height();
        byte[] pixels = new byte[size * size * 4];
        var player = Vars.player == null ? null : Vars.player.unit();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int tx = Math.min(worldWidth - 1, x * worldWidth / size);
                int ty = Math.min(worldHeight - 1, y * worldHeight / size);
                int color = player != null && player.tileX() == tx && player.tileY() == ty
                    ? 0xffffffff : UpstreamMinimapColors.colorFor(Vars.world.tile(tx, ty));
                write(pixels, (y * size + x) * 4, color);
            }
        }
        return pixels;
    }

    // MinimapRenderer.reset(), setZoom() and getRegion() from pinned v160.8.
    static Region region(int worldWidth, int worldHeight, float cameraTileX, float cameraTileY) {
        int smallerSide = Math.min(worldWidth, worldHeight);
        float zoom = Mathf.clamp(STOCK_DEFAULT_ZOOM, 1f, smallerSide / STOCK_BASE_SIZE / 2f);
        float radius = Mathf.clamp(STOCK_BASE_SIZE * zoom, STOCK_BASE_SIZE, smallerSide);
        float centerX = Mathf.clamp(cameraTileX, radius, worldWidth - radius);
        float centerY = Mathf.clamp(cameraTileY, radius, worldHeight - radius);
        return new Region(centerX - radius, centerY - radius, radius * 2f);
    }

    static void sampleRegion(byte[] pixels, int size, Region region) {
        int lastX = Vars.world.width() - 1, lastY = Vars.world.height() - 1;
        float step = region.span / size;
        for (int y = 0; y < size; y++) {
            int tileY = Math.min(lastY, (int) (region.bottom + (y + 0.5f) * step));
            for (int x = 0; x < size; x++) {
                int tileX = Math.min(lastX, (int) (region.left + (x + 0.5f) * step));
                int color = UpstreamMinimapColors.colorFor(Vars.world.tile(tileX, tileY));
                write(pixels, (y * size + x) * 4, color);
            }
        }
        var unit = Vars.player == null ? null : Vars.player.unit();
        if (unit != null) {
            int x = Mathf.floor((unit.x / Vars.tilesize + 0.5f - region.left) / step);
            int y = Mathf.floor((unit.y / Vars.tilesize + 0.5f - region.bottom) / step);
            if (x >= 0 && y >= 0 && x < size && y < size) write(pixels, (y * size + x) * 4, 0xffffffff);
        }
    }

    private static void write(byte[] pixels, int index, int color) {
        pixels[index] = (byte) (color >>> 24);
        pixels[index + 1] = (byte) (color >>> 16);
        pixels[index + 2] = (byte) (color >>> 8);
        pixels[index + 3] = (byte) color;
    }

    public static long hash(byte[] pixels) {
        long hash = 0xcbf29ce484222325L;
        for (byte pixel : pixels) {
            hash = (hash ^ (pixel & 0xff)) * 0x100000001b3L;
        }
        return hash;
    }
}
