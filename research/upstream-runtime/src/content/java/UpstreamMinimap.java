import arc.graphics.Color;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

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
                Tile tile = Vars.world.tile(tx, ty);
                int r = 32, g = 36, b = 40;
                if (tile != null) {
                    var block = tile.block();
                    // Block.minimapColor only covers blocks with custom minimap
                    // colors; getColor falls back to the loaded mapColor for
                    // floors and ordinary walls.
                    Color color = block != Blocks.air
                        ? block.getColor(tile)
                        : tile.floor().getColor(tile);
                    if (color != null) {
                        r = channel(color.r);
                        g = channel(color.g);
                        b = channel(color.b);
                    }
                    if (tile.build instanceof CoreBuild) {
                        r = 70;
                        g = 220;
                        b = 220;
                    }
                    if (player != null && player.tileX() == tile.x && player.tileY() == tile.y) {
                        r = 255;
                        g = 255;
                        b = 255;
                    }
                }
                int index = (y * size + x) * 4;
                pixels[index] = (byte) r;
                pixels[index + 1] = (byte) g;
                pixels[index + 2] = (byte) b;
                pixels[index + 3] = (byte) 255;
            }
        }
        return pixels;
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    public static long hash(byte[] pixels) {
        long hash = 0xcbf29ce484222325L;
        for (byte pixel : pixels) {
            hash = (hash ^ (pixel & 0xff)) * 0x100000001b3L;
        }
        return hash;
    }
}
