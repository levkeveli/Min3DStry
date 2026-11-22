import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import mindustry.Vars;
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
            String[] initial = UpstreamSinglePlayerApp.customGameCaptions(maps, 0, 0, Gamemode.sandbox);
            String[] resume = UpstreamSinglePlayerApp.customGameCaptions(maps, 1, 0, Gamemode.survival);
            String[] last = UpstreamSinglePlayerApp.customGameCaptions(maps, 0, maps.size() - 1, Gamemode.attack);
            if (!initial[0].equals("Custom Game / Sandbox") || !initial[1].equals("> Ground Zero")
                    || !resume[1].equals("> Load Game") || !last[1].equals("> Wasteland")) {
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
        } finally {
            session.shutdown();
        }
    }
}
