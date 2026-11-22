import arc.files.Fi;
import arc.util.Strings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mindustry.io.MapIO;
import mindustry.maps.Map;

/** The stock Custom Game metadata read directly from the packaged and SD .msav files. */
final class UpstreamCustomGameMaps {
    private UpstreamCustomGameMaps() {}

    static List<Map> discover(Fi bundled, Fi user) {
        var maps = new ArrayList<Map>();
        readMaps(user, true, maps);
        readMaps(bundled, false, maps);
        maps.sort(Comparator.comparing((Map map) -> !map.custom)
            .thenComparing(map -> Strings.stripColors(map.name()))
            .thenComparing(map -> map.file.name()));
        return maps;
    }

    private static void readMaps(Fi directory, boolean custom, List<Map> maps) {
        if (!directory.isDirectory()) return;
        for (Fi file : directory.list()) {
            if (file.isDirectory() || !file.extension().equalsIgnoreCase("msav")) continue;
            try {
                Map map = MapIO.createMap(file, custom);
                if (map.width > 0 && map.height > 0 && map.name() != null) maps.add(map);
            } catch (IOException | RuntimeException invalidMap) {
                System.out.println("UPSTREAM_CUSTOM_GAME_REJECTED file=" + file.name());
            }
        }
    }
}
