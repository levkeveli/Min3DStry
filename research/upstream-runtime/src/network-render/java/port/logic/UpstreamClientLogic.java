package port.logic;

import arc.Core;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.gen.Groups;
import mindustry.logic.GlobalVars;

/** Runs the stock logic clock, power graphs and building updates on the live client. */
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
        if (ids != null) return ids;
        var bundled = new Fi("/romfs/logicids.dat");
        return bundled.exists() ? bundled : Core.files.internal("logicids.dat");
    }

    public static void update(float elapsedTicks) {
        if (Vars.state.isPaused()) return;
        var state = Vars.state;
        state.tick += elapsedTicks;
        state.updateId++;
        state.teams.updateTeamStats();
        Vars.logicVars.update();
        state.envAttrs.clear();
        state.envAttrs.add(state.rules.attributes);
        Groups.weather.each(weather -> state.envAttrs.add(weather.weather.attrs, weather.opacity));
        if (!state.isEditor()) {
            // Logic.updateEntities runs these groups in this order in stock clients.
            Groups.powerGraph.update();
            Groups.build.update();
        }
    }
}
