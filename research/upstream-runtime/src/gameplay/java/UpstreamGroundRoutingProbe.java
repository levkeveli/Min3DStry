import arc.Core;
import arc.Events;
import arc.mock.MockAudio;
import java.io.IOException;
import mindustry.Vars;
import mindustry.ai.ControlPathfinder;
import mindustry.ai.Pathfinder;
import mindustry.ai.types.GroundAI;
import mindustry.async.AsyncCore;
import mindustry.content.UnitTypes;
import mindustry.game.EventType.ResetEvent;
import mindustry.world.Tile;

public final class UpstreamGroundRoutingProbe {
    public static void main(String[] args) throws IOException, InterruptedException {
        try {
            var app = UpstreamSimulationWorld.play(args, () -> {
                Vars.pathfinder = new Pathfinder();
                Vars.controlPath = new ControlPathfinder();
                Vars.asyncCore = new AsyncCore();
            });
            Core.audio = new MockAudio();
            var enemy = Vars.state.rules.waveTeam;
            var core = Vars.state.rules.defaultTeam.core();
            Tile start = Vars.world.tile(81, 55);
            var field = Vars.pathfinder.getField(enemy, Pathfinder.costGround, Pathfinder.fieldCore);
            for (int poll = 0; poll < 250 && !field.hasCompleteWeights(); poll++) {
                app.runPosted();
                Thread.sleep(16);
            }
            if (!field.hasCompleteWeights()) throw new IllegalStateException("Ground flowfield did not finish");
            app.runPosted();

            long weightsHash = 0xcbf29ce484222325L;
            for (int weight : field.completeWeights) {
                weightsHash = (weightsHash ^ Integer.toUnsignedLong(weight)) * 0x100000001b3L;
            }
            Tile current = start;
            int hops = 0;
            while (current.build != core && hops < 200) {
                Tile next = field.getNextTile(current);
                if (next == current) break;
                current = next;
                hops++;
            }
            if (current.build != core || hops == 0) {
                throw new IllegalStateException("Ground flowfield ended at " + current.x + "," + current.y
                    + " after " + hops + " hops");
            }
            System.out.println("UPSTREAM_GROUND_FIELD from=" + start.x + "," + start.y
                + " to=" + current.x + "," + current.y + " hops=" + hops
                + " hash=" + Long.toUnsignedString(weightsHash, 16));

            var unit = UnitTypes.dagger.create(enemy);
            unit.controller(UnitTypes.dagger.aiController.get());
            unit.set(start.worldx(), start.worldy());
            unit.add();
            if (!(unit.controller() instanceof GroundAI)) {
                throw new IllegalStateException("Expected the original Dagger ground controller");
            }
            int from = unit.tileY();
            for (int tick = 1; tick <= 120; tick++) {
                Vars.asyncCore.begin();
                Vars.logic.update();
                Vars.asyncCore.end();
                app.runPosted();
            }
            if (unit.tileY() >= from || unit.dead) {
                throw new IllegalStateException("Dagger did not advance toward the core");
            }
            System.out.println("UPSTREAM_GROUND_UNIT ticks=120 from=" + start.x + "," + start.y
                + " to=" + unit.tileX() + "," + unit.tileY());
        } finally {
            Events.fire(new ResetEvent());
        }
    }
}
