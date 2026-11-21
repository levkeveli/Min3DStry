import arc.Core;
import arc.mock.MockFiles;
import arc.mock.MockGraphics;
import arc.util.Time;
import java.io.IOException;
import mindustry.Vars;
import mindustry.ai.BaseRegistry;
import mindustry.ai.BlockIndexer;
import mindustry.ai.WaveSpawner;
import mindustry.entities.EntityCollisions;
import mindustry.game.Universe;
import mindustry.graphics.CacheLayer;
import mindustry.logic.GlobalVars;

final class UpstreamSimulationWorld {
    static UpstreamWorldLoadProbe.ProbeApplication play(String[] args) throws IOException {
        return play(args, () -> {});
    }

    static UpstreamWorldLoadProbe.ProbeApplication play(String[] args, Runnable beforeMap) throws IOException {
        CacheLayer.init();
        var app = UpstreamWorldLoadProbe.load(args, () -> {
            Vars.indexer = new BlockIndexer();
            Vars.spawner = new WaveSpawner();
            beforeMap.run();
        });
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
        Vars.bases = new BaseRegistry();
        Vars.bases.load();
        Vars.logic.play();
        return app;
    }
}
