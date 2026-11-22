import arc.util.I18NBundle;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure v160.8 HudFragment status-row text logic, shared by the live renderer
 * and a host probe. Kept free of Vars/state so every branch is directly testable.
 */
public final class UpstreamStatusRows {
    private UpstreamStatusRows() {
    }

    public static List<String> texts(I18NBundle bundle, boolean waves, boolean campaign,
            boolean attackMode, boolean waveTimer, boolean waitingWave, int wave, int winWave,
            int enemies, int enemyCores, int seconds) {
        var rows = new ArrayList<String>();
        if (!waves && attackMode) {
            rows.add(bundle.format(enemyCores > 1 ? "wave.enemycores" : "wave.enemycore",
                Math.max(1, enemyCores)));
            return rows;
        }
        if (waves) {
            rows.add(winWave > 1 && winWave >= wave
                ? bundle.format("wave.cap", wave, winWave)
                : bundle.format("wave", wave));
            if (enemies > 0) {
                rows.add(bundle.format(enemies == 1 ? "wave.enemy" : "wave.enemies", enemies));
            }
            if (waveTimer) {
                rows.add(waitingWave
                    ? bundle.get("wave.waveInProgress")
                    : bundle.format("wave.waiting", formatTime(seconds)));
            } else if (enemies == 0) {
                rows.add(bundle.get("waiting"));
            }
        } else if (campaign) {
            rows.add(bundle.get("sector.curcapture"));
        }
        return rows;
    }

    /** Matches the stock HudFragment timer formatting (seconds-only below one minute). */
    public static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        if (m > 0) {
            return m + ":" + (s < 10 ? "0" : "") + s;
        }
        return Integer.toString(s);
    }
}
