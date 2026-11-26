import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.SectorPresets;
import mindustry.game.EventType.SaveWriteEvent;
import mindustry.io.SaveIO;
import mindustry.io.SaveOptions;

/** Verify the offline sector catalog with saves written by the unmodified v160.8 server. */
public final class UpstreamCampaignResumeReference {
    public static void main(String[] args) throws Exception {
        Fi fixtures = new Fi(args[0]), root = new Fi(args[1]);
        if (root.exists()) throw new IllegalArgumentException("Use a new scratch campaign directory");
        root.mkdirs();
        Core.bundle = I18NBundle.createBundle(fixtures.child("bundle"), Locale.ENGLISH);
        var session = UpstreamSinglePlayerSession.create(root, "en", fixtures.child("planets"));
        try {
            var zero = SectorPresets.groundZero.sector;
            var forest = SectorPresets.frozenForest.sector;
            if (zero.id != 170 || forest.id != 64 || !session.sectorSaves().isEmpty()) {
                throw new AssertionError("Stock Serpulo preset assignments or empty catalog differ");
            }

            String zeroName = "sector-serpulo-170.msav", forestName = "sector-serpulo-64.msav";
            Fi canonical = root.child("saves").child(zeroName);
            Fi loose = root.child(zeroName);
            Fi nested = root.child("saves/ignored").child(forestName);
            copy(fixtures.child(zeroName), canonical);
            copy(fixtures.child(zeroName), loose);
            copy(fixtures.child(forestName), nested);
            var single = session.sectorSaves();
            if (single.size() != 1 || !single.get(0).file.equals(canonical) || single.get(0).sector != zero) {
                throw new AssertionError("The single stock slot should supersede a loose duplicate and ignore nested saves");
            }

            Fi second = root.child(forestName);
            copy(fixtures.child(forestName), second);
            root.child("sector-serpulo-86.msav").writeString("broken header");
            // Keep a valid magic and save version but omit the metadata region.
            try (var input = SaveIO.getStream(canonical);
                    var malformed = new DeflaterOutputStream(root.child("sector-serpulo-15.msav").write())) {
                malformed.write(input.readNBytes(8));
            }
            var slots = session.sectorSaves();
            if (slots.size() != 2 || slots.get(0).timestamp < slots.get(1).timestamp) {
                throw new AssertionError("Expected two stock sector slots in last-saved order");
            }
            int selected = slots.get(0).sector == zero ? 0 : 1;
            var captions = UpstreamSinglePlayerApp.campaignCaptions(slots, selected);
            if (!captions[0].equals("Campaign") || !captions[selected + 1].equals("> Serpulo / Ground Zero")
                    || !captions[2 - selected].equals("  Serpulo / Frozen Forest")) {
                throw new AssertionError("Campaign menu labels differ from stock sector names: " + Arrays.toString(captions));
            }

            var slot = slots.get(selected);
            long originalTimestamp = SaveIO.getMeta(canonical).timestamp;
            if (!session.start(null, slot.file, "campaign", slot.sector) || Vars.state.rules.sector != zero
                    || Vars.world.width() != 256 || Vars.world.height() != 256) {
                throw new AssertionError("The original Ground Zero sector did not resume");
            }
            session.stepSimulation(0f, 0f);
            session.save(slot.file);
            var reloaded = SaveIO.getMeta(canonical);
            if (reloaded.rules.sector != zero || reloaded.timestamp < originalTimestamp
                    || !Arrays.equals(SaveIO.backupFileFor(canonical).readBytes(), loose.readBytes())
                    || !Arrays.equals(second.readBytes(), nested.readBytes())) {
                throw new AssertionError("Saving the selected campaign modified the wrong slot or lost stock metadata");
            }

            Fi backup = SaveIO.backupFileFor(canonical);
            var priorPrimary = canonical.readBytes();
            var priorBackup = backup.readBytes();
            Cons<SaveWriteEvent> interrupt = event -> { throw new IllegalStateException("Test interruption"); };
            Events.on(SaveWriteEvent.class, interrupt);
            try {
                UpstreamCampaignSave.write(canonical, new SaveOptions(), false);
                throw new AssertionError("Expected the staged write to stop");
            } catch (RuntimeException expected) {
                if (!Arrays.equals(priorPrimary, canonical.readBytes())
                    || !Arrays.equals(priorBackup, backup.readBytes())
                    || canonical.sibling(canonical.name() + ".pending").exists()) {
                    throw new AssertionError("A failed staged write changed a live campaign save", expected);
                }
            } finally {
                Events.remove(SaveWriteEvent.class, interrupt);
            }

            Fi forestBackup = SaveIO.backupFileFor(second);
            if (!second.file().renameTo(forestBackup.file())) throw new AssertionError("Could not stage backup-only slot");
            var recovery = session.sectorSaves().stream().filter(entry -> entry.sector == forest)
                .findFirst().orElseThrow();
            if (!recovery.file.equals(second) || !session.start(null, recovery.file, "campaign", forest)
                    || Vars.state.rules.sector != forest) {
                throw new AssertionError("The stock backup-only campaign slot did not resume");
            }
            session.save(recovery.file);
            if (SaveIO.getMeta(second).rules.sector != forest
                    || !Arrays.equals(forestBackup.readBytes(), nested.readBytes())) {
                throw new AssertionError("Saving a recovered slot replaced its last good backup");
            }
            try (var input = SaveIO.getStream(forestBackup);
                    var truncated = new DataOutputStream(new DeflaterOutputStream(second.write()))) {
                truncated.write(input.readNBytes(8));
                int length = input.readInt();
                truncated.writeInt(length);
                truncated.write(input.readNBytes(length));
            }
            if (SaveIO.getMeta(second).rules.sector != forest
                    || !session.start(null, recovery.file, "campaign", forest) || Vars.world.width() != 200) {
                throw new AssertionError("SaveIO did not use the complete backup after a truncated primary");
            }
            session.save(recovery.file);
            if (!Arrays.equals(forestBackup.readBytes(), nested.readBytes())) {
                throw new AssertionError("A metadata-only primary displaced the known-good backup");
            }
            if (!session.start(null, recovery.file, "campaign", forest) || Vars.world.width() != 200) {
                throw new AssertionError("The recovered campaign primary did not reopen");
            }
            System.out.println("UPSTREAM_CAMPAIGN_RESUME_REFERENCE slots=2 groundZero=170 frozenForest=64 backup=ok"
                + " interrupted-write=preserved backup-only=reopened truncated-primary=preserved");
        } finally {
            session.shutdown();
        }
    }

    private static void copy(Fi source, Fi destination) {
        destination.parent().mkdirs();
        source.copyTo(destination);
    }
}
