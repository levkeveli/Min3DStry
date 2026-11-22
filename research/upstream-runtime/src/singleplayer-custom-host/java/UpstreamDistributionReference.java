import arc.graphics.Blending;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.type.Category;
import mindustry.world.blocks.distribution.Conveyor.ConveyorBuild;
import mindustry.world.blocks.distribution.DirectionBridge.DirectionBridgeBuild;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;
import mindustry.world.blocks.distribution.StackConveyor.StackConveyorBuild;
import mindustry.world.meta.BuildVisibility;

/** Check the stock distribution draw path with the atlas prepared by the offline app. */
final class UpstreamDistributionReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private final AtlasRegion copper = (AtlasRegion)Items.copper.fullIcon;
    private final UpstreamTerrainDrawProbe.Viewport viewport = UpstreamTerrainDrawProbe.liveViewport(this);
    private final UpstreamBuildingLayers layers = new UpstreamBuildingLayers();
    private int sprites;
    private int copperSprites;
    private boolean bridgeSpan;

    static void verify() {
        var target = new UpstreamDistributionReference();
        if (target.copper == null || !target.copper.found()) {
            throw new AssertionError("The original copper icon was not loaded with the world atlas");
        }
        target.viewport.moveTo(24, 30);
        var tile = Vars.world.tile(31, 36);
        int tested = 0;
        for (var block : Vars.content.blocks()) {
            if (!block.hasBuilding() || block.category != Category.distribution
                    || block.buildVisibility == BuildVisibility.hidden) continue;
            tile.setBlock(block, Team.sharded, 0);
            try {
                var build = tile.build;
                if (!UpstreamSinglePlayerApp.supportsBuildingDraw(build)) {
                    throw new AssertionError("The offline draw pass excludes " + block.name);
                }
                target.drawBuilding(build);
                if (target.sprites == 0) throw new AssertionError("No stock sprites for " + block.name);
                target.verifyActive(build);
                tested++;
            } finally {
                tile.setBlock(Blocks.air);
            }
        }
        if (tested == 0) throw new AssertionError("The stock distribution registry was empty");
        System.out.println("UPSTREAM_STOCK_DISTRIBUTION_DRAW tested=" + tested);
    }

    private void verifyActive(Building build) {
        if (build instanceof ConveyorBuild || build instanceof StackConveyorBuild) {
            build.handleStack(Items.copper, 1, build);
            drawBuilding(build);
            if (copperSprites == 0) throw new AssertionError("Missing transported copper on " + build.block.name);
        } else if (build instanceof ItemBridgeBuild || build instanceof DirectionBridgeBuild) {
            var other = Vars.world.tile(build.tileX() + 3, build.tileY());
            other.setBlock(build.block, build.team, 0);
            try {
                if (build instanceof ItemBridgeBuild bridge) {
                    bridge.link = other.pos();
                    bridge.warmup = 1f;
                }
                drawBuilding(build);
                if (!bridgeSpan) throw new AssertionError("Missing bridge span for " + build.block.name);
            } finally {
                other.setBlock(Blocks.air);
            }
        }
    }

    private void drawBuilding(Building build) {
        sprites = 0;
        copperSprites = 0;
        bridgeSpan = false;
        layers.render(viewport, build.team, candidate -> candidate == build);
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, Blending blending) {
        sprites++;
        if (page == UpstreamTerrainDrawProbe.pageIndex(copper) && left == copper.getX() && top == copper.getY()) {
            copperSprites++;
        }
        if (Math.abs(drawWidth) >= 15 && Math.abs(drawHeight) <= 8) bridgeSpan = true;
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void drawSolidQuad(float[] vertices, int offset, Blending blending) {}
    @Override public void end() {}
}
