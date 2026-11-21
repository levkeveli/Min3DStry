import arc.graphics.g2d.Draw;
import arc.struct.IntSet;
import arc.struct.Seq;
import java.util.function.Predicate;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.BuildingCacheLayer;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Layer;
import mindustry.world.Block;

/** Arc draw commands for the cached and dynamic passes in upstream BlockRenderer. */
final class UpstreamBuildingLayers {
    private final Seq<Building> visible = new Seq<>();
    private final IntSet visited = new IntSet();

    void render(UpstreamTerrainDrawProbe.Viewport viewport, Team viewer, Predicate<Block> supported) {
        int right = Math.min(viewport.left + UpstreamTerrainDrawProbe.VIEW_WIDTH, Vars.world.width());
        int top = Math.min(viewport.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT, Vars.world.height());
        for (int y = viewport.bottom; y < top; y++) {
            for (int x = viewport.left; x < right; x++) {
                var build = Vars.world.tile(x, y).build;
                if (build != null && visited.add(build.pos()) && supported.test(build.block)
                        && (!build.inFogTo(viewer) || build.wasVisible)) {
                    visible.add(build);
                }
            }
        }
        visited.clear();
        Draw.sort(true);
        try {
            for (var layer : BuildingCacheLayer.all) {
                Draw.draw(layer.layer, () -> drawCached(layer, viewer));
            }
            for (var build : visible) {
                var block = build.block;
                if (block.drawDynamic && block.cacheLayer == CacheLayer.normal) {
                    Draw.z(Layer.block);
                    block.drawBase(build.tile);
                    Draw.reset();
                    if (build.team != viewer && block.drawTeamOverlay) build.drawTeam();
                }
                Draw.reset();
            }
        } finally {
            try {
                Draw.sort(false);
            } finally {
                visible.clear();
            }
        }
    }

    private void drawCached(BuildingCacheLayer layer, Team viewer) {
        for (var build : visible) {
            var block = build.block;
            if (block.drawCached && block.buildingCacheLayer == layer) {
                block.drawBaseCached(build.tile);
                Draw.reset();
                if (build.team != viewer && block.drawTeamOverlay) build.drawTeam();
            }
        }
        Draw.reset();
    }
}
