import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.type.Category;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild;
import mindustry.world.blocks.defense.turrets.ContinuousLiquidTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.TractorBeamTurret.TractorBeamBuild;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;
import mindustry.world.blocks.liquid.LiquidRouter;
import mindustry.world.blocks.power.BeamNode;
import mindustry.world.blocks.power.BeamNode.BeamNodeBuild;
import mindustry.world.blocks.power.ConsumeGenerator.ConsumeGeneratorBuild;
import mindustry.world.blocks.power.ImpactReactor.ImpactReactorBuild;
import mindustry.world.blocks.power.NuclearReactor.NuclearReactorBuild;
import mindustry.world.blocks.power.PowerGenerator.GeneratorBuild;
import mindustry.world.blocks.power.VariableReactor.VariableReactorBuild;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;
import mindustry.world.blocks.production.Pump;
import mindustry.world.blocks.production.Pump.PumpBuild;
import mindustry.world.blocks.production.Separator;
import mindustry.world.blocks.production.Separator.SeparatorBuild;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;
import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;

/** Exercise stock material and power building draw passes using the pinned atlas. */
public final class UpstreamOfflineMaterialsReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private int sprites;
    private int beamSegments;
    private AtlasRegion beamRegion;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected Glacier, sprites.aatls and block_colors.png");
        var session = UpstreamSinglePlayerSession.create(new Fi("."), "en");
        try {
            session.start(new Fi(args[0]), null, "map");
            UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), UpstreamFluidAtlas::load);
            Vars.content.loadColors(new Fi(args[2]));
            UpstreamCrackAtlas.load();
            verifyRegistry();
            verifyTurretRegistry();
        } finally {
            session.shutdown();
        }
    }

    private static void verifyRegistry() {
        var target = new UpstreamOfflineMaterialsReference();
        var viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        viewport.moveTo(24, 30);
        var tile = Vars.world.tile(31, 36);
        var viewer = Team.sharded;
        var layers = new UpstreamBuildingLayers();
        int tested = 0;
        int powerTested = 0;
        for (var block : Vars.content.blocks()) {
            boolean power = block.category == Category.power;
            boolean material = block instanceof GenericCrafter || block instanceof Separator || block instanceof Pump
                || block instanceof LiquidRouter || block instanceof StorageBlock && !(block instanceof CoreBlock);
            if (!power && !material) continue;
            tile.setBlock(block, viewer, 0);
            var build = tile.build;
            try {
                if (!UpstreamSinglePlayerApp.supportsBuildingDraw(build)) {
                    throw new AssertionError("The offline draw pass excludes " + block.name);
                }
                target.verifyDraw(layers, viewport, viewer, build, "idle");
                activate(build);
                target.verifyDraw(layers, viewport, viewer, build, "working");
                if (power) {
                    if (!UpstreamRenderedNetworkProbe.supportsBuildingDraw(build)) {
                        throw new AssertionError("The multiplayer draw pass excludes " + block.name);
                    }
                    powerTested++;
                }
                if (material) tested++;
            } finally {
                tile.setBlock(Blocks.air);
            }
        }
        if (tested == 0) throw new AssertionError("The stock material registry was empty");
        if (powerTested == 0) throw new AssertionError("The stock power registry was empty");
        target.verifyBeam(layers, viewport, viewer);
        System.out.println("UPSTREAM_OFFLINE_MATERIAL_DRAW tested=" + tested);
        System.out.println("UPSTREAM_STOCK_POWER_DRAW tested=" + powerTested);
    }

    private void verifyBeam(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport, Team viewer) {
        var first = Vars.world.tile(31, 36);
        var second = Vars.world.tile(31, 40);
        first.setBlock(Blocks.beamNode, viewer, 0);
        second.setBlock(Blocks.beamNode, viewer, 0);
        try {
            var from = (BeamNodeBuild)first.build;
            var to = (BeamNodeBuild)second.build;
            from.updateDirections();
            to.updateDirections();
            beamRegion = (AtlasRegion)((BeamNode)from.block).laser;
            beamSegments = 0;
            layers.render(viewport, viewer, candidate -> candidate == from || candidate == to);
            if (beamSegments != 1) throw new AssertionError("Expected one stock beam, got " + beamSegments);
        } finally {
            first.setBlock(Blocks.air);
            second.setBlock(Blocks.air);
        }
    }

    private static void verifyTurretRegistry() {
        var target = new UpstreamOfflineMaterialsReference();
        var viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        viewport.moveTo(24, 30);
        var tile = Vars.world.tile(31, 36);
        var viewer = Team.sharded;
        var layers = new UpstreamBuildingLayers();
        int tested = 0;
        for (var block : Vars.content.blocks()) {
            if (!(block instanceof BaseTurret) && block.category != Category.turret) continue;
            tile.setBlock(block, viewer, 0);
            try {
                var build = tile.build;
                if (!UpstreamSinglePlayerApp.supportsBuildingDraw(build)
                        || !UpstreamRenderedNetworkProbe.supportsBuildingDraw(build)) {
                    throw new AssertionError("An ARM11 draw pass excludes " + block.name);
                }
                target.verifyDraw(layers, viewport, viewer, build, "idle");
                activateTurret((BaseTurretBuild)build);
                target.verifyDraw(layers, viewport, viewer, build, "active");
                tested++;
            } finally {
                tile.setBlock(Blocks.air);
            }
        }
        if (tested == 0) throw new AssertionError("The stock turret registry was empty");
        System.out.println("UPSTREAM_STOCK_TURRET_DRAW tested=" + tested);
    }

    private static void activateTurret(BaseTurretBuild build) {
        build.rotation = 65f;
        build.efficiency = 1f;
        if (build instanceof TurretBuild turret) {
            turret.heat = 0.7f;
            turret.curRecoil = 0.6f;
            turret.charge = 0.65f;
            turret.shootWarmup = 0.8f;
        }
        if (build instanceof TractorBeamBuild tractor) {
            tractor.any = true;
            tractor.strength = 0.8f;
            tractor.lastX = build.x - 20f;
            tractor.lastY = build.y - 16f;
        }
        if (build.block instanceof ItemTurret item) {
            build.handleItem(build, item.ammoTypes.keys().next());
        } else if (build.block instanceof LiquidTurret liquid) {
            build.liquids.add(liquid.ammoTypes.keys().next(), build.block.liquidCapacity / 2f);
        } else if (build.block instanceof ContinuousLiquidTurret liquid) {
            build.liquids.add(liquid.ammoTypes.keys().next(), build.block.liquidCapacity / 2f);
        }
    }

    private static void activate(Building build) {
        Time.setInternalTime(42f);
        build.efficiency = 1f;
        if (build.items != null) build.items.add(Items.copper, 1);
        if (build.liquids != null) {
            build.liquids.add(Liquids.water, build.block.liquidCapacity / 2);
        }
        if (build.power != null) build.power.status = 0.6f;
        if (build instanceof GeneratorBuild generator) generator.productionEfficiency = 0.8f;
        if (build instanceof ConsumeGeneratorBuild generator) {
            generator.warmup = 0.8f;
            generator.totalTime = 42f;
        } else if (build instanceof ImpactReactorBuild reactor) {
            reactor.warmup = 0.8f;
            reactor.totalProgress = 42f;
        } else if (build instanceof NuclearReactorBuild reactor) {
            reactor.heat = 0.8f;
        } else if (build instanceof VariableReactorBuild reactor) {
            reactor.instability = 0.8f;
            reactor.warmup = 0.8f;
            reactor.totalProgress = 42f;
        }
        if (build instanceof GenericCrafterBuild crafter) {
            crafter.progress = 0.5f;
            crafter.warmup = 1f;
            crafter.totalProgress = 42f;
        } else if (build instanceof PumpBuild pump) {
            pump.liquidDrop = Liquids.water;
            pump.warmup = 1f;
            pump.totalProgress = 42f;
        } else if (build instanceof SeparatorBuild separator) {
            separator.progress = 0.5f;
            separator.warmup = 1f;
            separator.totalProgress = 42f;
        }
    }

    private void verifyDraw(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                            Team viewer, Building build, String state) {
        sprites = 0;
        try {
            layers.render(viewport, viewer, candidate -> candidate == build
                && UpstreamSinglePlayerApp.supportsBuildingDraw(candidate));
            if (sprites == 0) throw new AssertionError("No stock atlas sprites");
        } catch (RuntimeException | AssertionError failure) {
            throw new AssertionError(build.block.name + " (" + state + ")", failure);
        }
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, Blending blending) {
        var error = (AtlasRegion)Core.atlas.find("error");
        if (beamRegion != null && page == UpstreamTerrainDrawProbe.pageIndex(beamRegion)
                && left == beamRegion.getX() && top == beamRegion.getY()) {
            beamSegments++;
        }
        if (page != UpstreamTerrainDrawProbe.pageIndex(error) || left != error.getX() || top != error.getY()) {
            sprites++;
        }
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void drawSolidQuad(float[] vertices, int offset, Blending blending) {}
    @Override public void end() {}
}
