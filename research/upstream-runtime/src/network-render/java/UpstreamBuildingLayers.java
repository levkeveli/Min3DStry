import arc.Core;
import arc.graphics.Blending;
import arc.graphics.g2d.Draw;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import java.util.function.Predicate;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Puddle;
import mindustry.graphics.BuildingCacheLayer;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Layer;
import mindustry.type.Category;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.blocks.heat.HeatConductor;
import mindustry.world.meta.BuildVisibility;
import mindustry.world.blocks.liquid.LiquidRouter;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.power.BeamNode.BeamNodeBuild;
import mindustry.world.blocks.power.LightBlock;
import mindustry.world.blocks.power.PowerBlock;
import mindustry.world.blocks.power.PowerDiode;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.Pump;
import mindustry.world.blocks.production.Separator;
import mindustry.world.blocks.production.WallCrafter;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;

/** Arc draw commands for the cached and dynamic passes in upstream BlockRenderer. */
final class UpstreamBuildingLayers {
    private final Seq<Building> visible = new Seq<>();
    private final IntSet visited = new IntSet();
    private final ObjectSet<UnitType> factoryGraphics = new ObjectSet<>();

    static boolean supportsPowerDraw(Block block) {
        return block instanceof PowerBlock || block instanceof PowerDiode || block instanceof LightBlock;
    }

    static boolean supportsDistributionDraw(Block block) {
        // Uncatalogued blocks also default to the distribution category.
        return block.category == Category.distribution && block.buildVisibility != BuildVisibility.hidden;
    }

    static boolean supportsMaterialDraw(Block block) {
        return block instanceof GenericCrafter || block instanceof HeatConductor || block instanceof Separator
            || block instanceof Pump || block instanceof LiquidRouter || block instanceof StorageBlock;
    }

    static boolean supportsExtractorDraw(Block block) {
        return block instanceof Drill || block instanceof BeamDrill || block instanceof WallCrafter;
    }

    void render(UpstreamTerrainDrawProbe.Viewport viewport, Team viewer, Predicate<Building> supported) {
        int right = Math.min(viewport.left + viewport.width(), Vars.world.width());
        int top = Math.min(viewport.bottom + viewport.height(), Vars.world.height());
        boolean live = Vars.net != null && Vars.net.client();
        for (int y = viewport.bottom; y < top; y++) {
            for (int x = viewport.left; x < right; x++) {
                var build = Vars.world.tile(x, y).build;
                if (build == null || !visited.add(build.pos())) continue;
                if (!build.inFogTo(viewer)) {
                    // The stock draw pass records revealed buildings. Beam nodes consult
                    // this state on the destination building before drawing a connection.
                    build.visibleFlags |= 1L << viewer.id;
                    build.wasVisible = true;
                }
                if (build.wasVisible && supported.test(build)) {
                    visible.add(build);
                    // The live snapshot does not carry BeamNode's cached endpoints.
                    // Refresh them using the stock tile-change and link rules.
                    if (live && build instanceof BeamNodeBuild beam) beam.updateTile();
                    if (build instanceof UnitFactoryBuild factory) {
                        loadFactoryGraphics(factory.unit());
                        if (factory.getPayload() instanceof UnitPayload payload) {
                            loadFactoryGraphics(payload.unit.type());
                        }
                    }
                }
            }
        }
        visited.clear();
        Draw.sort(true);
        try {
            drawPuddles(viewport);
            for (var layer : BuildingCacheLayer.all) {
                Draw.draw(layer.layer, () -> drawCached(layer, viewer));
            }
            for (var build : visible) {
                var block = build.block;
                if (block.drawDynamic && block.cacheLayer == CacheLayer.normal) {
                    // Streamed conveyors carry progress and rotation. Reconstruct cargo geometry before drawing.
                    if (build instanceof PayloadConveyorBuild conveyor) conveyor.updatePayload();
                    Draw.z(Layer.block);
                    block.drawBase(build.tile);
                    Draw.reset();
                    drawCracks(build);
                    Draw.z(Layer.block);
                    if (build.team != viewer && block.drawTeamOverlay) build.drawTeam();
                }
                Draw.reset();
            }
            for (var build : visible) {
                if (!build.block.drawDynamic) drawCracks(build);
            }
            drawLights(visible);
        } finally {
            try {
                Draw.sort(false);
            } finally {
                visible.clear();
            }
        }
    }

    private static void drawLights(Seq<Building> visible) {
        var region = Core.atlas.find("circle-shadow");
        if (!region.found() || visible.isEmpty()) return;
        Draw.blend(Blending.additive);
        try {
            Draw.z(Layer.effect);
            for (var build : visible) {
                var block = build.block;
                if (!block.emitLight) continue;
                float radius, opacity;
                int color;
                if (build instanceof LightBlock.LightBuild light) {
                    radius = block.lightRadius * Math.min(light.smoothTime, 2f);
                    color = light.color;
                    opacity = ((LightBlock)block).brightness * light.efficiency;
                } else {
                    radius = block.lightRadius;
                    color = block.lightColor.rgba();
                    opacity = block.lightColor.a;
                }
                if (radius <= 0f || opacity <= 0f) continue;
                Draw.color(color);
                Draw.alpha(Math.min(opacity, 1f));
                Draw.rect(region, build.x, build.y, radius * 2f, radius * 2f);
            }
        } finally {
            Draw.blend();
            Draw.reset();
        }
    }

    private static void drawPuddles(UpstreamTerrainDrawProbe.Viewport viewport) {
        float left = (viewport.left - 0.5f) * Vars.tilesize;
        float bottom = (viewport.bottom - 0.5f) * Vars.tilesize;
        float right = left + viewport.width() * Vars.tilesize;
        float top = bottom + viewport.height() * Vars.tilesize;
        for (var entity : Groups.draw) {
            if (!(entity instanceof Puddle puddle) || puddle.tile == null || puddle.liquid == null) continue;
            float radius = puddle.clipSize() / 2f;
            if (puddle.x + radius < left || puddle.x - radius > right
                    || puddle.y + radius < bottom || puddle.y - radius > top) continue;
            // Stock cell liquid places some of its geometry above the host building.
            puddle.draw();
            Draw.reset();
        }
    }

    private void loadFactoryGraphics(UnitType type) {
        if (type == null || !factoryGraphics.add(type)) return;
        // Resolve regions in the loaded stock atlas before submitting building draws.
        type.loadIcon();
        type.load();
    }

    private static void drawCracks(Building build) {
        if (!build.damaged()) return;
        Draw.z(Layer.blockCracks);
        build.drawCracks();
        Draw.reset();
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
