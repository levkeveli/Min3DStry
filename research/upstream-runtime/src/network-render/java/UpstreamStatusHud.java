import arc.Core;
import arc.util.Strings;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/** Stock wave and core-copper rows for the live ARM11 status HUD. */
final class UpstreamStatusHud {
    private UpstreamStatusHud() {
    }

    static List<UpstreamObjectiveHud.Row> collect() {
        var rows = new ArrayList<UpstreamObjectiveHud.Row>();
        var state = Vars.state;
        var rules = state.rules;
        var texts = UpstreamStatusRows.texts(Core.bundle, rules.waves, state.isCampaign(),
            rules.waveTimer, rules.waveTimer && Vars.logic.isWaitingWave(), state.wave,
            rules.winWave, Math.max(0, state.enemies), (int)(state.wavetime / 60f));
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
