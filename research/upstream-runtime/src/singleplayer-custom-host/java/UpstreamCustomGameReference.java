import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import arc.util.Strings;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.game.Gamemode;
import mindustry.maps.Maps;

/** JVM verification of the pinned map catalog and offline launch using upstream rules. */
public final class UpstreamCustomGameReference {
    public static void main(String[] args) throws Exception {
        Fi bundled = new Fi(args[0]), user = new Fi(args[1]), save = new Fi(args[2]);
        Core.bundle = I18NBundle.createBundle(new Fi(args[3]), Locale.ENGLISH);
        var session = UpstreamSinglePlayerSession.create(save.parent(), "en");
        try {
            var maps = UpstreamCustomGameMaps.discover(bundled, user);
            var names = Maps.class.getDeclaredField("defaultMapNames");
            names.setAccessible(true);
            Set<String> expected = Set.of((String[])names.get(null));
            Set<String> actual = maps.stream().filter(map -> !map.custom)
                .map(map -> map.file.nameWithoutExtension()).collect(Collectors.toSet());
            if (!actual.equals(expected) || maps.stream().filter(map -> map.custom).count() != 1) {
                throw new AssertionError("Packaged or user Custom Game map catalog disagrees with stock");
            }
            var quick = new UpstreamCustomGameSaves.Slot(save, "Load Game", 0);
            String[] initial = UpstreamSinglePlayerApp.customGameCaptions(maps, List.of(), 0, Gamemode.sandbox);
            String[] resume = UpstreamSinglePlayerApp.customGameCaptions(maps, List.of(quick), 0, Gamemode.survival);
            String[] last = UpstreamSinglePlayerApp.customGameCaptions(maps, List.of(), maps.size() - 1, Gamemode.attack);
            var stockMaps = maps.stream().filter(map -> !map.custom).toList();
            if (!initial[0].equals("Custom Game / Sandbox") || !initial[1].equals("> Ground Zero")
                    || !resume[1].equals("> Load Game") || !last[1].equals("> Wasteland")
                    || !stockMaps.get(0).file.name().equals("caldera.msav")
                    || !stockMaps.get(1).file.name().equals("archipelago.msav")) {
                throw new AssertionError("Unexpected stock Custom Game selector captions");
            }
            for (var entry : maps) {
                var mode = session.startCustomGame(entry, Gamemode.survival);
                if (!mode.valid(entry) || Vars.state.map != entry || session.core() == null
                        || Vars.world.width() != entry.width || Vars.world.height() != entry.height) {
                    throw new AssertionError("Could not launch the original map " + entry.file.name());
                }
            }
            var map = maps.stream().filter(entry -> !entry.custom && entry.file.nameWithoutExtension().equals("glacier"))
                .findFirst().orElseThrow();
            var expectedRules = map.applyRules(Gamemode.sandbox);
            var mode = session.startCustomGame(map, Gamemode.sandbox);
            if (mode != Gamemode.sandbox || Vars.state.map != map || session.core() == null
                    || Vars.state.rules.infiniteResources != expectedRules.infiniteResources
                    || Vars.state.rules.waveTimer != expectedRules.waveTimer
                    || Vars.state.rules.waves != expectedRules.waves
                    || Vars.state.rules.planet != expectedRules.planet || Vars.state.rules.sector != null
                    || Vars.state.rules.editor) {
                throw new AssertionError("Offline Glacier did not apply upstream Sandbox rules");
            }
            session.stepSimulation(0f, 0f);
            session.save(save);
            if (!session.start(null, save, null) || !Vars.state.rules.infiniteResources || Vars.state.rules.waveTimer
                    || Vars.world.width() != map.width || Vars.world.height() != map.height) {
                throw new AssertionError("Custom Game did not survive the original save path");
            }
            System.out.println("UPSTREAM_CUSTOM_GAME_REFERENCE bundled=" + actual.size()
                + " user=1 map=" + map.name() + " mode=" + mode.name() + " save=ok");
            verifyNamedSaves(session, map, stockMaps.get(0), save);
            verifyOfflineResults(session, stockMaps.get(0), map, save);
        } finally {
            session.shutdown();
        }
    }

    private static void verifyOfflineResults(UpstreamSinglePlayerSession session, mindustry.maps.Map caldera,
                                             mindustry.maps.Map glacier, Fi save) throws Exception {
        byte[] existing = save.readBytes();
        if (session.startCustomGame(caldera, Gamemode.survival) != Gamemode.survival) {
            throw new AssertionError("Expected an original Survival match");
        }
        Vars.state.wave = 9;
        var waveTeam = Vars.state.rules.waveTeam;
        session.core().kill();
        session.stepSimulation(0f, 0f);
        String[] lost = UpstreamSinglePlayerApp.offlineResultCaptions(session.offlineWinner());
        if (!Vars.state.gameOver || Vars.state.won || session.offlineWinner() != waveTeam
                || Vars.state.stats.wavesLasted != 9 || !lost[0].equals(Core.bundle.get("gameover"))
                || !lost[1].equals(Core.bundle.get("stats.wave") + " 9")
                || !lost[lost.length - 1].equals("A " + Core.bundle.get("menu"))) {
            throw new AssertionError("Stock Survival defeat should expose the final wave and menu action");
        }

        if (session.startCustomGame(glacier, Gamemode.pvp) != Gamemode.pvp || session.offlineWinner() != null) {
            throw new AssertionError("An original new match should clear the previous result");
        }
        var winner = Vars.state.rules.defaultTeam;
        var opposing = Vars.state.teams.active.copy();
        for (var team : opposing) {
            if (team.team != winner) team.cores.copy().each(core -> core.kill());
        }
        session.stepSimulation(0f, 0f);
        String[] won = UpstreamSinglePlayerApp.offlineResultCaptions(session.offlineWinner());
        var expected = Strings.stripColors(Core.bundle.format("gameover.pvp", winner.coloredName()));
        if (!Vars.state.gameOver || !Vars.state.won || session.offlineWinner() != winner
                || !won[0].equals(expected) || !Arrays.equals(existing, save.readBytes())) {
            throw new AssertionError("Stock PvP victory should retain the winner and the existing save");
        }
        System.out.println("UPSTREAM_OFFLINE_RESULT_REFERENCE survival=loss wave=9 pvp=win reset=ok save=preserved");
    }

    private static void verifyNamedSaves(UpstreamSinglePlayerSession session, mindustry.maps.Map glacier,
                                         mindustry.maps.Map caldera, Fi quickSave) throws Exception {
        Fi directory = quickSave.parent().child("custom-save-slot-fixtures");
        Fi first = directory.child("0.msav"), second = directory.child("Caldera Test.msav");
        session.core().items.set(Items.copper, 17);
        session.save(first);
        Core.settings.put("save-0-name", "Glacier Test");
        session.startCustomGame(caldera, Gamemode.survival);
        session.core().items.set(Items.copper, 41);
        session.save(second);
        byte[] originalSecond = second.readBytes();
        first.copyTo(directory.child("0.msav-backup.msav"));
        caldera.file.copyTo(directory.child("map-export.msav"));
        directory.child("corrupt.msav").writeString("invalid save");

        var slots = UpstreamCustomGameSaves.discover(directory, quickSave);
        var chosen = slots.stream().filter(slot -> slot.file().equals(first)).findFirst().orElseThrow();
        if (slots.size() != 3 || !slots.get(0).file().equals(quickSave) || !chosen.title().equals("Glacier Test")
                || !slots.get(1).file().equals(second) || !slots.get(1).title().equals("Caldera Test")) {
            throw new AssertionError("Named saves, quick save, backups or maps were classified incorrectly");
        }
        session.start(null, chosen.file(), "customgame-save");
        if (Vars.world.width() != glacier.width || Vars.world.height() != glacier.height
                || !Vars.state.rules.infiniteResources || session.core().items.get(Items.copper) != 17) {
            throw new AssertionError("The selected Glacier save lost its rules or inventory");
        }
        session.core().items.set(Items.copper, 18);
        session.save(chosen.file());
        if (!Arrays.equals(second.readBytes(), originalSecond)) {
            throw new AssertionError("Saving Glacier changed the neighbouring Caldera slot");
        }
        session.start(null, second, "customgame-save");
        if (Vars.world.width() != caldera.width || Vars.world.height() != caldera.height
                || Vars.state.rules.infiniteResources || session.core().items.get(Items.copper) != 41) {
            throw new AssertionError("The neighbouring Caldera save was not independently playable");
        }
        System.out.println("UPSTREAM_CUSTOM_SAVES_REFERENCE named=2 quick=1 isolated=ok");
    }
}
