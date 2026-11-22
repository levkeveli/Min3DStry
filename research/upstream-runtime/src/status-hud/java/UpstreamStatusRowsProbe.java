import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Host coverage for every v160.8 HudFragment status-row branch. */
public final class UpstreamStatusRowsProbe {
    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "/3ds/min3dstry/bundles/bundle";
        I18NBundle bundle = I18NBundle.createBundle(new Fi(path), Locale.ROOT);
        check(bundle, "cap-enemy-timer", true, true, true, false, 2, 10, 1, 309,
            "[accent]Wave 2/10", "[lightgray]1 Enemy Remaining", "[lightgray]Wave in 5:09");
        check(bundle, "plural-enemies", true, true, true, false, 2, 10, 2, 309,
            "[accent]Wave 2/10", "[lightgray]2 Enemies Remaining", "[lightgray]Wave in 5:09");
        check(bundle, "timer-off-waiting", true, true, false, false, 2, 1, 0, 0,
            "[accent]Wave 2", "[lightgray]Waiting...");
        check(bundle, "wave-in-progress", true, true, true, true, 1, 1, 1, 0,
            "[accent]Wave 1", "[lightgray]1 Enemy Remaining", "[lightgray]Wave in progress");
        check(bundle, "captured", false, true, false, false, 0, 0, 0, 0,
            "Sector Captured");
        check(bundle, "non-campaign", false, false, false, false, 0, 0, 0, 0);
        check(bundle, "minute-pad", true, true, true, false, 1, 1, 0, 65,
            "[accent]Wave 1", "[lightgray]Wave in 1:05");
        check(bundle, "seconds-only", true, true, true, false, 1, 1, 0, 45,
            "[accent]Wave 1", "[lightgray]Wave in 45");
        System.out.println("UPSTREAM_STATUS_ROWS cases=8 ok");
    }

    private static void check(I18NBundle bundle, String label, boolean waves, boolean campaign,
            boolean waveTimer, boolean waitingWave, int wave, int winWave,
            int enemies, int seconds, String... expected) {
        var actual = new ArrayList<String>(
            UpstreamStatusRows.texts(bundle, waves, campaign, waveTimer,
                waitingWave, wave, winWave, enemies, seconds));
        List<String> want = Arrays.asList(expected);
        if (!actual.equals(want)) {
            throw new IllegalStateException(label + ": expected " + want + " got " + actual);
        }
    }
}
