package port.logic;

import arc.files.Fi;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.gen.Groups;
import mindustry.logic.GlobalVars;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/** Connects the pinned logic globals and processor ticks to the live client. */
public final class UpstreamClientLogic {
    private static Fi ids;

    public static void initialize(Fi logicIds) {
        if (!logicIds.exists()) throw new IllegalStateException("Missing original logicids.dat");
        ids = logicIds;
        UI.loadColors();
        Vars.logicVars = new GlobalVars();
        Vars.logicVars.init();
    }

    public static Fi logicIds() {
        return ids;
    }

    public static void update(float elapsedTicks) {
        Vars.state.tick += elapsedTicks;
        Vars.state.updateId++;
        Vars.logicVars.update();
        for (var build : Groups.build) {
            if (build instanceof LogicBuild processor) processor.update();
        }
    }
}
