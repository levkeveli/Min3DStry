import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.game.SectorInfo;

/** Round-trips the upstream SectorInfo settings key with TeaVM field reflection. */
public final class UpstreamCampaignSectorInfoProbe {
    /** Disables Arc's asynchronous settings backup, which has no lane on TeaVM C. */
    private static final class ProbeSettings extends Settings {
        void saveWithoutBackup(){
            if(executor != null) executor.shutdownNow();
            try{
                saveValues();
            }catch(RuntimeException expected){
                // The file is written before Arc schedules its background backup copy.
            }
        }
    }

    public static void main(String[] args){
        String temporary = System.getProperty("java.io.tmpdir");
        boolean live = temporary == null || temporary.startsWith("/3ds/min3dstry");
        String base = args.length > 0
            ? args[0]
            : (live
                ? "/3ds/min3dstry"
                : temporary + "/m3ds-campaign-sector-info");
        Fi dir = new Fi(base);
        dir.mkdirs();
        Fi settingsFile = dir.child("settings.bin");
        Fi backupFile = dir.child("settings_backup.bin");
        Fi backupsDir = dir.child("settings_backups");
        byte[] oldSettings = settingsFile.exists() ? settingsFile.readBytes() : null;
        byte[] oldBackup = backupFile.exists() ? backupFile.readBytes() : null;
        boolean backupsExisted = backupsDir.exists();
        int blocks = -1;
        try{
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

            String key = "serpulo-s-170-info";
            SectorInfo info = new SectorInfo();
            info.wasCaptured = true;
            info.wave = 7;
            info.storageCapacity = 4000;
            info.name = "M3DS Outpost";
            info.items.add(Items.copper, 250);
            info.items.add(Items.lead, 75);
            // Keep the struct payload scalar; a Block here would need deeper content JSON.
            info.bestCoreType = null;
            info.resources.clear();
            blank.putJson(key, info);
            blank.saveWithoutBackup();

            if(!settingsFile.exists() || settingsFile.length() <= 0){
                throw new IllegalStateException("settings.bin was not written");
            }

            ProbeSettings reloaded = new ProbeSettings();
            reloaded.setDataDirectory(dir);
            reloaded.setAutosave(false);
            reloaded.load();
            Core.settings = reloaded;

            SectorInfo loaded = reloaded.getJson(key, SectorInfo.class, SectorInfo::new);
            if(!loaded.wasCaptured || loaded.wave != 7 || loaded.storageCapacity != 4000){
                throw new IllegalStateException("SectorInfo scalars did not reload");
            }
            if(!("M3DS Outpost".equals(loaded.name))){
                throw new IllegalStateException("SectorInfo name did not reload");
            }
            if(loaded.items.get(Items.copper) != 250 || loaded.items.get(Items.lead) != 75){
                throw new IllegalStateException("SectorInfo items did not reload");
            }
        }finally{
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
            "UPSTREAM_CAMPAIGN_SECTOR_INFO path=" + (live ? "live" : "tmp")
            + " key=serpulo-s-170-info captured=ok wave=7 items=250:75 reload=ok blocks=" + blocks);
    }
}
