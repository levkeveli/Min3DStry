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
        check(bundle, "cap-enemy-timer", true, true, false, true, false, 2, 10, 1, 0, 309,
            "[accent]Wave 2/10", "[lightgray]1 Enemy Remaining", "[lightgray]Wave in 5:09");
        check(bundle, "plural-enemies", true, true, false, true, false, 2, 10, 2, 0, 309,
            "[accent]Wave 2/10", "[lightgray]2 Enemies Remaining", "[lightgray]Wave in 5:09");
        check(bundle, "timer-off-waiting", true, true, false, false, false, 2, 1, 0, 0, 0,
            "[accent]Wave 2", "[lightgray]Waiting...");
        check(bundle, "wave-in-progress", true, true, false, true, true, 1, 1, 1, 0, 0,
            "[accent]Wave 1", "[lightgray]1 Enemy Remaining", "[lightgray]Wave in progress");
        check(bundle, "captured", false, true, false, false, false, 0, 0, 0, 0, 0,
            "Sector Captured");
        check(bundle, "non-campaign", false, false, false, false, false, 0, 0, 0, 0, 0);
        check(bundle, "minute-pad", true, true, false, true, false, 1, 1, 0, 0, 65,
            "[accent]Wave 1", "[lightgray]Wave in 1:05");
        check(bundle, "seconds-only", true, true, false, true, false, 1, 1, 0, 0, 45,
            "[accent]Wave 1", "[lightgray]Wave in 45");
        check(bundle, "enemy-core", false, false, true, false, false, 0, 0, 0, 1, 0,
            "[accent]1[lightgray] Enemy Core");
        check(bundle, "enemy-cores", false, false, true, false, false, 0, 0, 0, 2, 0,
            "[accent]2[lightgray] Enemy Cores");
        checkUnit(bundle, "full-health", 100, 100, 0, 0, "Health 100/100");
        checkUnit(bundle, "health-ammo", 37, 100, 25, 20, "Health 37/100", "Ammo 25%");
        checkUnit(bundle, "no-health", 0, 0, 0, 0);
        UpstreamNotification.show("Sector Captured!", 5f);
        if (!"Sector Captured!".equals(UpstreamNotification.current())) {
            throw new IllegalStateException("notification was not shown");
        }
        UpstreamNotification.update(2f);
        if (UpstreamNotification.current() == null) {
            throw new IllegalStateException("notification expired too early");
        }
        UpstreamNotification.update(4f);
        if (UpstreamNotification.current() != null) {
            throw new IllegalStateException("notification did not expire");
        }
        System.out.println("UPSTREAM_STATUS_ROWS cases=10 ok");
        System.out.println("UPSTREAM_UNIT_ROWS cases=3 ok");
        System.out.println("UPSTREAM_NOTIFICATION cases=2 ok");
    }

    private static void checkUnit(I18NBundle bundle, String label, int health, int maxHealth,
            int ammo, int ammoCapacity, String... expected) {
        var actual = new ArrayList<String>(
            UpstreamStatusRows.unitRows(bundle, health, maxHealth, ammo, ammoCapacity));
        List<String> want = Arrays.asList(expected);
        if (!actual.equals(want)) {
            throw new IllegalStateException(label + ": expected " + want + " got " + actual);
        }
    }

    private static void check(I18NBundle bundle, String label, boolean waves, boolean campaign,
            boolean attackMode, boolean waveTimer, boolean waitingWave, int wave, int winWave,
            int enemies, int enemyCores, int seconds, String... expected) {
        var actual = new ArrayList<String>(
            UpstreamStatusRows.texts(bundle, waves, campaign, attackMode, waveTimer,
                waitingWave, wave, winWave, enemies, enemyCores, seconds));
        List<String> want = Arrays.asList(expected);
        if (!actual.equals(want)) {
            throw new IllegalStateException(label + ": expected " + want + " got " + actual);
        }
    }
}
