import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Arrays;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.game.Gamemode;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;

/** Verify stock slot allocation for newly started and manually saved Custom Games. */
public final class UpstreamStockCustomSaveCreationReference {
    public static void main(String[] args) throws Exception {
        var root = new Fi(args[2]);
        var settings = UpstreamSinglePlayerSession.loadSettings(root);
        Core.bundle = I18NBundle.createBundle(new Fi(args[3]), Locale.ENGLISH);
        var session = UpstreamSinglePlayerSession.create(root, "en", null, settings);
        try {
            var glacier = MapIO.createMap(new Fi(args[0]), false);
            var caldera = MapIO.createMap(new Fi(args[1]), false);
            var first = session.startCustomGameWithSave(glacier, Gamemode.sandbox);
            if (first.mode() != Gamemode.sandbox || !first.save().name().equals("0.msav")) {
                throw new AssertionError("New stock Glacier save slot");
            }
            session.core().items.set(Items.copper, 41);
            session.save(first.save());
            var preserved = first.save().readBytes();
            var backup = SaveIO.backupFileFor(first.save()).readBytes();

            var second = session.startCustomGameWithSave(caldera, Gamemode.survival);
            if (second.mode() != Gamemode.survival || !second.save().name().equals("1.msav")) {
                throw new AssertionError("New stock Caldera save slot");
            }
            Core.settings.put("savecreate", false);
            var disabled = session.startCustomGameWithSave(glacier, Gamemode.sandbox);
            if (disabled.save() != null || Vars.saveDirectory.child("2.msav").exists()) {
                throw new AssertionError("Disabled automatic save creation");
            }
            var manual = session.createCustomGameSave();
            if (!manual.name().equals("2.msav") || !Arrays.equals(first.save().readBytes(), preserved)
                    || !Arrays.equals(SaveIO.backupFileFor(first.save()).readBytes(), backup)) {
                throw new AssertionError("Independent manual save and existing slot");
            }

            var reopened = UpstreamSinglePlayerSession.loadSettings(root);
            if (!reopened.getString("save-0-name", "").startsWith("Glacier ")
                    || !reopened.getString("save-1-name", "").startsWith("Ancient Caldera ")
                    || !reopened.getString("save-2-name", "").startsWith("Glacier ")) {
                throw new AssertionError("Stock display names survived reopening settings");
            }
            System.out.println("UPSTREAM_CUSTOM_SAVE_CREATION_REFERENCE numeric=0,1,2 automatic=2 manual=1 "
                + "preserved=ok stockNames=ok");
        } finally {
            session.shutdown();
        }
    }
}
