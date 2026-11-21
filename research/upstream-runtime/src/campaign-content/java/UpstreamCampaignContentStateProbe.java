import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;

/** Loads upstream content with campaign unlock keys present in settings.bin. */
public final class UpstreamCampaignContentStateProbe {
    /** Disables Arc's asynchronous settings backup, which has no lane on TeaVM C. */
    private static final class ProbeSettings extends Settings {
        void disableBackup(){
            executor.shutdownNow();
        }

        void saveWithoutBackup(){
            disableBackup();
            try{
                saveValues();
            }catch(RuntimeException expected){
                // The file is written before Arc schedules its background backup copy.
            }
        }
    }

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
        int blocks = -1;
        try {
            settingsFile.delete();
            backupFile.delete();

            ProbeSettings blank = new ProbeSettings();
            blank.setDataDirectory(dir);
            blank.setAutosave(false);
            blank.load();
            Core.settings = blank;

            Vars.headless = true;
            Vars.state = new GameState();
            Vars.content = new ContentLoader();
            Vars.content.createBaseContent();
            Vars.content.init();
            blocks = Vars.content.blocks().size;

            if(Blocks.conveyor.unlocked()){
                throw new IllegalStateException("Conveyor was unlocked before its key was persisted");
            }

            blank.put("conveyor-unlocked", true);
            blank.put("req-conveyor-copper", 50);
            blank.saveWithoutBackup();

            if(!settingsFile.exists() || settingsFile.length() <= 0){
                throw new IllegalStateException("settings.bin was not written");
            }

            // A fresh instance must recover the unlock state from settings.bin,
            // not from the in-memory map used to write it.
            ProbeSettings reloaded = new ProbeSettings();
            reloaded.setDataDirectory(dir);
            reloaded.setAutosave(false);
            reloaded.load();
            Core.settings = reloaded;

            if(!reloaded.getBool("conveyor-unlocked", false)){
                throw new IllegalStateException("Campaign unlock did not reload from disk");
            }
            Object research = reloaded.get("req-conveyor-copper", null);
            if(!(research instanceof Integer) || ((Integer)research) != 50){
                throw new IllegalStateException("Campaign research did not reload from disk");
            }

            // Rebuild content against the reloaded settings so UnlockableContent
            // reads the persisted key during construction.
            Vars.content = new ContentLoader();
            Vars.content.createBaseContent();
            Vars.content.init();
            if(!Blocks.conveyor.unlocked()){
                throw new IllegalStateException("Upstream content did not load the persisted unlock");
            }
        } finally {
            if(oldSettings == null){
                settingsFile.delete();
            }else{
                settingsFile.writeBytes(oldSettings);
            }
            if(oldBackup == null){
                backupFile.delete();
            }else{
                backupFile.writeBytes(oldBackup);
            }
            if(!backupsExisted && backupsDir.exists()){
                backupsDir.deleteDirectory();
            }
        }

        System.out.println(
            "UPSTREAM_CAMPAIGN_CONTENT path=" + (live ? "live" : "tmp")
            + " lockedBefore=ok conveyorUnlocked=true key=conveyor-unlocked research=50"
            + " reload=ok blocks=" + blocks);
    }
}
