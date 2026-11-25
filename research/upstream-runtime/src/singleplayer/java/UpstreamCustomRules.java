import arc.files.Fi;
import java.io.IOException;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.io.JsonIO;
import mindustry.maps.Map;

/** Apply a stock CustomRulesDialog clipboard export to a new offline game. */
final class UpstreamCustomRules {
    private UpstreamCustomRules() {}

    static Rules load(Fi directory, Map map, Gamemode mode, Rules defaults) throws IOException {
        var file = directory.child(map.file.nameWithoutExtension() + "." + mode.name() + ".json");
        if (!file.exists()) return defaults;
        Rules imported;
        try {
            String json = file.readString("UTF-8").trim();
            if (!json.startsWith("{")) throw new IllegalArgumentException("Expected stock rules JSON");
            imported = JsonIO.read(Rules.class, json);
            if (imported == null) throw new IllegalArgumentException("Empty rules");
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid custom game rules in " + file.name(), invalid);
        }
        // Match CustomRulesDialog's stock clipboard import. Control.playMap
        // retains the loaded map's content fields after the world is initialized.
        imported.spawns = defaults.spawns;
        imported.objectives = defaults.objectives;
        System.out.println("UPSTREAM_CUSTOM_GAME_RULES source=" + file.name());
        return imported;
    }
}
