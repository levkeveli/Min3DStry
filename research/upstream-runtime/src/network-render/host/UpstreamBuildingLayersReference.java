import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;
import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.SpriteBatch;
import arc.graphics.g2d.TextureRegion;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.content.UnitTypes;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Builderc;
import mindustry.gen.Player;
import mindustry.gen.Tankc;
import mindustry.gen.UnitWaterMove;
import mindustry.world.blocks.liquid.Conduit.ConduitBuild;
import mindustry.world.blocks.liquid.LiquidBlock;
import mindustry.game.Team;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.world.blocks.defense.OverdriveProjector.OverdriveBuild;
import mindustry.world.blocks.defense.ShockwaveTower.ShockwaveTowerBuild;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.payloads.PayloadConveyor;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;

public final class UpstreamBuildingLayersReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Sprite(float x, float y, int page, int left, int top, int width, int height, float rotation, int color, int mixColor, Blending blending) {}
    private final List<Sprite> sprites = new ArrayList<>();
    private final List<float[]> solidQuads = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected Glacier, sprites.aatls and block_colors.png");
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(new String[]{args[0]});
        UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), UpstreamFluidAtlas::load);
        Vars.content.loadColors(new Fi(args[2]));
        UpstreamCrackAtlas.load();

        var target = new UpstreamBuildingLayersReference();
        var viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        target.verifyArcOrder();

        // The visible two-tile wall starts one tile west of the viewport.
        viewport.moveTo(24, 30);
        var viewer = Team.sharded;
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
        var types = UpstreamRenderedNetworkProbe.loadUnitGraphics();
        for (var type : types) {
            var unit = type.spawn(Team.crux, 300f, 288f, 90f);
            for (var mount : unit.mounts) mount.heat = 1f;
            if (type == UnitTypes.quasar) unit.shield = 100f;
            sprites.clear();
            solidQuads.clear();
            Draw.sort(true);
            unit.draw();
            Draw.sort(false);
            requireRegion(type.region);
            if (type.drawCell) requireRegion(type.cellRegion);
            for (var mount : unit.mounts) {
                if (mount.weapon.region.found()) requireRegion(mount.weapon.region);
                if (mount.weapon.heatRegion.found()) requireRegion(mount.weapon.heatRegion);
            }
            if (type == UnitTypes.quasar && solidQuads.isEmpty()) {
                throw new AssertionError("Missing stock unshaded Quasar shield");
            }
            unit.remove();
        }
        System.out.println("UPSTREAM_UNIT_ROSTER types=" + types.length + " quasarShield=ok");
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

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
}
