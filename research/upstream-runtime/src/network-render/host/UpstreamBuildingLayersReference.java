import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Layer;

public final class UpstreamBuildingLayersReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Sprite(float x, float y, int page, int left, int top, int color, Blending blending) {}
    private final List<Sprite> sprites = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected Glacier and sprites.aatls");
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(new String[]{args[0]});
        UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), true);

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
        new UpstreamBuildingLayers().render(viewport, viewer, block ->
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
        sprites.add(new Sprite(x + drawWidth / 2f, y + drawHeight / 2f, page, left, top, color, blending));
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
}
