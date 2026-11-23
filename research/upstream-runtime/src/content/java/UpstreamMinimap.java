import mindustry.Vars;

/** Samples the live world into an RGBA8 minimap grid using stock block colors. */
public final class UpstreamMinimap {
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
                int index = (y * size + x) * 4;
                pixels[index] = (byte) (color >>> 24);
                pixels[index + 1] = (byte) (color >>> 16);
                pixels[index + 2] = (byte) (color >>> 8);
                pixels[index + 3] = (byte) color;
            }
        }
        return pixels;
    }

    public static long hash(byte[] pixels) {
        long hash = 0xcbf29ce484222325L;
        for (byte pixel : pixels) {
            hash = (hash ^ (pixel & 0xff)) * 0x100000001b3L;
        }
        return hash;
    }
}
