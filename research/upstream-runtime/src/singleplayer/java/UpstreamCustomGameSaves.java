import arc.Core;
import arc.files.Fi;
import arc.util.ArcRuntimeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mindustry.io.SaveIO;

/** Discovers offline saves with the pinned v160.8 SaveIO metadata reader. */
final class UpstreamCustomGameSaves {
    record Slot(Fi file, String title, long timestamp) {}

    private UpstreamCustomGameSaves() {}

    static List<Slot> discover(Fi directory, Fi quickSave) {
        var saves = new ArrayList<Slot>();
        if (directory.isDirectory()) {
            directory.walk(file -> {
                if (file.extension().equalsIgnoreCase("msav") && !file.name().contains("backup")) {
                    var slot = read(file, file.nameWithoutExtension());
                    if (slot != null) saves.add(slot);
                }
            });
        }
        saves.sort(Comparator.comparingLong(Slot::timestamp).reversed().thenComparing(slot -> slot.file.path()));
        var quick = read(quickSave, Core.bundle.get("loadgame"));
        if (quick != null) saves.add(0, quick);
        return saves;
    }

    private static Slot read(Fi file, String fallbackName) {
        if (!file.exists() || !SaveIO.isSaveValid(file)) return null;
        try {
            var meta = SaveIO.getMeta(file);
            if (meta.rules.sector != null || meta.isMap()) return null;
            var name = Core.settings.getString("save-" + file.nameWithoutExtension() + "-name", fallbackName);
            return new Slot(file, name, meta.timestamp);
        } catch (ArcRuntimeException invalidMetadata) {
            return null;
        }
    }
}
