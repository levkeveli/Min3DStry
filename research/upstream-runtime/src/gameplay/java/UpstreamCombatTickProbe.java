import arc.Core;
import arc.math.Mathf;
import arc.mock.MockAudio;
import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.gen.Groups;
import mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild;

public final class UpstreamCombatTickProbe {
    public static void main(String[] args) throws IOException {
        var app = UpstreamSimulationWorld.play(args);
        Core.audio = new MockAudio();
        var team = Vars.state.rules.defaultTeam;
        var enemy = Vars.state.rules.waveTeam;
        var core = team.core();
        int x = core.tile.x - 8;
        int y = core.tile.y;
        var turretTile = Vars.world.tile(x, y);
        var beltTile = Vars.world.tile(x - 1, y);
        var sourceTile = Vars.world.tile(x - 2, y);
        var targetTile = Vars.world.tile(x - 5, y);
        turretTile.setBlock(Blocks.duo, team, 0);
        beltTile.setBlock(Blocks.conveyor, team, 0);
        sourceTile.setBlock(Blocks.junction, team, 0);
        var turret = (ItemTurretBuild) turretTile.build;
        var belt = beltTile.build;
        var target = UnitTypes.dagger.create(enemy);
        // Hold movement constant to isolate targeting, projectile motion and damage.
        target.controller(new AIController() {
            @Override
            public void updateUnit() {}
        });
        target.set(targetTile.worldx(), targetTile.worldy());
        target.add();
        float health = target.health;
        Mathf.rand.setSeed(160_008L);

        int supplied = 0;
        int delivery = 0;
        int firstShot = 0;
        int firstHit = 0;
        int destroyed = 0;
        for (int tick = 1; tick <= 720; tick++) {
            if (supplied < 20 && belt.acceptItem(sourceTile.build, Items.copper)) {
                belt.handleItem(sourceTile.build, Items.copper);
                supplied++;
            }
            Vars.logic.update();
            app.runPosted();
            if (delivery == 0 && turret.totalAmmo > 0) delivery = tick;
            if (firstShot == 0 && Groups.bullet.size() > 0) firstShot = tick;
            if (firstHit == 0 && target.health < health) firstHit = tick;
            if (target.dead || !target.isAdded()) {
                destroyed = tick;
                break;
            }
        }
        if (!(delivery > 0 && firstShot >= delivery && firstHit > firstShot && destroyed > firstHit)
                || !target.dead || target.isAdded()) {
            throw new IllegalStateException("Incomplete upstream copper combat trace " +
                delivery + "/" + firstShot + "/" + firstHit + "/" + destroyed +
                " ammo=" + turret.totalAmmo + " health=" + target.health);
        }
        System.out.println("UPSTREAM_COMBAT delivery=" + delivery + " shot=" + firstShot
            + " hit=" + firstHit + " destroyed=" + destroyed + " supplied=" + supplied);
        Vars.logic.runWave();
        app.runPosted();
        int enemies = Groups.unit.count(unit -> unit.team == enemy);
        var composition = new StringBuilder();
        for (var type : Vars.content.units()) {
            int count = Groups.unit.count(unit -> unit.team == enemy && unit.type == type);
            if (count > 0) {
                if (composition.length() > 0) composition.append(',');
                composition.append(type.name).append('=').append(count);
            }
        }
        if (Vars.state.wave != 2 || enemies == 0) {
            throw new IllegalStateException("Glacier's first wave did not spawn");
        }
        System.out.println("UPSTREAM_WAVE next=" + Vars.state.wave + " groundSpawns="
            + Vars.spawner.countGroundSpawns() + " enemies=" + enemies + " units=" + composition);
    }
}
