import arc.Core;
import arc.mock.MockFiles;
import arc.mock.MockGraphics;
import arc.util.Time;
import java.io.IOException;
import mindustry.Vars;
import mindustry.ai.BaseRegistry;
import mindustry.ai.BlockIndexer;
import mindustry.ai.WaveSpawner;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.entities.EntityCollisions;
import mindustry.game.Universe;
import mindustry.logic.GlobalVars;

public final class UpstreamFactoryTickProbe {
    public static void main(String[] args) throws IOException {
        var app = UpstreamWorldLoadProbe.load(args);
        Core.graphics = new MockGraphics() {
            @Override
            public float getDeltaTime() {
                return 1f / 60f;
            }
        };
        Core.files = new MockFiles();
        Time.setDeltaProvider(() -> 1f);
        Vars.universe = new Universe();
        Vars.logicVars = new GlobalVars();
        Vars.logicVars.init();
        Vars.collisions = new EntityCollisions();
        Vars.indexer = new BlockIndexer();
        Vars.spawner = new WaveSpawner();
        Vars.spawner.reset();
        Vars.bases = new BaseRegistry();
        Vars.bases.load();
        Vars.logic.play();

        var team = Vars.state.rules.defaultTeam;
        var core = team.core();
        int x = core.tile.x - core.block.size / 2 - 1;
        int y = core.tile.y;
        var conveyor = Vars.world.tile(x, y);
        var source = Vars.world.tile(x - 1, y);
        conveyor.setBlock(Blocks.conveyor, team, 0);
        source.setBlock(Blocks.junction, team, 0);
        if (conveyor.block() != Blocks.conveyor || !conveyor.build.acceptItem(source.build, Items.copper)) {
            throw new IllegalStateException("Upstream conveyor refused the test item");
        }
        if (core.items.get(Items.copper) != 200) {
            throw new IllegalStateException("Glacier's starting core inventory differs");
        }
        conveyor.build.handleItem(source.build, Items.copper);
        int arrival = -1;
        for (int tick = 1; tick <= 240; tick++) {
            Vars.logic.update();
            app.runPosted();
            if (arrival == -1 && core.items.get(Items.copper) == 201) {
                arrival = tick;
            }
        }
        int copper = core.items.get(Items.copper);
        if (arrival != 29 || copper != 201 || Vars.state.updateId != 240 || Vars.state.tick != 240.0
                || Vars.state.wave != 1 || Vars.state.gameOver) {
            throw new IllegalStateException("Factory ticks differ at copper arrival " + arrival + " and count " + copper);
        }
        System.out.println("UPSTREAM_FACTORY updates=240 wave=1 arrival=29 copper=201");
    }
}
