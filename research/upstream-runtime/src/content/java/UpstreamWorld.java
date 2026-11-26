import mindustry.core.World;
import mindustry.world.Tile;
import mindustry.world.Tiles;

import static mindustry.Vars.darkRadius;

final class UpstreamWorld extends World {
    @Override
    public void addDarkness(Tiles tiles) {
        for (Tile tile : tiles) {
            if (tile.isDarkened()) {
                tile.data = wallDarknessFromEdge(tiles, tile.x, tile.y);
            }
        }
    }

    private static byte wallDarknessFromEdge(Tiles tiles, int x, int y) {
        // Each stock pass propagates a lower value by one cardinal step.
        // The extra ring accounts for the final full-darkness neighbor check.
        for (int distance = 1; distance <= darkRadius + 1; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                int dy = distance - Math.abs(dx);
                if (isClear(tiles, x + dx, y + dy) || (dy != 0 && isClear(tiles, x + dx, y - dy))) {
                    return (byte)(distance - 1);
                }
            }
        }
        return (byte)(darkRadius + 1);
    }

    private static boolean isClear(Tiles tiles, int x, int y) {
        return tiles.in(x, y) && !tiles.getn(x, y).isDarkened();
    }
}
