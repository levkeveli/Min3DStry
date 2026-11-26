import arc.files.Fi;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.io.SaveOptions;

import java.io.IOException;

/** Keeps a complete stock campaign save available while writing its successor. */
final class UpstreamCampaignSave {
    private UpstreamCampaignSave() {
    }

    static Fi readableFile(Fi slot) {
        return slot.exists() ? slot : SaveIO.backupFileFor(slot);
    }

    static SaveMeta write(Fi slot, SaveOptions options, boolean preserveBackup) throws IOException {
        var staged = slot.sibling(slot.name() + ".pending");
        var backup = SaveIO.backupFileFor(slot);
        try {
            SaveIO.write(staged, options);
            SaveMeta meta = readMeta(staged);
            boolean movedPrimary = false;
            if (slot.exists()) {
                if (!preserveBackup && validPrimary(slot)) {
                    removeIfPresent(backup);
                    movedPrimary = slot.file().renameTo(backup.file());
                    if (!movedPrimary) throw new IOException("Could not back up campaign slot " + slot.path());
                } else {
                    // A readable header does not make a primary that failed world loading safe to rotate.
                    removeIfPresent(slot);
                }
            }
            if (!staged.file().renameTo(slot.file())) {
                var failure = new IOException("Could not install campaign slot " + slot.path());
                if (movedPrimary && !backup.file().renameTo(slot.file())) {
                    failure.addSuppressed(new IOException("Previous save remains in " + backup.path()));
                }
                throw failure;
            }
            return meta;
        } finally {
            if (staged.exists()) staged.delete();
        }
    }

    private static SaveMeta readMeta(Fi file) throws IOException {
        try (var input = SaveIO.getStream(file)) {
            return SaveIO.getMeta(input);
        } catch (RuntimeException failure) {
            throw new IOException("Could not validate campaign save " + file.path(), failure);
        }
    }

    private static boolean validPrimary(Fi slot) {
        try {
            readMeta(slot);
            return true;
        } catch (IOException invalid) {
            return false;
        }
    }

    private static void removeIfPresent(Fi file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Could not remove " + file.path());
    }
}
