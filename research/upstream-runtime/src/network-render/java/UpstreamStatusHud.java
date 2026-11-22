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
        // Mirrors v160.8 HudFragment.makeStatusTable: wave.cap while the win
        // wave is ahead, enemies only when present, then timer/waiting.
        var rows = new ArrayList<UpstreamObjectiveHud.Row>();
        if (Vars.state.rules.waves) {
            int wave = Vars.state.wave;
            String waveText = Vars.state.rules.winWave > 1 && Vars.state.rules.winWave >= wave
                ? Core.bundle.format("wave.cap", wave, Vars.state.rules.winWave)
                : Core.bundle.format("wave", wave);
            add(rows, waveText);

            int enemies = Math.max(0, Vars.state.enemies);
            if (enemies > 0) {
                add(rows, Core.bundle.format(enemies == 1 ? "wave.enemy" : "wave.enemies", enemies));
            }

            if (Vars.state.rules.waveTimer) {
                add(rows, Vars.logic.isWaitingWave()
                    ? Core.bundle.get("wave.waveInProgress")
                    : Core.bundle.format("wave.waiting", formatTime((int)(Vars.state.wavetime / 60f))));
            } else if (enemies == 0) {
                add(rows, Core.bundle.get("waiting"));
            }
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

    private static void add(List<UpstreamObjectiveHud.Row> rows, String text) {
        rows.add(new UpstreamObjectiveHud.Row(Strings.stripColors(text), text, "none", null));
    }

    /** Matches the stock HudFragment wave timer formatting. */
    static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        if (m > 0) {
            return m + ":" + (s < 10 ? "0" : "") + s;
        }
        return Integer.toString(s);
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
