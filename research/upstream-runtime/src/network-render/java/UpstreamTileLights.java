import arc.Events;
import arc.math.geom.QuadTree;
import arc.math.geom.Rect;
import arc.struct.IntMap;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.TileChangeEvent;
import mindustry.game.EventType.TileFloorChangeEvent;
import mindustry.game.EventType.TileOverlayChangeEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.CacheLayer;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import port.render.UpstreamBuildingLights;

/** Adapts the stock BlockRenderer lightview indices to the handheld viewport. */
final class UpstreamTileLights {
    private static final UpstreamTileLights instance = new UpstreamTileLights();

    private final Rect view = new Rect();
    private Tiles tiles;
    private LightTree floors, overlays, blocks;

    private UpstreamTileLights() {
        Events.on(WorldLoadEvent.class, event -> {
            tiles = null;
            floors = overlays = blocks = null;
        });
        Events.on(TileChangeEvent.class, event -> {
            if (indexed()) index(event.tile);
        });
        Events.on(TileFloorChangeEvent.class, event -> {
            if (indexed()) indexEnvironment(floors, event.tile, event.floor);
        });
        Events.on(TileOverlayChangeEvent.class, event -> {
            if (indexed()) indexEnvironment(overlays, event.tile, event.overlay);
        });
    }

    static void collect(UpstreamTerrainDrawProbe.Viewport viewport, Team viewer) {
        if (!UpstreamBuildingLights.isCollecting()) return;
        instance.collectVisible(viewport, viewer);
    }

    private boolean indexed() {
        return tiles != null && tiles == Vars.world.tiles && !Vars.world.isGenerating();
    }

    private void collectVisible(UpstreamTerrainDrawProbe.Viewport viewport, Team viewer) {
        if (!indexed()) {
            tiles = Vars.world.tiles;
            var bounds = new Rect(0, 0, Vars.world.unitWidth(), Vars.world.unitHeight());
            floors = new LightTree(bounds, false);
            overlays = new LightTree(bounds, false);
            blocks = new LightTree(bounds, true);
            for (Tile tile : tiles) index(tile);
        }
        float size = Vars.tilesize;
        view.set((viewport.left - 0.5f) * size, (viewport.bottom - 0.5f) * size,
            viewport.width() * size, viewport.height() * size).grow(size * 2f);
        // Match the original: floor hits, overlay hits, then block hits.
        floors.tree.intersect(view, entry -> draw(entry.tile, viewer));
        overlays.tree.intersect(view, entry -> draw(entry.tile, viewer));
        blocks.tree.intersect(view, entry -> {
            if (entry.type.emitLight) draw(entry.tile, viewer);
        });
    }

    private void index(Tile tile) {
        Block block = tile.block();
        blocks.set(tile, tile.isCenter() && block != Blocks.air && block.cacheLayer == CacheLayer.normal ? block : null);
        indexEnvironment(floors, tile, tile.floor());
        indexEnvironment(overlays, tile, tile.overlay());
    }

    private static void indexEnvironment(LightTree tree, Tile tile, Block type) {
        tree.set(tile, type.emitLight && !tile.block().obstructsLight && Vars.world.getDarkness(tile.x, tile.y) < 3
            ? type : null);
    }

    private static void draw(Tile tile, Team viewer) {
        Block block = tile.block();
        Building build = tile.build;
        if (build == null) {
            if (block.emitLight) block.drawEnvironmentLight(tile);
        } else if (build.wasVisible || !build.inFogTo(viewer)) {
            build.drawLight();
        }
        if (block.obstructsLight) return;
        var floor = tile.floor();
        var overlay = tile.overlay();
        if (!floor.obstructsLight && overlay.emitLight) overlay.drawEnvironmentLight(tile);
        if (floor.forceDrawLight || (!overlay.obstructsLight && floor.emitLight)) floor.drawEnvironmentLight(tile);
    }

    private static final class LightTree {
        final QuadTree<Entry> tree;
        final IntMap<Entry> byTile = new IntMap<>();
        final boolean useOffset;

        LightTree(Rect bounds, boolean useOffset) {
            tree = new QuadTree<>(bounds);
            this.useOffset = useOffset;
        }

        void set(Tile tile, Block type) {
            int key = tile.pos();
            Entry before = byTile.remove(key);
            if (before != null) tree.remove(before);
            if (type == null) return;
            float offset = useOffset ? type.offset : 0f;
            // Keep the old bounds through TileFloorChangeEvent/TileOverlayChangeEvent.
            Entry entry = new Entry(tile, type, tile.worldx() + offset, tile.worldy() + offset, type.lightClipSize);
            byTile.put(key, entry);
            tree.insert(entry);
        }
    }

    private record Entry(Tile tile, Block type, float x, float y, float size) implements QuadTree.QuadTreeObject {
        @Override
        public void hitbox(Rect out) {
            out.setCentered(x, y, size, size);
        }
    }
}
