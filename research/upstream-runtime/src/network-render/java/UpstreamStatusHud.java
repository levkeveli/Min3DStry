import arc.Core;
import arc.util.Strings;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/** Stock wave and core-copper rows for the live ARM11 status HUD. */
final class UpstreamStatusHud {
    private UpstreamStatusHud() {
    }

    static List<UpstreamObjectiveHud.Row> collect() {
        return collect(null);
    }

    static List<UpstreamObjectiveHud.Row> collect(Team winner) {
        var rows = new ArrayList<UpstreamObjectiveHud.Row>();
        var state = Vars.state;
        if (winner != null) {
            add(rows, roundTitle(winner));
            if (state.rules.waves) {
                add(rows, Core.bundle.get("stats.wave") + " " + state.wave);
            }
        }
        String notification = UpstreamNotification.current();
        if (notification != null) add(rows, notification);
        var rules = state.rules;
        var unit = Vars.player == null ? null : Vars.player.unit();
        if (unit != null) {
            for (String text : UpstreamStatusRows.unitRows(Core.bundle,
                    Math.round(unit.health), Math.round(unit.maxHealth()),
                    Math.round(unit.ammof() * 100), unit.type.ammoCapacity)) {
                add(rows, text);
            }
        }
        int enemyCores = 0;
        if (rules.attackMode && Vars.player != null && state.teams != null) {
            var playerTeam = Vars.player.team();
            for (var data : state.teams.present) {
                if (data.team != playerTeam) enemyCores += data.cores.size;
            }
        }
        var texts = UpstreamStatusRows.texts(Core.bundle, rules.waves, state.isCampaign(),
            rules.attackMode, rules.waveTimer, rules.waveTimer && Vars.logic.isWaitingWave(),
            state.wave, rules.winWave, Math.max(0, state.enemies), enemyCores,
            (int)(state.wavetime / 60f));
        for (String text : texts) add(rows, text);

        int copper = copper();
        var item = Items.copper;
        if (item.uiIcon == null) item.loadIcon();
        rows.add(new UpstreamObjectiveHud.Row(
            item.localizedName + " " + copper,
            item.emoji() + " " + copper,
            item.name,
            item.uiIcon != null && item.uiIcon.found() ? item.uiIcon : null));
        return rows;
    }

    static String roundTitle(Team winner) {
        if (Vars.state.rules.pvp) return Core.bundle.format("gameover.pvp", winner.coloredName());
        if (Vars.state.isCampaign()) return Core.bundle.format("sector.lost", Vars.state.getSector().name());
        return Core.bundle.get("gameover");
    }

    private static void add(List<UpstreamObjectiveHud.Row> rows, String text) {
        rows.add(new UpstreamObjectiveHud.Row(Strings.stripColors(text), text, "none", null));
    }


    static int copper() {
        var core = core();
        return core == null ? 0 : core.items.get(Items.copper);
    }

    private static CoreBuild core() {
        var player = Vars.player;
        return player == null || player.team() == null ? null : player.team().core();
    }
}
