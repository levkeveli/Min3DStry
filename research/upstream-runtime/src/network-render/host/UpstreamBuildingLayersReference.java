import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;
import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.util.Time;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.world.blocks.liquid.Conduit.ConduitBuild;
import mindustry.world.blocks.liquid.LiquidBlock;
import mindustry.game.Team;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Layer;

public final class UpstreamBuildingLayersReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Sprite(float x, float y, int page, int left, int top, int width, int height, float rotation, int color, Blending blending) {}
    private final List<Sprite> sprites = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected Glacier and sprites.aatls");
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(new String[]{args[0]});
        UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), UpstreamFluidAtlas::load);
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
        layers.render(viewport, viewer, block ->
            block == Blocks.conveyor || block == Blocks.copperWallLarge || block == Blocks.mechanicalDrill);

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
        layers.render(viewport, viewer, block -> block == Blocks.copperWallLarge || block == Blocks.mechanicalDrill);
        target.verifyCrack(crack, edgeWall.build);
        target.verifyCrack(crack, drill.build);
        System.out.println("UPSTREAM_BUILDING_CRACKS region=cracks-2-4 wall=1 drill=1");
        target.verifyFluidDrawing(layers, viewport, viewer);
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
        layers.render(viewport, viewer, block -> block == Blocks.conduit);
        int frame = Liquids.water.getAnimationFrame();
        var conduit = (mindustry.world.blocks.liquid.Conduit) corner.block;
        requireRegion(Core.atlas.find("fluid-liquid-" + frame));
        for (var elbow : List.of(corner, mirror)) {
            requireRegion(conduit.rotateRegions[elbow.yscl == -1 ? 3 : 0][0][frame]);
        }
        if (firstAt(straight.x, straight.y) < 0) throw new AssertionError("Straight conduit was culled");
    }

    private void requireRegion(TextureRegion region) {
        if (sprites.stream().noneMatch(sprite -> sprite.page() == UpstreamTerrainDrawProbe.pageIndex(region)
                && sprite.left() == region.getX() && sprite.top() == region.getY()
                && sprite.width() == region.width && sprite.height() == region.height)) {
            throw new AssertionError("Missing stock frame or cropped corner region: " + sprites);
        }
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
                     float originX, float originY, float drawWidth, float drawHeight, float rotation, int color, Blending blending) {
        sprites.add(new Sprite(x + drawWidth / 2f, y + drawHeight / 2f, page, left, top, width, height, rotation, color, blending));
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
}
