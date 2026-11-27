import arc.Events;
import java.util.BitSet;
import mindustry.core.World;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.graphics.BlockRenderer;
import mindustry.world.Tile;
import mindustry.world.Tiles;

/** Visible texels of the pinned BlockRenderer tile-shadow mask. */
final class UpstreamTileShadows {
    // The stock batch packs the brightness into an RGBA8 vertex color.
    private static final int shadowAlpha = 255 - (int)(BlockRenderer.blendShadowColor.r * 255f);
    private static final UpstreamTileShadows fog = new UpstreamTileShadows();

    private Tiles tiles;
    private Team viewer;
    private BitSet remembered;

    private UpstreamTileShadows() {
        Events.on(WorldLoadEvent.class, event -> clear());
    }

    static boolean sample(World world, Rules rules, Team viewer, int left, int bottom,
                          int width, int height, byte[] alpha) {
        if (rules.fog) fog.prepare(world, viewer);
        else fog.clear();

        boolean covered = false;
        int offset = 0;
        // A one-texel margin keeps the original linear filtering at the camera bounds.
        for (int row = height; row >= -1; row--) {
            int y = Math.max(0, Math.min(world.height() - 1, bottom + row));
            for (int col = -1; col <= width; col++) {
                int x = Math.max(0, Math.min(world.width() - 1, left + col));
                Tile tile = world.tile(x, y);
                boolean shadow = rules.fog ? fog.visibleShadow(tile) : tile.block().displayShadow(tile);
                alpha[offset++] = (byte)(shadow ? shadowAlpha : 0);
                covered |= shadow;
            }
        }
        return covered;
    }

    private void prepare(World world, Team team) {
        if (tiles == world.tiles && viewer == team) return;
        tiles = world.tiles;
        viewer = team;
        remembered = new BitSet(world.width() * world.height());
        for (Tile tile : tiles) {
            var build = tile.build;
            if (tile.block().displayShadow(tile)
                    && (build == null || build.team == team || (build.visibleFlags & (1L << team.id)) != 0)) {
                remembered.set(index(tile));
            }
        }
    }

    private boolean visibleShadow(Tile tile) {
        var build = tile.build;
        int index = index(tile);
        // Stock TileChangeEvent skips hidden arrivals. Keep the prior texel until the
        // building is revealed. Empty cells and previously discovered buildings still update.
        if (build == null || build.team == viewer || (build.visibleFlags & (1L << viewer.id)) != 0
                || !build.inFogTo(viewer)) {
            remembered.set(index, tile.block().displayShadow(tile));
        }
        return remembered.get(index);
    }

    private int index(Tile tile) {
        return tile.x + tile.y * tiles.width;
    }

    private void clear() {
        tiles = null;
        viewer = null;
        remembered = null;
    }
}
