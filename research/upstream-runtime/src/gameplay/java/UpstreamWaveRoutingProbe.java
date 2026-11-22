import arc.Core;
import arc.math.Mathf;
import arc.mock.MockAudio;
import arc.struct.Seq;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import mindustry.Vars;
import mindustry.ai.ControlPathfinder;
import mindustry.ai.Pathfinder;
import mindustry.async.AsyncCore;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild;

/**
 * Pinned upstream wave and ground-routing trace for issue #30.
 *
 * The probe starts the original Pathfinder, ControlPathfinder and AsyncCore
 * before loading Glacier, applies the campaign sector rules that enable waves
 * and attack mode, feeds copper into a Duo, and advances the original waves.
 * Ground units therefore spawn at the real enemy core and must follow the
 * upstream flowfield toward the player core while the turret is supplied.
 */
public final class UpstreamWaveRoutingProbe {
    private static final int TICKS_PER_WAVE = 900;

    public static void main(String[] args) throws IOException, InterruptedException {
        UpstreamWorldLoadProbe.ProbeApplication app = null;
        try {
            app = UpstreamSimulationWorld.play(args, () -> {
                Vars.pathfinder = new Pathfinder();
                Vars.controlPath = new ControlPathfinder();
                Vars.asyncCore = new AsyncCore();
            });
            Core.audio = new MockAudio();
            var team = Vars.state.rules.defaultTeam;
            var enemy = Vars.state.rules.waveTeam;
            var core = team.core();
            var enemyCore = enemy.cores().first();
            int x = core.tile.x;
            int y = core.tile.y;
            // Keep the Duo and its feed clear of the core footprint.
            var turretTile = Vars.world.tile(x - 8, y);
            var beltTile = Vars.world.tile(x - 9, y);
            var sourceTile = Vars.world.tile(x - 10, y);
            turretTile.setBlock(Blocks.duo, team, 0);
            beltTile.setBlock(Blocks.conveyor, team, 0);
            sourceTile.setBlock(Blocks.junction, team, 0);
            var turret = (ItemTurretBuild) turretTile.build;
            var belt = beltTile.build;

            // A campaign sector applies these two rules from SectorInfo before
            // play. The raw map has wave groups but no spawn markers, so ground
            // waves must use the upstream enemy-core spawn path.
            Vars.state.rules.waves = true;
            Vars.state.rules.attackMode = true;
            Vars.spawner.reset();
            if (Vars.spawner.countGroundSpawns() <= 0 || !Vars.state.hasSpawns()) {
                throw new IllegalStateException("Enemy-core ground spawns are unavailable");
            }

            Mathf.rand.setSeed(160_008L);
            long fieldHash = waitForGroundField(app, enemy);
            System.out.println("UPSTREAM_WAVE_SETUP core=" + core.tile.x + "," + core.tile.y
                + " enemyCore=" + enemyCore.tile.x + "," + enemyCore.tile.y
                + " groundSpawns=" + Vars.spawner.countGroundSpawns()
                + " fieldHash=" + Long.toUnsignedString(fieldHash, 16));

            Set<Integer> seen = new HashSet<>();
            for (Unit unit : Groups.unit) {
                seen.add(unit.id);
            }
            int supplied = 0;
            for (int wave = 1; wave <= 4; wave++) {
                Vars.logic.runWave();
                app.runPosted();
                var spawned = new Seq<Unit>();
                for (Unit unit : Groups.unit) {
                    if (unit.team == enemy && !unit.dead && unit.isAdded() && seen.add(unit.id)) {
                        spawned.add(unit);
                    }
                }
                int groundSpawned = 0;
                for (Unit unit : spawned) {
                    if (!unit.type.flying) {
                        groundSpawned++;
                    }
                }
                if (groundSpawned == 0) {
                    throw new IllegalStateException("Wave " + wave + " spawned no ground units");
                }
                Unit first = null;
                for (Unit unit : spawned) {
                    if (!unit.type.flying) {
                        first = unit;
                        break;
                    }
                }
                int startX = first.tileX();
                int startY = first.tileY();
                float startDistance = Mathf.dst(first.x, first.y, core.x, core.y);
                float minDistance = startDistance;

                for (int tick = 1; tick <= TICKS_PER_WAVE; tick++) {
                    if (supplied < 400 && belt.acceptItem(sourceTile.build, Items.copper)) {
                        belt.handleItem(sourceTile.build, Items.copper);
                        supplied++;
                    }
                    Vars.asyncCore.begin();
                    Vars.logic.update();
                    Vars.asyncCore.end();
                    app.runPosted();
                    if (first.isAdded() && !first.dead) {
                        minDistance = Math.min(minDistance,
                            Mathf.dst(first.x, first.y, core.x, core.y));
                    }
                }

                int alive = 0;
                float endDistance = startDistance;
                int endX = startX;
                int endY = startY;
                for (Unit unit : spawned) {
                    if (!unit.type.flying && unit.isAdded() && !unit.dead) {
                        alive++;
                    }
                }
                if (first.isAdded() && !first.dead) {
                    endX = first.tileX();
                    endY = first.tileY();
                    endDistance = Mathf.dst(first.x, first.y, core.x, core.y);
                }
                System.out.println("UPSTREAM_WAVE wave=" + wave
                    + " spawned=" + describe(spawned)
                    + " groundSpawned=" + groundSpawned
                    + " first=" + startX + "," + startY + "->" + endX + "," + endY
                    + " distance=" + (int)startDistance + "->" + (int)endDistance
                    + " min=" + (int)minDistance
                    + " alive=" + alive + "/" + groundSpawned
                    + " ammo=" + turret.totalAmmo
                    + " nextWave=" + Vars.state.wave);
                if (minDistance >= startDistance) {
                    throw new IllegalStateException("Wave " + wave
                        + " ground unit did not advance toward the core");
                }
            }
        } catch (RuntimeException failure) {
            System.err.println("WAVE_ROUTING_ERROR " + failure);
            throw failure;
        } finally {
            if (app != null) app.disposeListeners();
        }
        // This is a one-shot probe process. Firing ResetEvent here dispatches into
        // TeaVM's monitor/event queue after the trace and can deadlock the host C
        // build; the process exits immediately after main instead.
    }

    private static long waitForGroundField(
        UpstreamWorldLoadProbe.ProbeApplication app, Team enemy)
        throws InterruptedException {
        var field = Vars.pathfinder.getField(enemy, Pathfinder.costGround, Pathfinder.fieldCore);
        // Avoid TeaVM's sleep timer/fiber here: the host C runtime can abort in
        // Object.monitorEnterWait while unwinding a pending sleep callback.
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (!field.hasCompleteWeights() && System.nanoTime() < deadline) {
            app.runPosted();
            Thread.yield();
        }
        if (!field.hasCompleteWeights()) {
            throw new IllegalStateException("Ground flowfield did not finish");
        }
        app.runPosted();
        long hash = 0xcbf29ce484222325L;
        for (int weight : field.completeWeights) {
            hash = (hash ^ Integer.toUnsignedLong(weight)) * 0x100000001b3L;
        }
        return hash;
    }

    private static String describe(Seq<Unit> units) {
        var composition = new StringBuilder();
        for (var type : Vars.content.units()) {
            int count = units.count(unit -> unit.type == type && !unit.dead && unit.isAdded());
            if (count > 0) {
                if (composition.length() > 0) composition.append(',');
                composition.append(type.name).append('=').append(count);
            }
        }
        return composition.toString();
    }
}
