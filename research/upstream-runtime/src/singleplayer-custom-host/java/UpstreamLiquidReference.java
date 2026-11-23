import arc.graphics.Blending;
import arc.util.Time;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.type.Category;
import mindustry.world.blocks.distribution.DirectionBridge.DirectionBridgeBuild;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;
import mindustry.world.blocks.liquid.Conduit.ConduitBuild;
import mindustry.world.blocks.liquid.LiquidRouter.LiquidRouterBuild;
import mindustry.world.blocks.production.Pump.PumpBuild;
import mindustry.world.blocks.sandbox.LiquidSource.LiquidSourceBuild;

/** Run original building drawers against the atlas used by the offline ARM11 app. */
final class UpstreamLiquidReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Sprite(int page, int left, int top, int width, int height, float x, float y,
                          float drawWidth, float drawHeight, int color) {}

    private final List<Sprite> sprites = new ArrayList<>();
    private final UpstreamTerrainDrawProbe.Viewport viewport = UpstreamTerrainDrawProbe.liveViewport(this);
    private final UpstreamBuildingLayers layers = new UpstreamBuildingLayers();

    static void verify() {
        var target = new UpstreamLiquidReference();
        target.viewport.moveTo(24, 30);
        var tile = Vars.world.tile(31, 36);
        int tested = 0;
        for (var block : Vars.content.blocks()) {
            if (!block.hasBuilding() || block.category != Category.liquid) continue;
            tile.setBlock(block, Team.sharded, 0);
            try {
                var build = tile.build;
                if (!UpstreamSinglePlayerApp.supportsBuildingDraw(build)) {
                    throw new AssertionError("The offline draw pass excludes " + block.name);
                }
                target.verifyStates(build);
                tested++;
            } finally {
                tile.setBlock(Blocks.air);
            }
        }
        if (tested == 0) throw new AssertionError("The stock liquid registry was empty");
        System.out.println("UPSTREAM_STOCK_LIQUID_DRAW tested=" + tested);
    }

    private void verifyStates(Building build) {
        drawBuilding(build);
        if (sprites.isEmpty()) throw new AssertionError("No stock sprites for " + build.block.name);
        var empty = List.copyOf(sprites);
        var fluid = build.block == Blocks.reinforcedLiquidTank ? Liquids.nitrogen : Liquids.water;
        if (build.liquids != null) build.liquids.add(fluid, build.block.liquidCapacity * 0.75f);
        if (build instanceof ConduitBuild conduit) conduit.advanceLiquidDraw();
        if (build instanceof PumpBuild pump) {
            pump.liquidDrop = fluid;
            pump.warmup = 1f;
        }
        if (build instanceof LiquidSourceBuild source) source.configured(null, fluid);
        if (build instanceof DirectionBridgeBuild || build instanceof ItemBridgeBuild) {
            verifyBridge(build, empty);
        } else {
            drawBuilding(build);
            if ((build instanceof ConduitBuild || build instanceof LiquidRouterBuild
                    || build instanceof LiquidSourceBuild) && sprites.equals(empty)) {
                throw new AssertionError("The filled stock draw state was lost for " + build.block.name);
            }
        }
    }

    private void verifyBridge(Building build, List<Sprite> empty) {
        var end = Vars.world.tile(build.tileX() + 3, build.tileY());
        end.setBlock(build.block, build.team, 0);
        try {
            if (build instanceof ItemBridgeBuild bridge) {
                bridge.link = end.pos();
                bridge.warmup = 1f;
            }
            drawBuilding(build);
            if (sprites.equals(empty) || sprites.stream().noneMatch(sprite -> Math.abs(sprite.drawWidth()) >= 15)) {
                throw new AssertionError("The original bridge span was lost for " + build.block.name);
            }
        } finally {
            end.setBlock(Blocks.air);
        }
    }

    private void drawBuilding(Building build) {
        Time.setInternalTime(37);
        sprites.clear();
        layers.render(viewport, build.team, candidate -> candidate == build);
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, Blending blending) {
        sprites.add(new Sprite(page, left, top, width, height, x, y, drawWidth, drawHeight, color));
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void drawSolidQuad(float[] vertices, int offset, Blending blending) {}
    @Override public void end() {}
}
