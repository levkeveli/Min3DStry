import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.I18NBundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.game.Rules;
import mindustry.game.SpawnGroup;
import mindustry.mod.Mods;
import mindustry.net.Net;

/** Host coverage for every v160.8 HudFragment status-row branch. */
public final class UpstreamStatusRowsProbe {
    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "/3ds/min3dstry/bundles/bundle";
        I18NBundle bundle = I18NBundle.createBundle(new Fi(path), Locale.ROOT);
        Core.bundle = bundle;
        Vars.headless = true;
        Core.settings = new Settings();
        Core.settings.setAutosave(false);
        Vars.state = new GameState();
        Vars.mods = new Mods();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Vars.net = new Net(null);
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
        Rules rules = new Rules();
        rules.winWave = 20;
        SpawnGroup boss = new SpawnGroup(UnitTypes.dagger);
        boss.effect = StatusEffects.boss;
        boss.begin = 8;
        boss.end = 9;
        boss.unitAmount = 1;
        rules.spawns.add(boss);
        if (!"Guardian approaching in [accent]1[] wave.".equals(
                UpstreamGuardianWarning.find(rules, 9))) {
            throw new IllegalStateException("guardian one-wave warning mismatch");
        }
        SpawnGroup later = new SpawnGroup(UnitTypes.dagger);
        later.effect = StatusEffects.boss;
        later.begin = 11;
        later.end = 12;
        later.unitAmount = 1;
        Rules laterRules = new Rules();
        laterRules.winWave = 20;
        laterRules.spawns.add(later);
        if (!"Guardian approaching in [accent]5[] waves.".equals(
                UpstreamGuardianWarning.find(laterRules, 8))) {
            throw new IllegalStateException("guardian five-wave warning mismatch");
        }
        var coreItems = UpstreamCoreItems.collect(
            Seq.with(Items.copper, Items.lead, Items.silicon).list(),
            item -> item == Items.copper ? 16 : item == Items.lead ? 7 : 0);
        if (coreItems.size() != 2
                || coreItems.get(0).item() != Items.copper || coreItems.get(0).amount() != 16
                || coreItems.get(1).item() != Items.lead || coreItems.get(1).amount() != 7) {
            throw new IllegalStateException("Core item row collection mismatch");
        }
        System.out.println("UPSTREAM_CORE_ITEMS rows=2 ok");
        var tracker = new UpstreamCoreItems.Tracker();
        var tracked = tracker.collect(Seq.with(Items.copper, Items.lead, Items.silicon).list(),
            item -> item == Items.copper ? 16 : 0);
        if (tracked.size() != 1 || tracked.get(0).item() != Items.copper) {
            throw new IllegalStateException("Used-item initial row mismatch");
        }
        tracked = tracker.collect(Seq.with(Items.copper, Items.lead, Items.silicon).list(),
            item -> item == Items.lead ? 7 : 0);
        if (tracked.size() != 2 || tracked.get(0).item() != Items.copper
                || tracked.get(0).amount() != 0 || tracked.get(1).item() != Items.lead) {
            throw new IllegalStateException("Used-item depletion mismatch");
        }
        tracker.reset();
        if (!tracker.collect(Seq.with(Items.copper).list(), item -> 0).isEmpty()) {
            throw new IllegalStateException("Used-item reset mismatch");
        }
        System.out.println("UPSTREAM_CORE_USED_ITEMS cases=3 ok");

        var pause = new UpstreamPauseMenu();
        pause.open();
        if (pause.selected() != 0 || pause.confirmingQuit()) {
            throw new IllegalStateException("Pause menu initial state mismatch");
        }
        pause.move(-1);
        if (pause.selected() != 2) throw new IllegalStateException("Pause menu wrap mismatch");
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.RESUME) {
            throw new IllegalStateException("Pause menu resume action mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.SAVE) {
            throw new IllegalStateException("Pause menu save action mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.NONE || !pause.confirmingQuit()) {
            throw new IllegalStateException("Pause menu quit confirmation missing");
        }
        if (pause.cancel() != UpstreamPauseMenu.Action.NONE || pause.confirmingQuit()) {
            throw new IllegalStateException("Pause menu quit cancel mismatch");
        }
        if (pause.activate() != UpstreamPauseMenu.Action.NONE
                || pause.activate() != UpstreamPauseMenu.Action.QUIT) {
            throw new IllegalStateException("Pause menu quit action mismatch");
        }
        pause.open(4);
        if (pause.options() != 4) throw new IllegalStateException("Pause menu research options mismatch");
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.RESEARCH) {
            throw new IllegalStateException("Pause menu research action mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.SAVE) {
            throw new IllegalStateException("Pause menu save-with-research action mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.NONE
                || pause.activate() != UpstreamPauseMenu.Action.QUIT) {
            throw new IllegalStateException("Pause menu quit-with-research action mismatch");
        }
        pause.open(5);
        if (pause.options() != 5) throw new IllegalStateException("Pause menu load options mismatch");
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.RESEARCH) {
            throw new IllegalStateException("Pause menu five-row research action mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.LOAD) {
            throw new IllegalStateException("Pause menu load action mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.SAVE) {
            throw new IllegalStateException("Pause menu load-save action mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.NONE
                || pause.activate() != UpstreamPauseMenu.Action.QUIT) {
            throw new IllegalStateException("Pause menu load-quit action mismatch");
        }
        pause.open(6);
        if (pause.options() != 6) throw new IllegalStateException("Pause menu settings options mismatch");
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.RESEARCH) {
            throw new IllegalStateException("Pause menu six-row research mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.LOAD) {
            throw new IllegalStateException("Pause menu six-row load mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.SAVE) {
            throw new IllegalStateException("Pause menu six-row save mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.SETTINGS) {
            throw new IllegalStateException("Pause menu settings action mismatch");
        }
        pause.move(1);
        if (pause.activate() != UpstreamPauseMenu.Action.NONE
                || pause.activate() != UpstreamPauseMenu.Action.QUIT) {
            throw new IllegalStateException("Pause menu six-row quit mismatch");
        }
        var settings = new UpstreamSettingsMenu();
        settings.open(true);
        if (!settings.coreItems() || settings.selected() != 0) {
            throw new IllegalStateException("Settings menu initial state mismatch");
        }
        settings.move(1);
        if (!settings.backSelected()) throw new IllegalStateException("Settings back selection mismatch");
        if (settings.toggleCoreItems() || settings.coreItems()) {
            throw new IllegalStateException("Settings core-items toggle mismatch");
        }
        settings.move(-1);
        if (settings.selected() != 0) throw new IllegalStateException("Settings wrap mismatch");
        System.out.println("UPSTREAM_PAUSE_MENU cases=18 ok");
        System.out.println("UPSTREAM_STATUS_ROWS cases=10 ok");
        System.out.println("UPSTREAM_GUARDIAN_WARNING cases=2 ok");
        System.out.println("UPSTREAM_UNIT_ROWS cases=3 ok");
        String coreAttack = Core.bundle.get("coreattack");
        if (!"< Core is under attack! >".equals(coreAttack)) {
            throw new IllegalStateException("coreattack bundle text mismatch: " + coreAttack);
        }
        UpstreamNotification.show(coreAttack, 4f);
        if (!coreAttack.equals(UpstreamNotification.current())) {
            throw new IllegalStateException("coreattack notification was not retained");
        }
        System.out.println("UPSTREAM_CORE_ATTACK text=\"" + coreAttack + "\" ok");
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
