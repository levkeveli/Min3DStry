import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.io.IOException;
import java.util.Locale;
import mindustry.Vars;
import mindustry.game.Gamemode;
import mindustry.io.MapIO;

/** Play a real offline map with a rules export produced by stock v160.8 Java. */
public final class UpstreamStockCustomRulesReference {
    public static void main(String[] args) throws Exception {
        var rules = new Fi(args[1]);
        var save = new Fi(args[2]);
        Core.bundle = I18NBundle.createBundle(new Fi(args[3]), Locale.ENGLISH);
        var session = UpstreamSinglePlayerSession.create(save.parent(), "en");
        try {
            var map = MapIO.createMap(new Fi(args[0]), false);
            var defaults = map.applyRules(Gamemode.sandbox);
            if (session.startCustomGame(map, Gamemode.sandbox, rules.child("absent")) != Gamemode.sandbox
                    || Vars.state.rules.waves != defaults.waves || Vars.state.rules.waveTimer != defaults.waveTimer
                    || Vars.state.rules.infiniteResources != defaults.infiniteResources) {
                throw new AssertionError("Stock sandbox defaults");
            }
            int spawns = Vars.state.rules.spawns.size;
            int weather = Vars.state.rules.weather.size;
            if (session.startCustomGame(map, Gamemode.sandbox, rules) != Gamemode.sandbox
                    || !Vars.state.rules.waves || !Vars.state.rules.waveTimer || Vars.state.rules.infiniteResources
                    || Vars.state.rules.waveSpacing != 420f || Vars.state.rules.initialWaveSpacing != 8f
                    || Vars.state.rules.buildCostMultiplier != 0.5f || Vars.state.rules.unitCap != 30
                    || Vars.state.rules.spawns.size != spawns || Vars.state.rules.weather.size != weather) {
                throw new AssertionError("Original rules or map content were not applied");
            }
            if (Vars.state.wave != 1 || Vars.state.wavetime != 8f) {
                throw new AssertionError("Imported initial wave timer was not used");
            }
            for (int i = 0; i < 12; i++) session.stepSimulation(0f, 0f);
            if (Vars.state.wave != 2 || Vars.state.wavetime < 410f || Vars.state.wavetime > 420f) {
                throw new AssertionError("The original wave scheduler did not use the imported timing");
            }
            session.save(save);

            var before = Vars.state.rules;
            var malformed = rules.child("malformed-case");
            malformed.child(map.file.nameWithoutExtension() + ".sandbox.json").writeString("not rules", false);
            try {
                session.startCustomGame(map, Gamemode.sandbox, malformed);
                throw new AssertionError("Malformed rules loaded");
            } catch (IOException expected) {
                if (Vars.state.rules != before || Vars.state.map != map || Vars.state.wave != 2) {
                    throw new AssertionError("Malformed rules changed an active session");
                }
            }
            System.out.println("UPSTREAM_STOCK_CUSTOM_RULES_REFERENCE spawns=" + spawns
                + " weather=" + weather + " wave=" + Vars.state.wave + " malformed=kept-original");
        } finally {
            session.shutdown();
        }
    }
}
