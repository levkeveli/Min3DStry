import arc.Core;
import arc.graphics.Blending;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.entities.Puddles;
import mindustry.game.Team;
import mindustry.net.Net;
import mindustry.world.Tile;

/** Captures original liquid geometry and the ordering of cell liquid on a solid block. */
final class UpstreamPuddleReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private static final int WATER_X = 28, CELL_X = 34, FAR_X = 47, Y = 36;
    private final AtlasRegion wallRegion = (AtlasRegion)Core.atlas.find("beryllium-wall");
    private final AtlasRegion circleRegion = (AtlasRegion)Core.atlas.find("circle");
    private int sequence, wallOrder, waterQuads, cellQuads, cellAboveWall, farQuads;

    static void verify() {
        var target = new UpstreamPuddleReference();
        var viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        viewport.moveTo(24, 30);
        var water = Vars.world.tile(WATER_X, Y);
        var cell = Vars.world.tile(CELL_X, Y);
        var far = Vars.world.tile(FAR_X, Y);
        var waterFloor = water.floor();
        var cellFloor = cell.floor();
        var farFloor = far.floor();
        var previousNet = Vars.net;
        Vars.net = new Net(null);
        try {
            water.setFloor(Blocks.stone.asFloor());
            cell.setFloor(Blocks.stone.asFloor());
            far.setFloor(Blocks.stone.asFloor());
            cell.setBlock(Blocks.berylliumWall, Team.sharded);
            Puddles.deposit(water, Liquids.water, 60);
            Puddles.deposit(cell, Liquids.neoplasm, 60);
            Puddles.deposit(far, Liquids.neoplasm, 60);
            if (Puddles.get(water) == null || Puddles.get(cell) == null || Puddles.get(far) == null) {
                throw new AssertionError("Original puddle creation failed");
            }
            var layers = new UpstreamBuildingLayers();
            viewport.draw(() -> layers.render(viewport, Team.sharded, build -> build == cell.build));
            if (target.waterQuads == 0 || target.cellQuads <= target.waterQuads || target.wallOrder == 0
                    || target.cellAboveWall == 0 || target.farQuads != 0) {
                throw new AssertionError("Stock puddle geometry or layer order changed: water=" + target.waterQuads
                    + " cell=" + target.cellQuads + " over=" + target.cellAboveWall + " far=" + target.farQuads);
            }
            System.out.println("UPSTREAM_PUDDLE_REFERENCE water=" + target.waterQuads + " neoplasm="
                + target.cellQuads + " overWall=" + target.cellAboveWall + " offscreen=culled");
        } finally {
            removePuddle(water);
            removePuddle(cell);
            removePuddle(far);
            water.setFloor(waterFloor);
            cell.setFloor(cellFloor);
            far.setFloor(farFloor);
            cell.setBlock(Blocks.air);
            Vars.net = previousNet;
        }
    }

    private static void removePuddle(Tile tile) {
        var puddle = Puddles.get(tile);
        if (puddle != null) puddle.remove();
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, Blending blending) {
        sequence++;
        if (page == UpstreamTerrainDrawProbe.pageIndex(wallRegion)
                && left == wallRegion.getX() && top == wallRegion.getY()) wallOrder = sequence;
        if (page != UpstreamTerrainDrawProbe.pageIndex(circleRegion)
                || left != circleRegion.getX() || top != circleRegion.getY()) return;
        x += originX;
        y += originY;
        if (Math.abs(y - Y * Vars.tilesize) > 16f) return;
        if (Math.abs(x - WATER_X * Vars.tilesize) <= 16f) waterQuads++;
        if (Math.abs(x - CELL_X * Vars.tilesize) <= 16f) {
            cellQuads++;
            if (wallOrder > 0 && sequence > wallOrder) cellAboveWall++;
        }
        if (Math.abs(x - FAR_X * Vars.tilesize) <= 16f) farQuads++;
    }

    @Override
    public void drawSolidQuad(float[] vertices, int offset, Blending blending) {}

    @Override
    public void begin(int left, int bottom, int width, int height) {}

    @Override
    public void end() {}
}
