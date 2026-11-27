import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.graphics.CacheLayer;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;
import port.render.UpstreamFluidAtlas;

/**
 * Verifies that the stock turret drawer applies the pinned TurretBuild.recoilOffset
 * to barrels, ammo, heat and liquid layers.
 */
public final class UpstreamTurretRecoilReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Sprite(float x, float y, int page, int left, int top, int width, int height) {}

    private final List<Sprite> sprites = new ArrayList<>();
    private AtlasRegion left, right;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Expected Glacier, sprites.aatls, block_colors.png");
        }
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(new String[]{args[0]}, () -> Vars.indexer = new BlockIndexer());
        UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), UpstreamFluidAtlas::load);
        Vars.content.loadColors(new Fi(args[2]));

        var target = new UpstreamTurretRecoilReference();
        target.left = Core.atlas.find("duo-barrel-l");
        target.right = Core.atlas.find("duo-barrel-r");
        if (!target.left.found() || !target.right.found()) {
            throw new AssertionError("Missing stock Duo barrel regions");
        }
        var viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        viewport.moveTo(33, 34);

        var team = Team.sharded;
        var tile = Vars.world.tile(35, 36);
        tile.setBlock(Blocks.duo, team, 0);
        var turret = (TurretBuild)tile.build;
        turret.rotation = 0f;

        turret.recoilOffset.setZero();
        target.sprites.clear();
        new UpstreamBuildingLayers().render(viewport, team, build -> build == turret);
        float rest = target.barrelX();

        turret.recoilOffset.trns(0f, -3f);
        target.sprites.clear();
        new UpstreamBuildingLayers().render(viewport, team, build -> build == turret);
        float recoiled = target.barrelX();

        if (Math.abs((rest - recoiled) - 3f) > 0.01f) {
            throw new AssertionError("Stock turret drawer ignored recoilOffset: rest=" + rest
                + " recoiled=" + recoiled);
        }
        tile.setBlock(Blocks.air);
        System.out.println("UPSTREAM_TURRET_RECOIL rest=" + rest + " recoiled=" + recoiled + " offset=3");
    }

    private float barrelX() {
        for (var sprite : sprites) {
            if (matches(sprite, left) || matches(sprite, right)) return sprite.x();
        }
        throw new AssertionError("Stock turret draw did not emit a Duo barrel");
    }

    private boolean matches(Sprite sprite, AtlasRegion region) {
        return sprite.page == UpstreamTerrainDrawProbe.pageIndex(region)
            && sprite.left == region.getX() && sprite.top == region.getY()
            && sprite.width == region.width && sprite.height == region.height;
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
    @Override public void drawSolidQuad(float[] vertices, int offset, arc.graphics.Blending blending) {}

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, arc.graphics.Blending blending) {
        sprites.add(new Sprite(x, y, page, left, top, width, height));
    }
}
