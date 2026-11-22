import arc.files.Fi;
import arc.struct.Seq;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mindustry.io.MapIO;
import mindustry.maps.Map;

/** The stock Custom Game metadata read directly from the packaged and SD .msav files. */
final class UpstreamCustomGameMaps {
    private UpstreamCustomGameMaps() {}

    static List<Map> discover(Fi bundled, Fi user) {
        var maps = new Seq<Map>();
        readMaps(user, true, maps);
        readMaps(bundled, false, maps);
        maps.sort((a, b) -> {
            int order = a.compareTo(b);
            return order == 0 ? a.file.name().compareTo(b.file.name()) : order;
        });
        var sorted = new ArrayList<Map>(maps.size);
        for (Map map : maps) sorted.add(map);
        return sorted;
    }

    private static void readMaps(Fi directory, boolean custom, Seq<Map> maps) {
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
