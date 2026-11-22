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
        if (Vars.state.rules.waves) {
            String wave = Core.bundle.format("wave", Vars.state.wave);
            rows.add(new UpstreamObjectiveHud.Row(Strings.stripColors(wave), wave, "none", null));
            String enemies = Core.bundle.format("wave.enemies", Math.max(0, Vars.state.enemies));
            rows.add(new UpstreamObjectiveHud.Row(Strings.stripColors(enemies), enemies, "none", null));
        }

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

    static int copper() {
        var core = core();
        return core == null ? 0 : core.items.get(Items.copper);
    }

    private static CoreBuild core() {
        var player = Vars.player;
        return player == null || player.team() == null ? null : player.team().core();
    }
}
