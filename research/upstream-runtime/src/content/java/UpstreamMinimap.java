import arc.graphics.Color;
import arc.math.Mathf;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.io.MapIO;
import mindustry.world.Block;
import mindustry.world.Tile;

/** Samples the live world into an RGBA8 minimap grid using the stock color path. */
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
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int tx = Math.min(worldWidth - 1, x * worldWidth / size);
                int ty = Math.min(worldHeight - 1, y * worldHeight / size);
                Tile tile = Vars.world.tile(tx, ty);
                int packed = colorFor(tile);
                int index = (y * size + x) * 4;
                pixels[index] = (byte) (packed >>> 24);
                pixels[index + 1] = (byte) (packed >>> 16);
                pixels[index + 2] = (byte) (packed >>> 8);
                pixels[index + 3] = (byte) 255;
            }
        }
        int player = playerIndex(size);
        if (player >= 0) {
            int px = player % size;
            int py = player / size;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    int x = px + dx;
                    int y = py + dy;
                    if (x >= size || y >= size) continue;
                    int index = (y * size + x) * 4;
                    pixels[index] = (byte) 255;
                    pixels[index + 1] = (byte) 255;
                    pixels[index + 2] = (byte) 255;
                    pixels[index + 3] = (byte) 255;
                }
            }
        }
        return pixels;
    }

    /** Returns the sampled minimap cell occupied by the local player, or -1. */
    public static int playerIndex(int size) {
        if (size <= 0 || Vars.world == null || Vars.player == null) return -1;
        var unit = Vars.player.unit();
        if (unit == null) return -1;
        return cellIndex(size, Vars.world.width(), Vars.world.height(), unit.tileX(), unit.tileY());
    }

    public static int cellIndex(int size, int worldWidth, int worldHeight, int tileX, int tileY) {
        if (size <= 0 || worldWidth <= 0 || worldHeight <= 0) return -1;
        int x = Math.max(0, Math.min(size - 1, tileX * size / worldWidth));
        int y = Math.max(0, Math.min(size - 1, tileY * size / worldHeight));
        return y * size + x;
    }

    private static int colorFor(Tile tile) {
        if (tile == null) return 0;
        Block block = realBlock(tile);
        int color = block.minimapColor(tile);
        if (color == 0 && block == Blocks.air && tile.overlay() == Blocks.air) {
            color = tile.floor().minimapColor(tile);
        }
        Color out = Tmp.c1.set(color != 0 ? color
            : MapIO.colorFor(block, tile.floor(), tile.overlay(), tile.team()));
        out.mul(1f - Mathf.clamp(Vars.world.getDarkness(tile.x, tile.y) / 4f));
        if (block == Blocks.air && tile.y < Vars.world.height() - 1
                && realBlock(Vars.world.tile(tile.x, tile.y + 1)).solid) {
            out.mul(0.7f);
        } else if (tile.floor().isLiquid
                && (tile.y >= Vars.world.height() - 1
                    || !Vars.world.tile(tile.x, tile.y + 1).floor().isLiquid)) {
            out.mul(0.84f, 0.84f, 0.9f, 1f);
        }
        return out.rgba();
    }

    private static Block realBlock(Tile tile) {
        if (tile.build == null) return tile.block();
        return Vars.state.rules.fog && !tile.build.wasVisible ? Blocks.air : tile.block();
    }

    public static long hash(byte[] pixels) {
        long hash = 0xcbf29ce484222325L;
        for (byte pixel : pixels) {
            hash = (hash ^ (pixel & 0xff)) * 0x100000001b3L;
        }
        return hash;
    }
}
