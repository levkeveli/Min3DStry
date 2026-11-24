import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;
import port.render.UpstreamCanvasTexture;
import port.render.UpstreamLogicAtlas;
import port.render.UpstreamLogicFont;
import port.render.UpstreamLogicFramebuffer;
import port.render.UpstreamLogicFramebuffer.RasterTexture;
import port.logic.UpstreamClientLogic;
import port.audio.Arm11AudioReference;
import arc.Core;
import arc.audio.AudioBus;
import arc.audio.Sound;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Camera;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.SpriteBatch;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import arc.graphics.g2d.TextureRegion;
import arc.mock.MockAudio;
import arc.mock.MockGraphics;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import mindustry.Vars;
import mindustry.ui.Fonts;
import mindustry.ai.BlockIndexer;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.content.UnitTypes;
import mindustry.ctype.ContentType;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Builderc;
import mindustry.gen.Groups;
import mindustry.gen.Payloadc;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Crawlc;
import mindustry.gen.DisplayCmd;
import mindustry.gen.Player;
import mindustry.gen.Tankc;
import mindustry.gen.UnitWaterMove;
import mindustry.world.blocks.liquid.Conduit.ConduitBuild;
import mindustry.world.blocks.liquid.LiquidBlock;
import mindustry.game.Team;
import mindustry.game.MapObjectives;
import mindustry.game.MapObjectives.BuildCountObjective;
import mindustry.game.MapObjectives.ItemObjective;
import mindustry.game.MapObjectives.QuadMarker;
import mindustry.game.MapObjectives.TextureMarker;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.logic.LMarkerControl;
import mindustry.type.Category;
import mindustry.world.Block;
import mindustry.world.blocks.defense.OverdriveProjector.OverdriveBuild;
import mindustry.world.blocks.defense.ShockwaveTower.ShockwaveTowerBuild;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.ReloadTurret;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.BeamDrill.BeamDrillBuild;
import mindustry.world.blocks.production.BurstDrill.BurstDrillBuild;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;
import mindustry.world.blocks.production.WallCrafter.WallCrafterBuild;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.logic.CanvasBlock;
import mindustry.world.blocks.logic.CanvasBlock.CanvasBuild;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.logic.LogicBlock.LogicLink;
import mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild;
import mindustry.world.blocks.logic.LogicDisplay;
import mindustry.world.blocks.logic.TileableLogicDisplay.TileableLogicDisplayBuild;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.payloads.PayloadConveyor;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.units.UnitFactory;

public final class UpstreamBuildingLayersReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Sprite(float x, float y, int page, int left, int top, int width, int height, float rotation, int color, int mixColor, Blending blending) {}
    private final List<Sprite> sprites = new ArrayList<>();
    private final List<float[]> solidQuads = new ArrayList<>();
    private record UnitBuild(int page, int left, int top, int width, int height, float x, float y,
                             float rotation, float progress, float alpha, float phase) {}
    private final List<UnitBuild> unitBuilds = new ArrayList<>();
    private final List<UpstreamCanvasTexture> canvasDraws = new ArrayList<>();
    private int texturedQuads;
    private UpstreamCanvasTexture canvasQuadTexture;
    private float[] canvasQuadVertices;
    private final List<RasterTexture> displayDraws = new ArrayList<>();
    private final List<Integer> displayFirstPixels = new ArrayList<>();

    private static void verifyLiveChatInput() {
        var input = new UpstreamChatInput();
        int open = 1 << 22, send = 1 << 17, cancel = 1 << 19;
        if (input.update(send, -1) != null || input.open()) throw new AssertionError("Closed chat");
        input.update(open, -1);
        if (!input.open()) throw new AssertionError("Open chat");
        // Shift, H, ordinary i, space, 3, Backspace, and i again.
        input.update(0, 32 | 214 << 16);
        input.update(0, 176 | 132 << 16);
        input.update(0, 32 | 214 << 16);
        input.update(0, 240 | 93 << 16);
        input.update(0, 192 | 214 << 16);
        input.update(0, 80 | 53 << 16);
        input.update(0, 290 | 214 << 16);
        input.update(0, 240 | 93 << 16);
        if (!"Hi i".equals(input.update(send, -1)) || input.open()) {
            throw new AssertionError("Chat touch editing and submit");
        }
        input.update(open, -1);
        input.update(0, 240 | 93 << 16);
        if (input.update(cancel, -1) != null || input.open()) throw new AssertionError("Cancel chat");
        input.update(open, -1);
        for (int i = 0; i < Vars.maxTextLength + 5; i++) input.update(0, 240 | 93 << 16);
        if (input.update(send, -1).length() != Vars.maxTextLength) throw new AssertionError("Stock chat length");
        System.out.println("UPSTREAM_CHAT_TOUCH_REFERENCE submit=ok cancel=ok stockLimit=" + Vars.maxTextLength);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--packaged-graphics")) {
            verifySpriteAtlas(new Fi(args[2]));
            var font = new Fi(args[1]);
            UpstreamLogicFont.load(font);
            verifyLogicFontPixels(font);
            return;
        }
        if (args.length == 2 && args[0].equals("--logic-font")) {
            var font = new Fi(args[1]);
            UpstreamLogicFont.load(font);
            verifyLogicFontPixels(font);
            return;
        }
        if (args.length < 3 || args.length == 6 || args.length > 7) {
            throw new IllegalArgumentException("Expected Glacier, sprites.aatls, block_colors.png, optional bundle base, logicids.dat, then paired world atlas tiles and logic font");
        }
        if (args.length >= 4) Core.bundle = UpstreamInterfaceLocale.loadForStockClient(new Fi(args[3]), 1, "en");
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(new String[]{args[0]}, () -> Vars.indexer = new BlockIndexer());
        UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), UpstreamFluidAtlas::load);
        Vars.content.loadColors(new Fi(args[2]));
        UpstreamCrackAtlas.load();

        var target = new UpstreamBuildingLayersReference();
        var viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        verifyLiveChatInput();
        target.verifyArcOrder();
        target.verifyTexturedLine();
        target.verifyFactory(viewport);

        // The visible two-tile wall starts one tile west of the viewport.
        viewport.moveTo(24, 30);
        var viewer = Team.sharded;
        if (args.length >= 4) verifyPowerBar(viewer);
        var conveyor = Vars.world.tile(25, 31);
        var edgeWall = Vars.world.tile(23, 35);
        var offscreenWall = Vars.world.tile(21, 39);
        var drill = Vars.world.tile(28, 37);
        conveyor.setBlock(Blocks.conveyor, viewer, 1);
        edgeWall.setBlock(Blocks.copperWallLarge, viewer, 0);
        offscreenWall.setBlock(Blocks.copperWallLarge, viewer, 0);
        drill.setBlock(Blocks.mechanicalDrill, viewer, 0);

        target.sprites.clear();
        var layers = new UpstreamBuildingLayers();
        layers.render(viewport, viewer, build ->
            build.block == Blocks.conveyor || build.block == Blocks.copperWallLarge || build.block == Blocks.mechanicalDrill);

        int cached = target.firstAt(edgeWall.drawx(), edgeWall.drawy());
        int moving = target.firstAt(conveyor.drawx(), conveyor.drawy());
        int producing = target.firstAt(drill.drawx(), drill.drawy());
        long visibleWallSprites = target.sprites.stream()
            .filter(sprite -> sprite.x() == edgeWall.drawx() && sprite.y() == edgeWall.drawy()).count();
        if (visibleWallSprites != 1 || moving < 0 || cached <= moving || producing <= cached
                || target.firstAt(offscreenWall.drawx(), offscreenWall.drawy()) != -1) {
            throw new AssertionError("Unexpected stock building culling or layer order: " + target.sprites);
        }
        System.out.printf("UPSTREAM_BUILDING_LAYERS draws=%d wall=%d conveyor=%d drill=%d%n",
            target.sprites.size(), cached, moving, producing);
        var crack = Core.atlas.find("cracks-2-4");
        edgeWall.build.health = edgeWall.build.maxHealth * 0.5f;
        drill.build.health = drill.build.maxHealth * 0.5f;
        target.sprites.clear();
        layers.render(viewport, viewer, build -> build.block == Blocks.copperWallLarge || build.block == Blocks.mechanicalDrill);
        target.verifyCrack(crack, edgeWall.build);
        target.verifyCrack(crack, drill.build);
        System.out.println("UPSTREAM_BUILDING_CRACKS region=cracks-2-4 wall=1 drill=1");
        target.verifyFluidDrawing(layers, viewport, viewer);
        target.verifyBuildingEffects(layers, viewport, viewer);
        target.verifyPayloadMix(layers, viewport, viewer);
        target.verifyCarriedBuildings(layers, viewport, viewer);
        target.verifyUnits(viewer);
        target.verifyCarriedUnits(layers, viewport, viewer);
        target.verifyNavalWake(viewer);
        target.verifyTankTreads(viewer);
        target.verifyCoreVariants(layers, viewport, viewer);
        target.verifyErekirStarterBlocks(layers, viewport, viewer);
        target.verifyExtractorDraws(layers, viewport, viewer);
        target.verifyLiveBuildingRegistry(layers, viewport, viewer, block -> block instanceof BaseTurret, "TURRET");
        // The stock Liquid Bridge is liquid-category, so the live predicate's
        // Category.liquid branch must keep both bridge blocks drawable.
        if (Blocks.bridgeConduit.category != Category.liquid
                || Blocks.phaseConduit.category != Category.liquid) {
            throw new AssertionError("Stock Liquid Bridge is not in the liquid draw category");
        }
        int power = target.verifyLiveBuildingRegistry(
            layers, viewport, viewer, UpstreamBuildingLayers::supportsPowerDraw, "POWER");
        int distribution = target.verifyLiveBuildingRegistry(
            layers, viewport, viewer, UpstreamBuildingLayers::supportsDistributionDraw, "DISTRIBUTION");
        int liquid = target.verifyLiveBuildingRegistry(
            layers, viewport, viewer, block -> block.category == Category.liquid, "LIQUID");
        int materials = target.verifyLiveBuildingRegistry(layers, viewport, viewer,
            block -> UpstreamBuildingLayers.supportsMaterialDraw(block) && !(block instanceof CoreBlock),
            "MATERIAL");
        if (power != 26) throw new AssertionError("Expected 26 stock power buildings, got " + power);
        if (distribution != 28) {
            throw new AssertionError("Expected 28 stock distribution buildings, got " + distribution);
        }
        if (liquid != 21) throw new AssertionError("Expected 21 stock liquid buildings, got " + liquid);
        if (materials != 52) throw new AssertionError("Expected 52 stock material buildings, got " + materials);
        target.verifyLiquidBridgeTransfer(viewer);
        target.verifyLiquidRouterTransfer(viewer);
        target.verifyLiquidJunctionRouting(viewer);
        target.verifyCanvas(viewer);
        if (args.length >= 4) verifyObjectiveHud();
        if (args.length >= 5) verifyClientProcessor(new Fi(args[4]), viewer);
        if (args.length == 7) target.verifyLogicDisplay(new Fi(args[5]), new Fi(args[6]), viewer);
        target.verifyUnitProjectiles(viewport, viewer);
        verifyPositionalAudioDispatch();
        Arm11AudioReference.verifyStockRegistry();
        UpstreamPuddleReference.verify();
        UpstreamWeatherLayersReference.verify();
    }

    private static void verifyPositionalAudioDispatch() {
        Core.audio = new MockAudio() {
            @Override public boolean initialized() { return true; }
        };
        Core.audio.sfxVolume = 0.8f;
        Core.camera = new Camera();
        Core.camera.width = 128f;
        var sample = new Sound() {
            @Override public boolean valid() { return true; }

            @Override
            protected int playSource(AudioBus bus, float volume, float pitch, float pan, boolean loop) {
                if (bus != Core.audio.soundBus || Math.abs(volume - 0.4f) > 0.001f || pitch != 1.2f
                        || pan != 0.5f || loop) {
                    throw new AssertionError("Arc did not preserve the original positional audio parameters");
                }
                return 23;
            }
        };
        if (sample.at(32f, 0f, 1.2f, 0.5f) != 23) throw new AssertionError("Arc did not return the backend voice");
        System.out.println("UPSTREAM_LIVE_AUDIO_REFERENCE volume=0.4 pitch=1.2 pan=0.5 voice=23");
    }

    private void verifyUnitProjectiles(UpstreamTerrainDrawProbe.Viewport viewport, Team viewer) {
        Vars.net.setClientConnected();
        Core.audio = new MockAudio();
        var gamma = UnitTypes.gamma.spawn(viewer, 648f, 208f, 0f);
        var dagger = UnitTypes.dagger.spawn(viewer, 648f, 224f, 0f);
        Vars.player.unit(gamma);
        var peer = Player.create();
        peer.team(viewer);
        peer.unit(dagger);
        if (!gamma.isLocal() || !dagger.isRemote()) throw new AssertionError("Expected local and remote units");
        UpstreamClientProjectiles.load();
        gamma.aim(730f, 208f, true);
        dagger.aim(730f, 224f, true);
        gamma.controlWeapons(true, true);
        dagger.controlWeapons(true, true);
        float oldDelta = Time.delta;
        Time.delta = 1f;
        try {
            for (int i = 0; i < 7; i++) {
                Time.update();
                UpstreamClientProjectiles.update();
            }
            if (Groups.bullet.count(bullet -> bullet.owner == gamma) != 2
                    || !Groups.bullet.contains(bullet -> bullet.owner == gamma && bullet.trail != null)
                    || !Groups.bullet.contains(bullet -> bullet.owner == dagger && bullet.vel.x > 0f)) {
                throw new AssertionError("The pinned Gamma burst, its trail or the remote Dagger did not advance");
            }
            sprites.clear();
            solidQuads.clear();
            viewport.moveTo(73, 20);
            int count = UpstreamClientProjectiles.draw(viewport);
            if (count < 3 || solidQuads.isEmpty()) throw new AssertionError("Stock projectiles did not draw");
            requireRegion(Core.atlas.find("bullet"));

            gamma.controlWeapons(true, false);
            dagger.controlWeapons(true, false);
            int shots = Arrays.stream(gamma.mounts).mapToInt(mount -> mount.totalShots).sum()
                + Arrays.stream(dagger.mounts).mapToInt(mount -> mount.totalShots).sum();
            for (int i = 0; i < 90; i++) {
                Time.update();
                UpstreamClientProjectiles.update();
            }
            int after = Arrays.stream(gamma.mounts).mapToInt(mount -> mount.totalShots).sum()
                + Arrays.stream(dagger.mounts).mapToInt(mount -> mount.totalShots).sum();
            if (!Groups.bullet.isEmpty() || shots != after) {
                throw new AssertionError("Stock projectiles did not clear after releasing both units");
            }
        } finally {
            Time.delta = oldDelta;
        }
        System.out.println("UPSTREAM_ORIGINAL_PROJECTILES gamma=burst dagger=remote atlas=ok trail=ok release=ok");
    }


    private void verifyFactory(UpstreamTerrainDrawProbe.Viewport viewport) {
        for (var block : Vars.content.blocks()) {
            if (!(block instanceof UnitFactory factory)) continue;
            for (var plan : factory.plans) {
                plan.unit.loadIcon();
                var icon = plan.unit.fullIcon;
                if (icon.width > 128 || icon.height > 128 || !icon.found()) {
                    throw new AssertionError("The original factory icon does not fit the ARM11 scratch atlas: "
                        + plan.unit.name + " " + icon.width + "x" + icon.height);
                }
            }
        }
        viewport.moveTo(24, 30);
        var viewer = Team.sharded;
        var tile = Vars.world.tile(31, 35);
        tile.setBlock(Blocks.groundFactory, viewer, 1);
        var factory = (UnitFactory.UnitFactoryBuild)tile.build;
        var layers = new UpstreamBuildingLayers();
        var oldPlayer = Vars.player;
        Vars.player = Player.create();
        Vars.player.team(viewer);
        try {
            factory.currentPlan = -1;
            sprites.clear();
            unitBuilds.clear();
            layers.render(viewport, viewer, build -> build == factory);
            int idleSprites = sprites.size();
            if (idleSprites != 3 || !unitBuilds.isEmpty()) {
                throw new AssertionError("Expected the original factory base, output and top layers at rest");
            }
            factory.currentPlan = 1;
            factory.progress = ((UnitFactory)factory.block).plans.get(1).time / 2f;
            verifyLiveFactoryClock(factory);
            factory.speedScl = 0.75f;
            factory.time = 40;
            solidQuads.clear();
            layers.render(viewport, viewer, build -> build == factory);
            var icon = UnitTypes.crawler.fullIcon;
            var expected = new UnitBuild(UpstreamTerrainDrawProbe.pageIndex(icon), icon.getX(), icon.getY(),
                icon.width, icon.height, factory.x, factory.y, factory.rotdeg() - 90f, 0.5f, 0.75f, -2f);
            if (!unitBuilds.equals(List.of(expected)) || solidQuads.isEmpty()) {
                throw new AssertionError("The pinned construction sprite or scan line was altered: " + unitBuilds);
            }
            factory.payload = new UnitPayload(UnitTypes.crawler.create(viewer));
            sprites.clear();
            layers.render(viewport, viewer, build -> build == factory);
            if (sprites.size() <= idleSprites) throw new AssertionError("Factory did not draw its stock output payload");
            System.out.println("UPSTREAM_FACTORY_GRAPHICS idle=3 construction=crawler progress=0.5 payload=ok");
        } finally {
            Vars.player = oldPlayer;
            tile.setBlock(Blocks.air);
            sprites.clear();
            solidQuads.clear();
            unitBuilds.clear();
        }
    }

    private static void verifyLiveFactoryClock(UnitFactory.UnitFactoryBuild factory) {
        float delta = Time.delta;
        float progress = factory.progress;
        try {
            Time.delta = 1f;
            factory.efficiency = 1f;
            factory.speedScl = 0f;
            factory.time = 0f;
            UpstreamRenderedNetworkProbe.advanceFactoryDraw(factory);
            UpstreamRenderedNetworkProbe.advanceFactoryDraw(factory);
            if (factory.speedScl <= 0f || factory.time <= 0f || factory.progress != progress
                    || factory.payload != null || factory.currentPlan != 1) {
                throw new AssertionError("The live factory did not animate independently of production");
            }
            float speed = factory.speedScl;
            float clock = factory.time;
            factory.efficiency = 0f;
            UpstreamRenderedNetworkProbe.advanceFactoryDraw(factory);
            if (factory.speedScl >= speed || factory.time != clock || factory.progress != progress) {
                throw new AssertionError("The live factory did not fade out after production stopped");
            }
        } finally {
            Time.delta = delta;
        }
    }

    @Override
    public void drawUnitBuild(int page, int left, int top, int width, int height,
                              float x, float y, float drawWidth, float drawHeight, float rotation,
                              float progress, Color tint, float alpha, float phase, int vertexColor) {
        unitBuilds.add(new UnitBuild(page, left, top, width, height, x, y, rotation, progress, alpha, phase));
    }

    private void verifyExtractorDraws(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                      Team viewer) {
        viewport.moveTo(24, 30);
        var tile = Vars.world.tile(31, 36);
        int tested = 0;
        for (var block : Vars.content.blocks()) {
            if (!UpstreamBuildingLayers.supportsExtractorDraw(block)) continue;
            var wall = Vars.world.tile(tile.x + block.size / 2 + 1, tile.y);
            boolean beamDrill = block instanceof BeamDrill;
            var oldWall = wall.block();
            var oldOre = tile.overlay();
            tile.setOverlay(Blocks.oreCopper);
            wall.setBlock(beamDrill ? Blocks.graphiticWall : Blocks.beryllicStoneWall);
            tile.setBlock(block, viewer, 0);
            try {
                var build = tile.build;
                build.onProximityUpdate();
                sprites.clear();
                layers.render(viewport, viewer, candidate -> candidate == build
                    && UpstreamRenderedNetworkProbe.supportsBuildingDraw(candidate));
                requireRegion(block.region);
                var idle = List.copyOf(sprites);
                if (build instanceof BurstDrillBuild burst) {
                    if (burst.dominantItem != Items.copper) throw new AssertionError("Burst drill ore missing");
                    burst.smoothProgress = 1f;
                } else if (build instanceof BeamDrillBuild beam) {
                    if (beam.facingAmount == 0) throw new AssertionError("Beam drill wall ore missing");
                    beam.warmup = 1f;
                } else if (build instanceof WallCrafterBuild crusher) {
                    if (!block.canPlaceOn(tile, viewer, 0)) throw new AssertionError("Crusher wall target missing");
                    crusher.totalTime = 19f;
                } else if (build instanceof DrillBuild drill) {
                    if (drill.dominantItem != Items.copper) throw new AssertionError("Floor drill ore missing");
                    drill.timeDrilled = 19f;
                    drill.warmup = 1f;
                }
                sprites.clear();
                layers.render(viewport, viewer, candidate -> candidate == build
                    && UpstreamRenderedNetworkProbe.supportsBuildingDraw(candidate));
                requireRegion(block.region);
                if (sprites.equals(idle)) throw new AssertionError("Stock extractor draw state unchanged: " + block.name);
                if (block instanceof BeamDrill beam) requireRegion(beam.laserCenter);
                tested++;
            } finally {
                tile.setBlock(Blocks.air);
                tile.setOverlay(oldOre);
                wall.setBlock(oldWall);
            }
        }
        if (tested != 10) throw new AssertionError("Expected ten stock extractors, got " + tested);
        System.out.printf("UPSTREAM_STOCK_EXTRACTOR_DRAW tested=%d active=%d%n", tested, tested);
    }

    private int verifyLiveBuildingRegistry(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                            Team viewer, Predicate<Block> included, String category) {
        viewport.moveTo(24, 30);
        var tile = Vars.world.tile(31, 36);
        int tested = 0;
        for (var block : Vars.content.blocks()) {
            if (!block.hasBuilding() || !included.test(block)) continue;
            tile.setBlock(block, viewer, 0);
            try {
                var building = tile.build;
                sprites.clear();
                layers.render(viewport, viewer, build -> build == building
                    && UpstreamRenderedNetworkProbe.supportsBuildingDraw(build));
                if (sprites.isEmpty()) throw new AssertionError("The live draw pass excludes " + block.name);
                tested++;
            } finally {
                tile.setBlock(Blocks.air);
            }
        }
        System.out.printf("UPSTREAM_STOCK_LIVE_%s_DRAW tested=%d%n", category, tested);
        return tested;
    }

    private static void verifyClientProcessor(Fi ids, Team team) {
        UpstreamClientLogic.initialize(ids);
        int copper = Vars.logicVars.lookupLogicId(Items.copper);
        if (copper < 0 || Vars.logicVars.lookupContent(ContentType.item, copper) != Items.copper) {
            throw new AssertionError("Original logic ID table did not resolve Copper");
        }
        var processorTile = Vars.world.tile(70, 15);
        var canvasTile = Vars.world.tile(72, 15);
        canvasTile.setBlock(Blocks.canvas, team);
        processorTile.setBlock(Blocks.microProcessor, team);
        var processor = (LogicBuild)processorTile.build;
        var canvas = (CanvasBuild)canvasTile.build;
        Vars.net.setClientConnected();
        byte[] config = LogicBlock.compress("getlink canvas 0\nop add color 3 2\nwrite color canvas 0\nend",
            Seq.with(new LogicLink(2, 0, "canvas1", true)));
        processor.configured(null, config);
        float oldDelta = Time.delta;
        Time.delta = 1f;
        try {
            for (int i = 0; i < 8; i++) UpstreamClientLogic.update(1f);
        } finally {
            Time.delta = oldDelta;
        }
        if (processor.executor.links.length != 1 || processor.executor.links[0] != canvas || canvas.getPixel(0) != 5) {
            throw new AssertionError("Stock client processor did not execute the configured Canvas write");
        }
        System.out.println("UPSTREAM_CLIENT_PROCESSOR_REFERENCE ids=ok compressed=ok link=ok canvas=5");
    }

    private static void verifyPowerBar(Team viewer) {
        var consumer = Blocks.arc.newBuilding().create(Blocks.arc, viewer);
        var battery = Blocks.battery.newBuilding().create(Blocks.battery, viewer);
        if (!UpstreamBuildingPowerHud.showsPower(consumer, viewer)
                || UpstreamBuildingPowerHud.showsPower(consumer, Team.crux)) {
            throw new AssertionError("Stock building bars must only show to the owning team");
        }
        consumer.power.status = 0f;
        if (consumer.block.powerBarFraction(consumer) != 0f
                || !consumer.block.powerBarLabel(consumer).equals("Power")) {
            throw new AssertionError("Stock unpowered bar differs");
        }
        consumer.power.status = 0.4f;
        if (consumer.block.powerBarFraction(consumer) != 0.4f) {
            throw new AssertionError("Stock partial power bar differs");
        }
        battery.power.status = 0.25f;
        if (battery.block.powerBarFraction(battery) != 0.25f
                || !battery.block.powerBarLabel(battery).equals("Power: 1.0[gray]k[]")) {
            throw new AssertionError("Stock buffered power bar differs");
        }
        // Arc draws power while reloading even when disabled.
        ((ReloadTurret.ReloadTurretBuild)consumer).reloadCounter = ((ReloadTurret)Blocks.arc).reload;
        consumer.power.graph.add(battery);
        if (consumer.block.powerBarFraction(consumer) != 1f) {
            throw new AssertionError("A supplied idle consumer should show a full power bar");
        }
        System.out.println("UPSTREAM_STOCK_POWER_BAR states=dry,partial,buffered,supplied-idle team=filtered");
    }

    private static void verifySpriteAtlas(Fi file) throws IOException {
        var stock = new TextureAtlasData(file, file.parent(), false);
        if (UpstreamTerrainDrawProbe.countAtlasRegions(file) != stock.getRegions().size) {
            throw new AssertionError("Stock atlas reservation count differs from the Arc decoder");
        }
        var atlas = UpstreamTerrainDrawProbe.readAtlas(file);
        var regions = atlas.getRegions();
        if (stock.getRegions().size != 5200 || regions.size != stock.getRegions().size) {
            throw new AssertionError("Expected 5,200 stock sprite regions");
        }
        var lookup = new HashMap<String, AtlasRegion>();
        for (int i = 0; i < regions.size; i++) {
            var expected = stock.getRegions().get(i);
            var actual = regions.get(i);
            lookup.put(expected.name, actual);
            if (!expected.name.equals(actual.name)
                    || UpstreamTerrainDrawProbe.pageIndex(actual) != stock.getPages().indexOf(expected.page, true)
                    || actual.texture.width != expected.page.width || actual.texture.height != expected.page.height
                    || actual.getX() != expected.left || actual.getY() != expected.top
                    || actual.width != expected.width || actual.height != expected.height
                    || actual.offsetX != expected.offsetX || actual.offsetY != expected.offsetY
                    || actual.originalWidth != expected.originalWidth || actual.originalHeight != expected.originalHeight
                    || !Arrays.equals(actual.splits, expected.splits) || !Arrays.equals(actual.pads, expected.pads)) {
                throw new AssertionError("Stock sprite metadata differs for " + expected.name);
            }
        }
        if (atlas.getRegionMap().size != lookup.size()) throw new AssertionError("Unexpected atlas lookup size");
        lookup.forEach((name, region) -> {
            if (atlas.find(name) != region) throw new AssertionError("Stock atlas lookup differs for " + name);
        });
        if (atlas.find("missing-port-region") != atlas.find("error")) {
            throw new AssertionError("Missing sprites should fall back to the stock error region");
        }
        System.out.println("UPSTREAM_STOCK_SPRITE_ATLAS pages=" + stock.getPages().size + " regions=" + regions.size);
    }

    private static void verifyLogicFontPixels(Fi font) {
        var texture = Fonts.logic.getRegion().texture;
        var sampled = (UpstreamLogicFramebuffer.SampledTexture)texture;
        try (var packed = new DataInputStream(font.read())) {
            // Skip the magic, size and float metrics before the glyph records.
            packed.skipNBytes(24);
            packed.skipNBytes(packed.readUnsignedShort() * 16L);
            for (int y = 0; y < texture.height; y++) {
                for (int x = 0; x < texture.width; x++) {
                    if (sampled.pixel(x, y) != packed.readInt()) {
                        throw new AssertionError("Stock Logic Display font differs at " + x + "," + y);
                    }
                }
            }
            if (packed.read() != -1) throw new AssertionError("Stock Logic Display font has extra pixels");
            System.out.println("UPSTREAM_LOGIC_FONT_REFERENCE pixels=" + texture.width * texture.height);
        } catch (IOException error) {
            throw new AssertionError("Could not verify the packed stock Logic Display font", error);
        }
    }

    private void verifyLogicDisplay(Fi tiles, Fi font, Team team) {
        var atlas = new UpstreamLogicAtlas(tiles);
        UpstreamLogicFramebuffer.atlasPixels((texture, x, y) ->
            atlas.sample(UpstreamTerrainDrawProbe.pageIndex(texture), x, y));
        UpstreamLogicFont.load(font);
        verifyLogicFontPixels(font);
        var processor = (LogicBuild)Vars.world.tile(70, 15).build;
        var displayTile = Vars.world.tile(70, 20);
        var sourceTile = Vars.world.tile(76, 15);
        displayTile.setBlock(Blocks.logicDisplay, team);
        sourceTile.setBlock(Blocks.logicDisplay, team);
        var display = (LogicDisplayBuild)displayTile.build;
        var source = (LogicDisplayBuild)sourceTile.build;
        display.ensureBuffer();
        source.ensureBuffer();
        var links = Seq.with(new LogicLink(0, 5, "display1", true), new LogicLink(6, 0, "display2", true));
        processor.configured(null, LogicBlock.compress("""
            getlink target 0
            getlink source 1
            draw clear 32 160 224 0 0 0
            drawflush source
            draw clear 16 32 64 0 0 0
            draw col %ff000080 0 0 0 0 0
            draw rect 10 10 8 8 0 0
            draw color 255 255 255 255 0 0
            draw translate 20 10 0 0 0 0
            draw rect 5 5 6 6 0 0
            draw reset 0 0 0 0 0 0
            draw image 48 30 source 8 0 0
            draw image 60 60 @copper 12 0 0
            print "A"
            draw print 30 60 @bottomLeft 0 0 0
            drawflush target
            end
            """, links));
        float oldDelta = Time.delta;
        Time.delta = 1f;
        try {
            for (int i = 0; i < 20; i++) UpstreamClientLogic.update(1f);
        } finally {
            Time.delta = oldDelta;
        }
        if (display.commands.isEmpty() || source.commands.isEmpty()) {
            throw new AssertionError("Stock drawflush did not queue both displays");
        }
        display.processCommands();
        if (!source.commands.isEmpty() || !display.commands.isEmpty()) {
            throw new AssertionError("Stock processCommands did not update the offscreen source first");
        }
        assertDisplayPixel(display, 0, 0, 0x102040ff);
        assertDisplayPixel(display, 10, 10, 0x881020ff);
        assertDisplayPixel(display, 25, 15, 0xffffffff);
        assertDisplayPixel(display, 48, 30, 0x20a0e0ff);
        if (displayPixels(display, 54, 54, 66, 66) == 0 || displayPixels(display, 30, 60, 46, 80) == 0
                || processor.executor.links[0] != display) {
            throw new AssertionError("Original copper icon, logic font or display link missing");
        }
        displayDraws.clear();
        display.draw();
        if (displayDraws.size() != 1 || displayDraws.get(0) != display.buffer.getTexture()) {
            throw new AssertionError("Original display did not submit its framebuffer texture");
        }
        var buffer = display.buffer;
        displayTile.setBlock(Blocks.air);
        if (!buffer.isDisposed()) {
            throw new AssertionError("Removed display did not dispose its framebuffer");
        }
        verifyDisplayMarkers(source);
        verifyTiledDisplay(team);
        System.out.println("UPSTREAM_LOGIC_DISPLAY_REFERENCE flush=ok clear=ok alpha=ok transform=ok offscreen=ok copper=ok font=ok tiles=ok dispose=ok");
    }

    private void verifyDisplayMarkers(LogicDisplayBuild source) {
        var marker = new TextureMarker(source, 605, 240, 16, 16);
        var quad = new QuadMarker();
        quad.texture.value = source;
        for (int i = 0; i < 4; i++) {
            quad.control(LMarkerControl.posi, i, i >= 2 ? 75 : 71, i == 1 || i == 2 ? 29 : 25);
            quad.control(LMarkerControl.uvi, i, i >= 2 ? 1 : 0, i == 1 || i == 2 ? 1 : 0);
        }
        if (quad.vertices[4] != 0f || quad.vertices[10] != 1f) {
            throw new AssertionError("Stock quad did not preserve framebuffer orientation");
        }
        Vars.state.markers.add(616, marker);
        Vars.state.markers.add(617, quad);
        source.commands.addLast(DisplayCmd.get(LogicDisplay.commandClear, 250, 86, 37, 0, 0, 0));
        displayDraws.clear();
        displayFirstPixels.clear();
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 2.5f, (text, x, y, flags, size, align, lines) -> {});
        if (!displayDraws.equals(List.of(source.buffer.getTexture(), source.buffer.getTexture()))
                || !displayFirstPixels.equals(List.of(0xfa5625ff, 0xfa5625ff)) || !source.commands.isEmpty()) {
            throw new AssertionError("Offscreen display commands must finish before either marker samples its texture");
        }
        var old = source.buffer;
        source.buffer = null;
        old.dispose();
        displayDraws.clear();
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 2.5f, (text, x, y, flags, size, align, lines) -> {});
        if (source.buffer == null || !displayDraws.equals(List.of(source.buffer.getTexture(), source.buffer.getTexture()))) {
            throw new AssertionError("Markers did not bind the replacement framebuffer");
        }
        Vars.state.markers.remove(616);
        Vars.state.markers.remove(617);
        System.out.println("UPSTREAM_DISPLAY_MARKERS_REFERENCE sprite=ok quad=ok deferred=ok replacement=ok");
    }

    private void verifyTiledDisplay(Team team) {
        var left = Vars.world.tile(73, 19);
        var right = Vars.world.tile(74, 19);
        left.setBlock(Blocks.tileLogicDisplay, team);
        right.setBlock(Blocks.tileLogicDisplay, team);
        var a = (TileableLogicDisplayBuild)left.build;
        var b = (TileableLogicDisplayBuild)right.build;
        a.updateTile();
        b.updateTile();
        if (a.rootDisplay != b.rootDisplay || !a.rectangular || a.tilesWidth != 2 || a.tilesHeight != 1) {
            throw new AssertionError("Original tileable displays did not form a two-tile framebuffer");
        }
        Core.graphics = new MockGraphics() {
            @Override public long getFrameId() { return 1; }
        };
        displayDraws.clear();
        Draw.sort(true);
        try {
            Draw.z(Layer.block);
            a.draw();
            Draw.z(Layer.block);
            b.draw();
        } finally {
            Draw.sort(false);
        }
        var buffer = a.rootDisplay.buffer;
        if (buffer == null || buffer.getWidth() != 52 || buffer.getHeight() != 20
                || displayDraws.size() != 2 || displayDraws.stream().anyMatch(texture -> texture != buffer.getTexture())) {
            throw new AssertionError("Original tileable texture crops used the wrong framebuffer: " +
                (buffer == null ? "none" : buffer.getWidth() + "x" + buffer.getHeight()) + ", draws=" + displayDraws.size());
        }
    }

    @Override
    public void drawLogicDisplay(RasterTexture texture, float[] vertices, int offset, Blending blending) {
        displayDraws.add(texture);
        displayFirstPixels.add(texture.pixels[0]);
    }

    private static int displayPixels(LogicDisplayBuild display, int left, int bottom, int right, int top) {
        int changed = 0;
        int[] pixels = display.buffer.getTexture().pixels;
        int width = display.buffer.getWidth();
        for (int y = bottom; y < top; y++) {
            for (int x = left; x < right; x++) {
                if (pixels[y * width + x] != 0x102040ff) changed++;
            }
        }
        return changed;
    }

    private static void assertDisplayPixel(LogicDisplayBuild display, int x, int y, int expected) {
        int rgba = display.buffer.getTexture().pixels[y * display.buffer.getWidth() + x];
        if (rgba != expected) throw new AssertionError(String.format("Logic Display pixel %d,%d: %08x != %08x", x, y, rgba, expected));
    }

    private void verifyCanvas(Team team) {
        var tile = Vars.world.tile(73, 25);
        var neighbor = Vars.world.tile(75, 25);
        var largeTile = Vars.world.tile(81, 29);
        tile.setBlock(Blocks.canvas, team);
        neighbor.setBlock(Blocks.canvas, team);
        largeTile.setBlock(Blocks.largeCanvas, team);
        var canvas = (CanvasBuild)tile.build;
        var large = (CanvasBuild)largeTile.build;
        canvas.onProximityUpdate();
        canvas.setPixel(0, 1);
        canvas.setPixel(1, 6);
        large.setPixel(23 * 24 + 23, 3);
        canvasDraws.clear();
        sprites.clear();
        canvas.draw();
        large.draw();
        if (canvas.blending == 0 || canvasDraws.size() != 2 || sprites.isEmpty()) {
            throw new AssertionError("Canvas neighborhood, stock borders or draw missing");
        }
        var smallTexture = canvasDraws.get(0);
        var largeTexture = canvasDraws.get(1);
        if (smallTexture.width != 12 || smallTexture.pixels[0] != ((CanvasBlock)Blocks.canvas).palette[1]
                || smallTexture.pixels[1] != ((CanvasBlock)Blocks.canvas).palette[6]
                || largeTexture.width != 24 || largeTexture.pixels[575] != ((CanvasBlock)Blocks.largeCanvas).palette[3]) {
            throw new AssertionError("Stock Canvas palette or row order changed");
        }
        var bytes = new ByteArrayOutputStream();
        large.write(new Writes(new DataOutputStream(bytes)));
        var restored = (CanvasBuild)Blocks.largeCanvas.newBuilding();
        restored.read(new Reads(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))), large.version());
        if (!Arrays.equals(restored.data, large.data) || restored.getPixel(575) != 3) {
            throw new AssertionError("Stock Canvas save payload did not round-trip");
        }

        var marker = new TextureMarker(canvas, 586f, 242f, 16f, 16f);
        Vars.state.markers.add(613, marker);
        canvasDraws.clear();
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 2.5f, (text, x, y, flags, size, align, lines) -> {});
        if (!canvasDraws.equals(List.of(smallTexture))) throw new AssertionError("Missing stock Canvas texture marker");
        var replacement = ((CanvasBuild)neighbor.build);
        replacement.setPixel(0, 3);
        int before = smallTexture.revision;
        canvas.configured(null, replacement.data.clone());
        canvasDraws.clear();
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 2.5f, (text, x, y, flags, size, align, lines) -> {});
        if (!canvasDraws.equals(List.of(smallTexture)) || smallTexture.revision <= before
                || smallTexture.pixels[0] != ((CanvasBlock)Blocks.canvas).palette[3]) {
            throw new AssertionError("Stock Canvas configuration did not update the linked marker texture"
                + " draws=" + canvasDraws.size() + " rev=" + smallTexture.revision + " before=" + before
                + " pixel=" + Integer.toHexString(smallTexture.pixels[0]));
        }
        Vars.state.markers.remove(613);
        tile.remove();
        if (!smallTexture.isDisposed()) throw new AssertionError("Canvas removal kept its texture active");
        System.out.println("UPSTREAM_CANVAS_REFERENCE small=ok large=ok palettes=ok borders=ok save=ok config=ok marker=ok disposed=ok");
        verifyCanvasQuad(large, largeTexture);
    }

    private void verifyCanvasQuad(CanvasBuild canvas, UpstreamCanvasTexture texture) {
        var quad = new QuadMarker();
        quad.texture.value = canvas;
        quad.vertices = new float[]{
            40f, 64f, Color.whiteFloatBits, 0f, 0f, Color.clearFloatBits,
            44f, 112f, Color.sky.toFloatBits(), 0f, 1f, Color.clearFloatBits,
            92f, 118f, Color.whiteFloatBits, 1f, 1f, Color.clearFloatBits,
            105f, 72f, Color.whiteFloatBits, 1f, 0f, Color.clearFloatBits
        };
        Vars.state.markers.add(614, quad);
        int before = texture.revision;
        canvas.setPixel(0, 6);
        float requestedU = 0.75f, requestedV = 0.25f;
        quad.control(LMarkerControl.uvi, 2, requestedU, requestedV);
        quad.control(LMarkerControl.posi, 2, 13, 16);
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 2.5f, (text, x, y, flags, size, align, lines) -> {});
        if (canvasQuadTexture != texture || texture.revision <= before
                || texture.pixels[0] != ((CanvasBlock)Blocks.largeCanvas).palette[6]
                || !Arrays.equals(canvasQuadVertices, quad.vertices)
                || canvasQuadVertices[12] != 104f || canvasQuadVertices[13] != 128f
                || canvasQuadVertices[8] != Color.sky.toFloatBits()) {
            throw new AssertionError("Linked Canvas quad did not preserve original pixels, vertices or tint");
        }
        // Mindustry v160.8 QuadMarker.setUv inverts local V before submitting the vertex.
        float vertexV = 1f - requestedV;
        if (canvasQuadVertices[15] != requestedU || canvasQuadVertices[16] != vertexV) {
            throw new AssertionError("Linked Canvas quad did not submit the stock UV orientation");
        }
        quad.setTexture("block-router-ui");
        var router = Core.atlas.find("block-router-ui");
        float u = router.u + (router.u2 - router.u) * requestedU;
        float v = router.v + (router.v2 - router.v) * vertexV;
        if (Math.abs(quad.vertices[15] - u) > 0.00001f || Math.abs(quad.vertices[16] - v) > 0.00001f) {
            throw new AssertionError("Retargeting a Canvas quad changed its local UV");
        }
        Vars.state.markers.remove(614);
        System.out.println("UPSTREAM_CANVAS_QUAD_REFERENCE refresh=ok vertices=ok uv=ok tint=ok retarget=ok");
    }

    private static void verifyObjectiveHud() {
        var rules = Vars.state.rules;
        var collect = new ItemObjective(Items.beryllium, 60);
        var construct = new BuildCountObjective(Blocks.turbineCondenser, 1);
        var hidden = new ItemObjective(Items.copper, 50);
        hidden.hidden = true;
        rules.objectives = new MapObjectives();
        rules.objectives.add(collect.child(construct), hidden);
        rules.defaultTeam.items().set(Items.beryllium, 17);
        var rows = UpstreamObjectiveHud.collect(rules);
        if (rows.size() != 1 || !rows.get(0).text().equals("Obtain: 17/60\nBeryllium")
                || rows.get(0).icon() == null || rows.get(0).icon() != Items.beryllium.uiIcon) {
            throw new AssertionError("Incorrect stock item objective HUD: " + rows);
        }
        collect.done();
        rows = UpstreamObjectiveHud.collect(rules);
        if (rows.size() != 1 || !rows.get(0).text().equals("Build: 1x\nTurbine Condenser")
                || rows.get(0).icon() == null || rows.get(0).icon() != Blocks.turbineCondenser.uiIcon) {
            throw new AssertionError("Incorrect stock build objective HUD: " + rows);
        }
        System.out.println("UPSTREAM_OBJECTIVE_HUD_REFERENCE item=beryllium build=turbine-condenser hidden=filtered");
    }

    private void verifyErekirStarterBlocks(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                           Team viewer) {
        var duct = Vars.world.tile(48, 34);
        var wall = Vars.world.tile(49, 34);
        duct.setBlock(Blocks.duct, viewer, 1);
        wall.setBlock(Blocks.berylliumWall, viewer, 0);
        sprites.clear();
        layers.render(viewport, viewer, build -> (build == duct.build || build == wall.build)
            && UpstreamRenderedNetworkProbe.supportsBuildingDraw(build));
        requireRegion(Core.atlas.find("duct-top-0"));
        requireRegion(Blocks.berylliumWall.region);
        System.out.println("UPSTREAM_EREKIR_BUILDINGS duct=1 berylliumWall=1");
    }

    private void verifyCoreVariants(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                    Team viewer) {
        viewport.moveTo(44, 30);
        var tile = Vars.world.tile(52, 36);
        var cores = List.of(Blocks.coreShard, Blocks.coreFoundation, Blocks.coreNucleus,
            Blocks.coreBastion, Blocks.coreCitadel, Blocks.coreAcropolis);
        for (var block : cores) {
            tile.setBlock(block, viewer, 0);
            sprites.clear();
            layers.render(viewport, viewer, build -> build == tile.build
                && UpstreamRenderedNetworkProbe.supportsBuildingDraw(build));
            requireRegion(block.region);
        }
        System.out.println("UPSTREAM_CORE_VARIANTS originalRegions=" + cores.size());
    }

    private void verifyCarriedUnits(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                   Team viewer) {
        var tile = Vars.world.tile(32, 40);
        tile.setBlock(Blocks.payloadConveyor, viewer, 1);
        var belt = (PayloadConveyorBuild) tile.build;
        var conveyor = (PayloadConveyor) belt.block;
        for (var type : List.of(UnitTypes.gamma, UnitTypes.dagger)) {
            var team = type == UnitTypes.gamma ? viewer : Team.crux;
            var original = type.create(team);
            var out = new ByteArrayOutputStream();
            Payload.write(new UnitPayload(original), new Writes(new DataOutputStream(out)));
            UnitPayload cargo = Payload.read(new Reads(new DataInputStream(new ByteArrayInputStream(out.toByteArray()))));
            belt.progress = conveyor.moveTime * 0.4352753f;
            belt.handlePayload(belt, cargo);

            sprites.clear();
            layers.render(viewport, viewer, candidate -> candidate == belt
                && UpstreamRenderedNetworkProbe.supportsBuildingDraw(candidate));
            if (cargo.unit.isAdded() || cargo.unit.type() != type || cargo.unit.team() != team
                    || cargo.unit.elevation() != original.elevation() || cargo.rotation() != 90f
                    || Math.abs(cargo.x() - belt.x) > 0.001f || Math.abs(cargo.y() - belt.y + 6f) > 0.001f) {
                throw new AssertionError("Stock unit payload state differed for " + type.name);
            }
            requireRegion(type.region);
            requireRegion(type.cellRegion);
            if (sprites.stream().anyMatch(sprite -> matchesRegion(sprite, Core.atlas.find("circle")))) {
                throw new AssertionError("Stock carried unit drew its flight engine");
            }
        }
        System.out.println("UPSTREAM_UNIT_PAYLOAD roundTrip=2 incomingOffset=6 restoresElevation=ok");
    }

    private void verifyUnits(Team viewer) {
        Vars.player = Player.create();
        Vars.player.team(viewer);
        var carrier = UnitTypes.mega.create(viewer);
        carrier.set(248f, 290f);
        ((Payloadc)carrier).payloads().add(new UnitPayload(UnitTypes.flare.create(viewer)));
        if (!UpstreamRenderedNetworkProbe.loadUnitGraphics(carrier)) {
            throw new AssertionError("Could not load the stock carrier");
        }
        sprites.clear();
        carrier.draw();
        requireRegion(UnitTypes.mega.region);
        requireRegion(UnitTypes.flare.region);
        System.out.println("UPSTREAM_LIVE_CARRIED_UNIT carrier=mega cargo=flare atlas=ok");
        var types = Vars.content.units();
        int drawn = 0;
        for (var type : types) {
            // Stock BlockUnit proxies belong to buildings and cannot be added to Groups.unit.
            if (type.sample instanceof BlockUnitc) continue;
            if (!UpstreamRenderedNetworkProbe.loadUnitGraphics(type)) {
                throw new AssertionError("Could not load stock unit " + type.name);
            }
            var unit = type.spawn(Team.crux, 300f, 288f, 90f);
            for (var mount : unit.mounts) mount.heat = 1f;
            if (type == UnitTypes.quasar) unit.shield = 100f;
            sprites.clear();
            solidQuads.clear();
            texturedQuads = 0;
            Draw.sort(true);
            try {
                unit.draw();
                Draw.sort(false);
            } catch (IllegalStateException failure) {
                throw new AssertionError("Stock unit draw failed for " + type.name, failure);
            }
            if (type.drawBody) requireRegion(type.region);
            if (unit instanceof Crawlc) {
                for (var segment : type.segmentRegions) requireRegion(segment);
            } else if (type.drawCell) {
                requireRegion(type.cellRegion);
            }
            for (var mount : unit.mounts) {
                if (mount.weapon.region.found()) requireRegion(mount.weapon.region);
                if (mount.weapon.heatRegion.found()) requireRegion(mount.weapon.heatRegion);
            }
            if (type == UnitTypes.quasar && solidQuads.isEmpty()) {
                throw new AssertionError("Missing stock unshaded Quasar shield");
            }
            if (type.name.equals("anthicus-missile") && texturedQuads == 0) {
                throw new AssertionError("Missing original Anthicus missile quad");
            }
            unit.remove();
            drawn++;
        }
        System.out.println("UPSTREAM_UNIT_ROSTER types=" + types.size + " drawn=" + drawn
            + " quasarShield=ok anthicusQuad=ok");
        var gamma = UnitTypes.gamma.spawn(viewer, 240f, 288f, 90f);
        var dagger = UnitTypes.dagger.spawn(Team.crux, 256f, 288f, 90f);

        sprites.clear();
        Draw.sort(true);
        gamma.type().draw(gamma);
        dagger.type().draw(dagger);
        Draw.sort(false);

        for (String name : List.of("unit-gamma-full", "particle", "circle", "gamma",
                "gamma-cell", "small-mount-weapon", "small-mount-weapon-outline", "dagger-base",
                "dagger-leg", "dagger", "power-cell", "large-weapon", "large-weapon-outline")) {
            requireRegion(Core.atlas.find(name));
        }
        for (var unit : List.of(gamma, dagger)) {
            var cell = unit.type().cellRegion;
            int expected = Color.floatToIntColor(unit.team().color.toFloatBits());
            if (sprites.stream().noneMatch(sprite -> matchesRegion(sprite, cell) && sprite.color() == expected)) {
                throw new AssertionError("Missing stock team cell color for " + unit.type().name);
            }
        }
        Vars.player.unit(gamma);
        gamma.addBuild(new BuildPlan(30, 36, 0, Blocks.conveyor));
        ((Builderc) gamma).buildAlpha(1f);
        sprites.clear();
        solidQuads.clear();
        gamma.type().draw(gamma);
        if (solidQuads.size() != 2) {
            throw new AssertionError("Expected two stock construction beam lines, got " + solidQuads.size());
        }
        requireRegion(Core.atlas.white());
        gamma.clearBuilding();
        System.out.println("UPSTREAM_UNIT_DRAW gamma=shadow,engine,body,cell,weapons dagger=legs,base,body,cell,weapons");
    }

    private void verifyNavalWake(Team viewer) {
        for (int y = 35; y <= 37; y++) {
            for (int x = 28; x <= 34; x++) {
                Vars.world.tile(x, y).setFloor(Blocks.water.asFloor());
            }
        }
        var boat = (UnitWaterMove) UnitTypes.risso.spawn(viewer, 240f, 288f, 0f);
        float health = boat.health;
        Time.delta = 1f;
        for (int i = 0; i < 8; i++) {
            boat.set(240f + 2f * i, 288f);
            boat.advanceWake();
        }
        if (boat.x != 254f || boat.y != 288f || boat.health != health || !boat.vel.isZero()) {
            throw new AssertionError("Wake update changed stock ship simulation state");
        }
        sprites.clear();
        solidQuads.clear();
        Draw.sort(true);
        boat.draw();
        Draw.sort(false);
        requireRegion(boat.type.region);
        var wakeColor = Blocks.water.mapColor.cpy().mul(1.5f);
        int packedColor = Color.floatToIntColor(wakeColor.toFloatBits());
        int above = 0, below = 0;
        for (var vertices : solidQuads) {
            if (Color.floatToIntColor(vertices[2]) != packedColor) {
                throw new AssertionError("Unexpected water color in the stock Risso wake");
            }
            if (vertices[1] > boat.y) above++;
            if (vertices[1] < boat.y) below++;
        }
        if (above != 8 || below != 8) throw new AssertionError("Unexpected Risso wake: " + above + " and " + below);
        var serverBoat = UnitTypes.risso.create(viewer);
        serverBoat.set(260f, 288f);
        var snapshot = new ByteArrayOutputStream();
        serverBoat.writeSync(new Writes(new DataOutputStream(snapshot)));
        boat.readSync(new Reads(new DataInputStream(new ByteArrayInputStream(snapshot.toByteArray()))));
        boat.interpolate();
        if (boat.x != 260f || boat.y != 288f) throw new AssertionError("Stock Risso snapshot did not move the remote unit");
        System.out.println("UPSTREAM_NAVAL_WAKE port=" + above + " starboard=" + below
            + " color=" + wakeColor + " snapshot=ok");
    }

    private void verifyTankTreads(Team viewer) {
        var types = List.of(UnitTypes.stell, UnitTypes.locus, UnitTypes.precept, UnitTypes.vanquish, UnitTypes.conquer);
        for (var type : types) {
            var unit = type.spawn(viewer, 240f, 288f, 90f);
            var tank = (Tankc) unit;
            var initial = type.treadRegions[0][0];
            sprites.clear();
            unit.draw();
            requireRegion(type.treadRegion);
            requireRegion(initial);

            unit.set(243f, 292f);
            UpstreamRenderedNetworkProbe.advanceTankTreads(tank);
            UpstreamRenderedNetworkProbe.advanceTankTreads(tank);
            if (tank.treadTime() != 5f || unit.x != 243f || unit.y != 292f) {
                throw new AssertionError("Unexpected stock tread distance for " + type.name);
            }
            sprites.clear();
            Draw.sort(true);
            unit.draw();
            Draw.sort(false);
            for (var strip : type.treadRegions) {
                var expected = strip[5 % type.treadFrames];
                if (sprites.stream().filter(sprite -> matchesRegion(sprite, expected)).count() != 2) {
                    throw new AssertionError("Missing both stock tread sides for " + type.name);
                }
            }
            unit.remove();
        }
        System.out.println("UPSTREAM_TANK_TREADS types=" + types.size() + " movingFrame=5 idleDistance=0");
    }

    private void verifyCarriedBuildings(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                        Team viewer) {
        var source = Vars.world.tile(28, 40);
        var destination = Vars.world.tile(32, 40);
        destination.setBlock(Blocks.payloadConveyor, viewer, 0);
        var sender = (PayloadConveyorBuild) source.build;
        var receiver = (PayloadConveyorBuild) destination.build;
        var conveyor = (PayloadConveyor) sender.block;
        var blocks = List.of(Blocks.copperWallLarge, Blocks.copperWall, Blocks.mechanicalDrill,
            Blocks.conveyor, Blocks.titaniumConveyor, Blocks.armoredConveyor, Blocks.payloadConveyor,
            Blocks.payloadRouter, Blocks.reinforcedPayloadConveyor, Blocks.reinforcedPayloadRouter);
        var shadow = Core.atlas.find("square-shadow");
        int shade = Color.floatToIntColor(new Color(0f, 0f, 0f, 0.4f).toFloatBits());
        int white = Color.floatToIntColor(Color.whiteFloatBits);
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            float offset = i == 0 ? -6f : 0f;
            sender.progress = conveyor.moveTime * (i == 0 ? 0.4352753f : 0.5f);
            sender.handlePayload(sender, new BuildPayload(block, viewer));
            var bytes = new ByteArrayOutputStream();
            sender.writeAll(new Writes(new DataOutputStream(bytes)));
            receiver.readAll(new Reads(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))),
                sender.version());
            sprites.clear();
            layers.render(viewport, viewer, candidate -> candidate == receiver
                && UpstreamRenderedNetworkProbe.supportsBuildingDraw(candidate));
            if (!(receiver.item instanceof BuildPayload payload) || payload.block() != block
                    || payload.build.team != viewer || payload.build.tile != Vars.emptyTile
                    || Math.abs(payload.x() - receiver.x - offset) > 0.001f || payload.y() != receiver.y) {
                throw new AssertionError("Stock payload stream lost " + block + " or its visual position");
            }

            int shadowIndex = findPayloadSprite(shadow, payload, shade);
            var expected = switch (block.name) {
                case "copper-wall", "copper-wall-large" -> List.of(block.name);
                // BuildPayload's depth transform places the drill's original after-cracks layer
                // at a lower depth than the initial draw at blockOver.
                case "mechanical-drill" -> List.of("mechanical-drill-rotator", "mechanical-drill-top", block.name);
                default -> List.of("block-" + block.name + "-full");
            };
            int previous = shadowIndex;
            for (var region : expected) {
                int index = findPayloadSprite(Core.atlas.find(region), payload, white);
                if (previous < 1 || index <= previous) {
                    throw new AssertionError("Unexpected stock payload layers for " + block + ": " + sprites);
                }
                previous = index;
            }
        }
        System.out.println("UPSTREAM_CARRIED_BUILDINGS roundTrip=" + blocks.size()
            + " positions=2 shadows=" + blocks.size());
    }

    private int findPayloadSprite(TextureRegion region, BuildPayload payload, int color) {
        for (int i = 0; i < sprites.size(); i++) {
            var sprite = sprites.get(i);
            if (Math.abs(sprite.x() - payload.x()) < 0.001f && Math.abs(sprite.y() - payload.y()) < 0.001f
                    && sprite.page() == UpstreamTerrainDrawProbe.pageIndex(region)
                    && sprite.left() == region.getX() && sprite.top() == region.getY()
                    && sprite.width() == region.width && sprite.height() == region.height
                    && sprite.color() == color) {
                return i;
            }
        }
        return -1;
    }

    private void verifyPayloadMix(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                  Team viewer) {
        var tile = Vars.world.tile(28, 40);
        tile.setBlock(Blocks.payloadConveyor, viewer, 0);
        var build = (PayloadConveyorBuild) tile.build;
        var conveyor = (PayloadConveyor) build.block;
        for (float progress : new float[]{0f, 0.4352753f, 0.5f}) {
            build.progress = conveyor.moveTime * progress;
            float glow = Math.max((0.8f - Math.abs(build.fract() - 0.5f) * 2) / 0.8f, 0);
            int expected = Color.floatToIntColor(viewer.color.cpy().a(glow).toFloatBits());
            sprites.clear();
            layers.render(viewport, viewer, candidate -> candidate == build);
            long mixed = sprites.stream().filter(sprite -> sprite.mixColor() == expected).count();
            if (sprites.isEmpty() || (glow > 0f && mixed < 2)
                    || sprites.stream().anyMatch(sprite -> sprite.width() <= 0 || sprite.height() <= 0)) {
                throw new AssertionError("Unexpected stock payload crop or mix: " + sprites);
            }
        }
        System.out.println("UPSTREAM_PAYLOAD_MIX emptyClip=ok partial=ok full=ok");
    }

    private void verifyBuildingEffects(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                       Team viewer) {
        // Fill writes to a shared float array. A later draw must not overwrite queued geometry.
        solidQuads.clear();
        Draw.sort(true);
        Draw.z(Layer.effect);
        Fill.quad(1, 2, Color.red.toFloatBits(), 1, 3, Color.green.toFloatBits(),
            2, 3, Color.blue.toFloatBits(), 2, 2, Color.whiteFloatBits);
        Draw.z(Layer.block);
        Fill.quad(4, 7, 4, 8, 5, 8, 5, 7);
        if (!solidQuads.isEmpty()) throw new AssertionError("A solid quad escaped the Arc layer queue");
        Draw.sort(false);
        if (solidQuads.size() != 2 || solidQuads.get(0)[0] != 4 || solidQuads.get(1)[0] != 1
                || solidQuads.get(1)[8] != Color.green.toFloatBits()
                || solidQuads.get(1)[14] != Color.blue.toFloatBits()) {
            throw new AssertionError("Solid quad queue lost vertex order or color");
        }

        var weaving = Vars.world.tile(31, 31);
        var overdrive = Vars.world.tile(34, 34);
        var shockwave = Vars.world.tile(37, 38);
        weaving.setBlock(Blocks.phaseWeaver, viewer, 0);
        overdrive.setBlock(Blocks.overdriveProjector, viewer, 0);
        shockwave.setBlock(Blocks.shockwaveTower, viewer, 0);
        ((GenericCrafterBuild) weaving.build).warmup = 1f;
        ((OverdriveBuild) overdrive.build).heat = 1f;
        ((ShockwaveTowerBuild) shockwave.build).heat = 0.5f;
        shockwave.build.potentialEfficiency = 1f;
        Time.setInternalTime(42);

        solidQuads.clear();
        layers.render(viewport, viewer, build -> build.block == Blocks.phaseWeaver);
        if (solidQuads.size() != 1 || solidQuads.get(0)[2] != Pal.accent.toFloatBits()) {
            throw new AssertionError("Missing original active weave line");
        }
        solidQuads.clear();
        layers.render(viewport, viewer, build -> build.block == Blocks.overdriveProjector);
        int outline = solidQuads.size();
        solidQuads.clear();
        layers.render(viewport, viewer, build -> build.block == Blocks.shockwaveTower);
        if (outline != 4 || solidQuads.isEmpty()) throw new AssertionError("Missing stock building effects");
        System.out.printf("UPSTREAM_SOLID_BUILDING_EFFECTS weave=1 overdrive=%d shockwave=%d%n",
            outline, solidQuads.size());
    }

    private void verifyLiquidBridgeTransfer(Team team) {
        float bridge = verifyBridgeTransfer(Blocks.bridgeConduit, 50, 50, 3, team);
        float phase = verifyBridgeTransfer(Blocks.phaseConduit, 56, 50, 3, team);

        // Stock links are one-way: a back-link makes linkValid() false, so the
        // pair must not transport over distance.
        var backSourceTile = Vars.world.tile(62, 50);
        var backTargetTile = Vars.world.tile(65, 50);
        backSourceTile.setBlock(Blocks.air);
        backTargetTile.setBlock(Blocks.air);
        backSourceTile.setBlock(Blocks.bridgeConduit, team, 0);
        backTargetTile.setBlock(Blocks.bridgeConduit, team, 0);
        var backSource = (mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)backSourceTile.build;
        var backTarget = (mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)backTargetTile.build;
        backSource.configure(new arc.math.geom.Point2(3, 0));
        backTarget.configure(new arc.math.geom.Point2(-3, 0));
        backSource.liquids.add(Liquids.water, backSource.block.liquidCapacity);
        Time.delta = 1f;
        for (int tick = 0; tick < 120; tick++) {
            backSource.update();
            backTarget.update();
        }
        if (backTarget.liquids.get(Liquids.water) > 0f) {
            throw new AssertionError("Back-linked stock bridge transported water");
        }

        System.out.println("UPSTREAM_LIQUID_BRIDGE_REFERENCE bridgeMilli=" + Math.round(bridge * 1000f)
            + " phaseMilli=" + Math.round(phase * 1000f) + " backLink=rejected");
    }

    private void verifyLiquidJunctionRouting(Team team) {
        var sourceTile = Vars.world.tile(71, 50);
        var junctionTile = Vars.world.tile(72, 50);
        var sinkTile = Vars.world.tile(73, 50);
        sourceTile.setBlock(Blocks.air);
        junctionTile.setBlock(Blocks.air);
        sinkTile.setBlock(Blocks.air);
        sourceTile.setBlock(Blocks.liquidRouter, team, 0);
        junctionTile.setBlock(Blocks.liquidJunction, team, 0);
        sinkTile.setBlock(Blocks.liquidContainer, team, 0);

        var junction = junctionTile.build;
        var destination = junction.getLiquidDestination(sourceTile.build, Liquids.water);
        if (destination != sinkTile.build) {
            throw new AssertionError("Stock Liquid Junction did not route to the far side");
        }

        sinkTile.setBlock(Blocks.air);
        destination = junction.getLiquidDestination(sourceTile.build, Liquids.water);
        if (destination != junction) {
            throw new AssertionError("Stock Liquid Junction did not stop at a dead end");
        }

        System.out.println("UPSTREAM_LIQUID_JUNCTION_REFERENCE through=1 deadEnd=1");
    }

    private void verifyLiquidRouterTransfer(Team team) {
        var routerTile = Vars.world.tile(68, 50);
        var sinkTile = Vars.world.tile(69, 50);
        routerTile.setBlock(Blocks.air);
        sinkTile.setBlock(Blocks.air);
        routerTile.setBlock(Blocks.liquidRouter, team, 0);
        sinkTile.setBlock(Blocks.liquidContainer, team, 0);

        var router = routerTile.build;
        router.liquids.add(Liquids.water, router.block.liquidCapacity);
        Time.delta = 1f;
        for (int tick = 0; tick < 60; tick++) {
            router.update();
        }

        float moved = sinkTile.build.liquids.get(Liquids.water);
        if (moved <= 0f) throw new AssertionError("Stock Liquid Router did not transfer water");
        System.out.println("UPSTREAM_LIQUID_ROUTER_REFERENCE movedMilli=" + Math.round(moved * 1000f));
    }

    private float verifyBridgeTransfer(Block block, int x, int y, int dx, Team team) {
        var sourceTile = Vars.world.tile(x, y);
        var targetTile = Vars.world.tile(x + dx, y);
        sourceTile.setBlock(Blocks.air);
        targetTile.setBlock(Blocks.air);
        sourceTile.setBlock(block, team, 0);
        targetTile.setBlock(block, team, 0);

        var source = (mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)sourceTile.build;
        source.configure(new arc.math.geom.Point2(dx, 0));
        source.liquids.add(Liquids.water, source.block.liquidCapacity);
        if (source.block.hasPower && source.power != null) {
            source.power.status = 1f;
        }
        Time.delta = 1f;
        for (int tick = 0; tick < 120; tick++) {
            source.update();
        }

        float moved = targetTile.build.liquids.get(Liquids.water);
        if (moved <= 0f) throw new AssertionError("Stock " + block.name + " did not transfer water");
        return moved;
    }

    private void verifyFluidDrawing(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                    Team viewer) {
        var straight = Vars.world.tile(27, 32);
        var corner = Vars.world.tile(34, 37);
        var mirror = Vars.world.tile(37, 35);
        straight.setBlock(Blocks.conduit, viewer, 0);
        Vars.world.tile(26, 32).setBlock(Blocks.conduit, viewer, 0);
        corner.setBlock(Blocks.conduit, viewer, 0);
        Vars.world.tile(34, 36).setBlock(Blocks.conduit, viewer, 1);
        mirror.setBlock(Blocks.conduit, viewer, 0);
        Vars.world.tile(37, 36).setBlock(Blocks.conduit, viewer, 3);

        var straightBuild = (ConduitBuild) straight.build;
        var cornerBuild = (ConduitBuild) corner.build;
        var mirrorBuild = (ConduitBuild) mirror.build;
        if (straightBuild.blendbits == 1 || cornerBuild.blendbits != 1 || mirrorBuild.blendbits != 1
                || cornerBuild.yscl == mirrorBuild.yscl) {
            throw new AssertionError("Stock conduit adjacency did not produce both corner orientations: "
                + straightBuild.blendbits + "," + cornerBuild.blendbits + "," + mirrorBuild.blendbits
                + " scales=" + cornerBuild.yscl + "," + mirrorBuild.yscl);
        }
        for (var conduit : List.of(straightBuild, cornerBuild, mirrorBuild)) {
            conduit.liquids.add(Liquids.water, conduit.block.liquidCapacity);
            Time.delta = 1f;
            conduit.advanceLiquidDraw();
            if (Math.abs(conduit.smoothLiquid - 0.05f) > 1e-6f) {
                throw new AssertionError("Unexpected original smoothing rate");
            }
        }
        Time.setInternalTime(0);
        verifyWaterFrame(layers, viewport, viewer, straightBuild, cornerBuild, mirrorBuild);
        int previous = Liquids.water.getAnimationFrame();
        Time.setInternalTime(37);
        if (previous == Liquids.water.getAnimationFrame()) throw new AssertionError("Liquid frame did not advance");
        verifyWaterFrame(layers, viewport, viewer, straightBuild, cornerBuild, mirrorBuild);

        sprites.clear();
        LiquidBlock.drawTiledFrames(2, 0, 0, 2f, Liquids.nitrogen, 1f);
        var gas = Core.atlas.find("fluid-gas-" + Liquids.nitrogen.getAnimationFrame());
        if (sprites.size() != 4 || sprites.stream().noneMatch(sprite ->
                sprite.page() == UpstreamTerrainDrawProbe.pageIndex(gas)
                    && sprite.left() == gas.getX() && sprite.top() == gas.getY()
                    && sprite.width() == gas.width && sprite.height() == gas.height)
                || sprites.stream().noneMatch(sprite -> sprite.width() < gas.width || sprite.height() < gas.height)) {
            throw new AssertionError("Stock tiled gas frame did not preserve original padding: " + sprites);
        }
        System.out.println("UPSTREAM_FLUID_LAYERS straight=1 corners=2 waterFrames=2 gasTiles=4");
    }

    private void verifyWaterFrame(UpstreamBuildingLayers layers, UpstreamTerrainDrawProbe.Viewport viewport,
                                  Team viewer, ConduitBuild straight, ConduitBuild corner, ConduitBuild mirror) {
        sprites.clear();
        layers.render(viewport, viewer, build -> build.block == Blocks.conduit);
        int frame = Liquids.water.getAnimationFrame();
        var conduit = (mindustry.world.blocks.liquid.Conduit) corner.block;
        requireRegion(Core.atlas.find("fluid-liquid-" + frame));
        for (var elbow : List.of(corner, mirror)) {
            requireRegion(conduit.rotateRegions[elbow.yscl == -1 ? 3 : 0][0][frame]);
        }
        if (firstAt(straight.x, straight.y) < 0) throw new AssertionError("Straight conduit was culled");
    }

    private void requireRegion(TextureRegion region) {
        if (sprites.stream().noneMatch(sprite -> matchesRegion(sprite, region))) {
            throw new AssertionError("Missing stock region at " + region.getX() + "," + region.getY() + ": " + sprites);
        }
    }

    private static boolean matchesRegion(Sprite sprite, TextureRegion region) {
        return sprite.page() == UpstreamTerrainDrawProbe.pageIndex(region)
            && sprite.left() == region.getX() && sprite.top() == region.getY()
            && sprite.width() == region.width && sprite.height() == region.height;
    }

    private void verifyCrack(TextureRegion crack, mindustry.gen.Building build) {
        int color = Color.floatToIntColor(new Color(0.2f, 0.2f, 0.2f, 0.4f).toFloatBits());
        var matches = sprites.stream().filter(sprite -> sprite.x() == build.x && sprite.y() == build.y
            && sprite.page() == UpstreamTerrainDrawProbe.pageIndex(crack)
            && sprite.left() == crack.getX() && sprite.top() == crack.getY()).toList();
        if (matches.size() != 1 || matches.get(0).rotation() != build.pos() % 4 * 90
                || matches.get(0).color() != color || firstAt(build.x, build.y) == sprites.indexOf(matches.get(0))) {
            throw new AssertionError("Unexpected upstream crack draw for " + build.block + ": " + matches);
        }
    }

    private void verifyArcOrder() {
        var wall = Core.atlas.find("copper-wall-large");
        var reused = new TextureRegion(wall);
        Draw.sort(true);
        Draw.z(Layer.block);
        Draw.color(Color.orange);
        Draw.mixcol(Color.scarlet, 0.5f);
        Draw.blend(Blending.additive);
        Draw.rect(reused, 3, 1);
        reused.set(Core.atlas.find("white"));
        Draw.blend();
        Draw.reset();
        Draw.draw(Layer.block - 0.5f, () -> {
            Draw.rect(reused, 1, 1);
            Draw.draw(Layer.block + 1f, () -> Draw.rect(reused, 2, 1));
        });
        Draw.z(Layer.block);
        Draw.rect(reused, 4, 1);
        if (!sprites.isEmpty()) throw new AssertionError("Sorted draws reached the target before flush");
        Draw.sort(false);
        for (int i = 0; i < 4; i++) {
            if (sprites.size() != 4 || sprites.get(i).x() != i + 1) {
                throw new AssertionError("Unexpected Arc draw order: " + sprites);
            }
        }
        var captured = sprites.get(2);
        if (captured.left() != wall.getX() || captured.top() != wall.getY()
                || captured.page() != UpstreamTerrainDrawProbe.pageIndex(wall)
                || captured.color() != Color.floatToIntColor(Color.orange.toFloatBits())
                || captured.mixColor() != Color.floatToIntColor(Color.scarlet.cpy().a(0.5f).toFloatBits())
                || sprites.get(3).mixColor() != 0
                || captured.blending() != Blending.additive || sprites.get(3).blending() != Blending.normal) {
            throw new AssertionError("Queued draw lost its original atlas coordinates or tint");
        }
    }

    private void verifyTexturedLine() {
        var beam = Core.atlas.find("minelaser");
        if (!beam.found()) throw new AssertionError("Missing the original mining beam");
        sprites.clear();
        Draw.sort(true);
        Draw.color(Color.lightGray);
        Lines.stroke(9f);
        Lines.line(beam, 10f, 20f, 40f, 60f, false);
        Lines.stroke(1f);
        Draw.color();
        Draw.sort(false);
        if (sprites.size() != 1) throw new AssertionError("Expected one textured mining line: " + sprites);
        var drawn = sprites.get(0);
        if (drawn.page() != UpstreamTerrainDrawProbe.pageIndex(beam)
                || drawn.left() != beam.getX() || drawn.top() != beam.getY()
                || drawn.width() != beam.width || drawn.height() != beam.height
                || Math.abs(drawn.x() - 38.6f) > 0.01f || Math.abs(drawn.y() - 21.8f) > 0.01f
                || Math.abs(drawn.rotation() - 53.1301f) > 0.01f
                || drawn.color() != Color.floatToIntColor(Color.lightGray.toFloatBits())) {
            throw new AssertionError("Textured Arc line lost its atlas rect, geometry or tint: " + drawn);
        }
        System.out.println("UPSTREAM_TEXTURED_LINE minelaser=ok");
    }

    private int firstAt(float x, float y) {
        for (int i = 0; i < sprites.size(); i++) {
            var sprite = sprites.get(i);
            if (sprite.x() == x && sprite.y() == y) return i;
        }
        return -1;
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight, float rotation, int color, int mixColor, Blending blending) {
        sprites.add(new Sprite(x + drawWidth / 2f, y + drawHeight / 2f, page, left, top, width, height, rotation, color, mixColor, blending));
    }

    @Override
    public void drawSolidQuad(float[] vertices, int offset, Blending blending) {
        solidQuads.add(Arrays.copyOfRange(vertices, offset, offset + SpriteBatch.SPRITE_SIZE));
    }

    @Override
    public void drawCanvas(UpstreamCanvasTexture texture, float x, float y, float originX, float originY,
                           float width, float height, float rotation, int color, int mixColor, Blending blending) {
        canvasDraws.add(texture);
    }

    @Override
    public void drawTexturedQuad(int page, int pageWidth, int pageHeight, float[] vertices, int offset, Blending blending) {
        texturedQuads++;
    }

    @Override
    public void drawCanvasQuad(UpstreamCanvasTexture texture, float[] vertices, int offset, Blending blending) {
        canvasQuadTexture = texture;
        canvasQuadVertices = Arrays.copyOfRange(vertices, offset, offset + 24);
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
}
