import arc.Core;
import arc.files.Fi;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.entities.abilities.Ability;
import mindustry.entities.abilities.ArmorPlateAbility;
import mindustry.entities.abilities.EnergyFieldAbility;
import mindustry.entities.abilities.ForceFieldAbility;
import mindustry.entities.abilities.MoveLightningAbility;
import mindustry.entities.abilities.ShieldArcAbility;
import mindustry.entities.abilities.SuppressionFieldAbility;
import mindustry.entities.abilities.UnitSpawnAbility;
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
    private int sprites;
    private int unitBuilds;

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
        Time.delta = 1f;

        var result = new StringBuilder();
        for (var cls : new Class<?>[]{ForceFieldAbility.class, EnergyFieldAbility.class,
                SuppressionFieldAbility.class, ArmorPlateAbility.class, ShieldArcAbility.class,
                UnitSpawnAbility.class, MoveLightningAbility.class}) {
            UnitType type = find(cls);
            if (type == null) continue;
            int drawn = draw(target, type);
            if (result.length() > 0) result.append(',');
            result.append(label(cls)).append('=').append(type.name).append(':').append(drawn);
        }
        System.out.println("UPSTREAM_UNIT_ABILITY " + result);
    }

    private static String label(Class<?> cls) {
        var name = cls.getSimpleName();
        return name.endsWith("Ability") ? name.substring(0, name.length() - 7).toLowerCase() : name;
    }

    private static UnitType find(Class<?> cls) {
        for (var candidate : Vars.content.units()) {
            for (var ability : candidate.abilities) {
                if (cls.isInstance(ability)) return candidate;
            }
        }
        return null;
    }

    private static int draw(UpstreamUnitShieldReference target, UnitType type) {
        if (!UpstreamRenderedNetworkProbe.loadUnitGraphics(type)) {
            throw new AssertionError("Could not load stock unit graphics for " + type.name);
        }
        Unit unit = type.spawn(Team.sharded, 600f, 200f, 0f);
        unit.shield = 100f;
        unit.isShooting = true;
        unit.vel.set(3f, 0f);
        Time.delta = 1f;
        for (int i = 0; i < 20; i++) {
            for (var ability : unit.abilities) ability.update(unit);
        }
        target.solidQuads = 0;
        target.sprites = 0;
        target.unitBuilds = 0;
        unit.draw();
        return target.solidQuads + target.sprites + target.unitBuilds;
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}
    @Override public void drawSolidQuad(float[] vertices, int offset, arc.graphics.Blending blending) {
        solidQuads++;
    }

    @Override
    public void drawUnitBuild(int page, int left, int top, int width, int height, float x, float y,
                              float drawWidth, float drawHeight, float rotation, float progress,
                              arc.graphics.Color tint, float alpha, float phase, int vertexColor) {
        unitBuilds++;
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, arc.graphics.Blending blending) {
        sprites++;
    }
}
