import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.Tile;

/** Samples the live world into an RGBA8 minimap grid using the stock color path. */
public final class UpstreamMinimap {
    private UpstreamMinimap() {
    }

    public static byte[] sample(int size) {
        return sample(size, true);
    }

    public static byte[] sample(int size, boolean playerMarker) {
        byte[] pixels = sampleTerrain(size);
        if (pixels == null) return null;
        int worldWidth = Vars.world.width();
        int worldHeight = Vars.world.height();
        var localTeam = Vars.player == null ? null : Vars.player.team();
        var playerUnit = Vars.player == null ? null : Vars.player.unit();
        if (localTeam != null) {
            for (CoreBuild core : localTeam.cores()) {
                int coreCell = cellIndex(size, worldWidth, worldHeight, core.tileX(), core.tileY());
                if (coreCell < 0) continue;
                int x = coreCell % size;
                int y = coreCell / size;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        putBlip(pixels, size, x + dx, y + dy,
                            localTeam.color.r, localTeam.color.g, localTeam.color.b);
                    }
                }
            }
        }
        for (Unit unit : Groups.unit) {
            if (unit == playerUnit || unit.dead || !unit.isAdded() || !unit.type.drawMinimap) continue;
            if (localTeam != null && unit.inFogTo(localTeam)) continue;
            int unitCell = cellIndex(size, worldWidth, worldHeight, unit.tileX(), unit.tileY());
            if (unitCell < 0) continue;
            var color = unit.team.color;
            putBlip(pixels, size, unitCell % size, unitCell / size, color.r, color.g, color.b);
        }
        int player = playerMarker ? playerIndex(size) : -1;
        var playerModel = Vars.player == null ? null : Vars.player.unit();
        if (player >= 0 && playerModel != null) {
            int px = player % size;
            int py = player / size;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    putBlip(pixels, size, px + dx, py + dy, 1f, 1f, 1f);
                }
            }
            int tipX = Math.round(px + 0.5f + arc.math.Mathf.cosDeg(playerModel.rotation()) * 2.5f);
            int tipY = Math.round(py + 0.5f + arc.math.Mathf.sinDeg(playerModel.rotation()) * 2.5f);
            putBlip(pixels, size, tipX, tipY, 1f, 1f, 1f);
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

    static byte[] sampleTerrain(int size) {
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
                int packed = UpstreamMinimapColors.colorFor(tile);
                int index = (y * size + x) * 4;
                pixels[index] = (byte) (packed >>> 24);
                pixels[index + 1] = (byte) (packed >>> 16);
                pixels[index + 2] = (byte) (packed >>> 8);
                pixels[index + 3] = (byte) packed;
            }
        }
        return pixels;
    }

    private static void putBlip(byte[] pixels, int size, int x, int y, float r, float g, float b) {
        if (x < 0 || y < 0 || x >= size || y >= size) return;
        int index = (y * size + x) * 4;
        pixels[index] = (byte) channel(r);
        pixels[index + 1] = (byte) channel(g);
        pixels[index + 2] = (byte) channel(b);
        pixels[index + 3] = (byte) 255;
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
