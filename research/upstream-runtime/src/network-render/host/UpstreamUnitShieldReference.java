import arc.Core;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.entities.abilities.Ability;
import mindustry.entities.abilities.EnergyFieldAbility;
import mindustry.entities.abilities.ForceFieldAbility;
import mindustry.entities.abilities.SuppressionFieldAbility;
import mindustry.game.Team;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.CacheLayer;
import mindustry.type.UnitType;
import port.render.UpstreamFluidAtlas;

/**
 * Verifies the stock drawable unit abilities on the graphical client, which runs
 * without a desktop Renderer (the patched shield path takes Lines/Fill).
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

        var target = new UpstreamUnitShieldReference();
        var viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        viewport.moveTo(73, 20);
        Vars.player = Player.create();
        Vars.player.team(Team.sharded);

        UnitType force = find(ForceFieldAbility.class, "ForceFieldAbility");
        UnitType energy = find(EnergyFieldAbility.class, "EnergyFieldAbility");
        UnitType suppress = find(SuppressionFieldAbility.class, "SuppressionFieldAbility");

        int forceQuads = draw(target, force);
        int energyQuads = draw(target, energy);
        int suppressQuads = draw(target, suppress);
        if (forceQuads <= 0 || energyQuads <= 0 || suppressQuads <= 0) {
            throw new AssertionError("Stock ability draw missing geometry: force=" + forceQuads
                + " energy=" + energyQuads + " suppress=" + suppressQuads);
        }
        System.out.println("UPSTREAM_UNIT_ABILITY force=" + force.name + ":" + forceQuads
            + " energy=" + energy.name + ":" + energyQuads
            + " suppress=" + suppress.name + ":" + suppressQuads);
    }

    private static UnitType find(Class<? extends Ability> cls, String label) {
        for (var candidate : Vars.content.units()) {
            for (var ability : candidate.abilities) {
                if (cls.isInstance(ability)) return candidate;
            }
        }
        throw new AssertionError("No stock unit has a " + label);
    }

    private static int draw(UpstreamUnitShieldReference target, UnitType type) {
        if (!UpstreamRenderedNetworkProbe.loadUnitGraphics(type)) {
            throw new AssertionError("Could not load stock unit graphics for " + type.name);
        }
        Unit unit = type.spawn(Team.sharded, 600f, 200f, 0f);
        unit.shield = 100f;
        target.solidQuads = 0;
        unit.draw();
        return target.solidQuads;
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
