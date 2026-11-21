import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;

/** Loads upstream content with campaign unlock keys already present. */
public final class UpstreamCampaignContentStateProbe {
    public static void main(String[] args) {
        String temporary = System.getProperty("java.io.tmpdir");
        boolean live = temporary != null && temporary.startsWith("/3ds/min3dstry");
        String base = args.length > 0
            ? args[0]
            : (live
                ? "/3ds/min3dstry"
                : temporary + "/m3ds-campaign-content");
        Fi dir = new Fi(base);
        dir.mkdirs();
        Fi settingsFile = dir.child("settings.bin");
        Fi backupFile = dir.child("settings_backup.bin");
        Fi backupsDir = dir.child("settings_backups");
        byte[] oldSettings = settingsFile.exists() ? settingsFile.readBytes() : null;
        byte[] oldBackup = backupFile.exists() ? backupFile.readBytes() : null;
        boolean backupsExisted = backupsDir.exists();
        int blocks;
        try {
            settingsFile.delete();
            backupFile.delete();

            Settings settings = new Settings();
            settings.setDataDirectory(dir);
            settings.setAutosave(false);
            settings.load();
            settings.put("conveyor-unlocked", true);
            settings.put("req-conveyor-copper", 50);
            settings.forceSave();
            Core.settings = settings;

            Vars.headless = true;
            Vars.state = new GameState();
            Vars.content = new ContentLoader();
            Vars.content.createBaseContent();
            Vars.content.init();

            if (!Blocks.conveyor.unlocked()) {
                throw new IllegalStateException("Upstream conveyor unlock was not loaded");
            }
            if (!"conveyor-unlocked".equals(Blocks.conveyor.name + "-unlocked")) {
                throw new IllegalStateException("Unexpected conveyor unlock key");
            }
            if (!"req-conveyor-copper".equals(
                    "req-" + Blocks.conveyor.name + "-" + Items.copper.name)) {
                throw new IllegalStateException("Unexpected conveyor research key");
            }
            Object research = settings.get("req-conveyor-copper", null);
            if (!(research instanceof Integer) || ((Integer)research) != 50) {
                throw new IllegalStateException("Campaign research key did not load");
            }
            blocks = Vars.content.blocks().size;
        } finally {
            if (oldSettings == null) {
                settingsFile.delete();
            } else {
                settingsFile.writeBytes(oldSettings);
            }
            if (oldBackup == null) {
                backupFile.delete();
            } else {
                backupFile.writeBytes(oldBackup);
            }
            if (!backupsExisted && backupsDir.exists()) {
                backupsDir.deleteDirectory();
            }
        }

        System.out.println(
            "UPSTREAM_CAMPAIGN_CONTENT path=" + (live ? "live" : "tmp")
            + " conveyorUnlocked=true key=conveyor-unlocked research=50"
            + " blocks=" + blocks);
    }
}
