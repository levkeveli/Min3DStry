import arc.Core;
import arc.files.Fi;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.content.UnitTypes;
import mindustry.entities.abilities.ForceFieldAbility;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.gen.Player;
import mindustry.graphics.CacheLayer;
import mindustry.type.UnitType;
import port.render.UpstreamFluidAtlas;

/**
 * Verifies the stock ForceFieldAbility draw path on the graphical client, which
 * runs without a desktop Renderer (the patched ability takes the Lines/Fill path).
 */
public final class UpstreamUnitShieldReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private int solidQuads;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Expected Glacier, sprites.aatls, block_colors.png");
        }
        CacheLayer.init();
        UpstreamWorldLoadProbe.load(new String[]{args[0]}, () -> Vars.indexer = new BlockIndexer());
        UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), UpstreamFluidAtlas::load);
        Vars.content.loadColors(new Fi(args[2]));

        UnitType type = null;
        for (var candidate : Vars.content.units()) {
            for (var ability : candidate.abilities) {
                if (ability instanceof ForceFieldAbility) {
                    type = candidate;
                    break;
                }
            }
            if (type != null) break;
        }
        if (type == null) throw new AssertionError("No stock unit has a ForceFieldAbility");
        if (!UpstreamRenderedNetworkProbe.loadUnitGraphics(type)) {
            throw new AssertionError("Could not load stock unit graphics for " + type.name);
        }

        var target = new UpstreamUnitShieldReference();
        var viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        viewport.moveTo(73, 20);
        Unit unit = type.spawn(Team.sharded, 600f, 200f, 0f);
        unit.shield = 100f;
        Vars.player = Player.create();
        Vars.player.team(Team.sharded);
        Vars.player.unit(unit);

        target.solidQuads = 0;
        unit.draw();
        if (target.solidQuads == 0) {
            throw new AssertionError("Stock shield ability drew no geometry");
        }
        System.out.println("UPSTREAM_UNIT_SHIELD unit=" + type.name + " quads=" + target.solidQuads);
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
    @Override public void drawSolidQuad(float[] vertices, int offset, arc.graphics.Blending blending) {
        solidQuads++;
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, arc.graphics.Blending blending) {
    }
}
