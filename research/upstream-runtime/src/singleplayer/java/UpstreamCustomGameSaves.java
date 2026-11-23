import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.ArcRuntimeException;
import java.util.ArrayList;
import java.util.List;
import mindustry.io.SaveIO;

/** Discovers offline saves with the pinned v160.8 SaveIO metadata reader. */
final class UpstreamCustomGameSaves {
    record Slot(Fi file, String title, long timestamp) {}

    private UpstreamCustomGameSaves() {}

    static List<Slot> discover(Fi directory, Fi quickSave) {
        var saves = new Seq<Slot>();
        if (directory.isDirectory()) {
            directory.walk(file -> {
                if (file.extension().equalsIgnoreCase("msav") && !file.name().contains("backup")) {
                    var slot = read(file, file.nameWithoutExtension());
                    if (slot != null) saves.add(slot);
                }
            });
        }
        // Match the stock LoadDialog's Arc sort and newest-first order.
        saves.sort((left, right) -> {
            int latestFirst = Long.compare(right.timestamp(), left.timestamp());
            return latestFirst == 0 ? left.file().path().compareTo(right.file().path()) : latestFirst;
        });
        var slots = new ArrayList<Slot>(saves.size + 1);
        var quick = read(quickSave, Core.bundle.get("loadgame"));
        if (quick != null) slots.add(quick);
        for (Slot save : saves) slots.add(save);
        return slots;
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
