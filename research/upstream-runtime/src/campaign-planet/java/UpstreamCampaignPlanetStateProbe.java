import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.content.SectorPresets;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.type.Sector;

/** Reloads the upstream planet last-sector routing key on ARM11. */
public final class UpstreamCampaignPlanetStateProbe {
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
                : temporary + "/m3ds-campaign-planet");
        Fi dir = new Fi(base);
        dir.mkdirs();
        Fi settingsFile = dir.child("settings.bin");
        Fi backupFile = dir.child("settings_backup.bin");
        Fi backupsDir = dir.child("settings_backups");
        byte[] oldSettings = settingsFile.exists() ? settingsFile.readBytes() : null;
        byte[] oldBackup = backupFile.exists() ? backupFile.readBytes() : null;
        boolean backupsExisted = backupsDir.exists();
        int sectors = -1;
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
            sectors = Planets.serpulo.sectors.size;

            Sector target = SectorPresets.frozenForest.sector;
            if(target == null || target.id != 86 || target.preset != SectorPresets.frozenForest){
                throw new IllegalStateException("Frozen forest preset did not initialize");
            }
            if(Planets.serpulo.startSector == target.id){
                throw new IllegalStateException("Last-sector test must differ from the start sector");
            }

            Planets.serpulo.setLastSector(target);
            if(blank.getInt("serpulo-last-sector", -1) != target.id){
                throw new IllegalStateException("Last-sector key was not written");
            }
            blank.saveWithoutBackup();

            if(!settingsFile.exists() || settingsFile.length() <= 0){
                throw new IllegalStateException("settings.bin was not written");
            }

            ProbeSettings reloaded = new ProbeSettings();
            reloaded.setDataDirectory(dir);
            reloaded.setAutosave(false);
            reloaded.load();
            Core.settings = reloaded;

            Sector last = Planets.serpulo.getLastSector();
            if(last != target || last.id != 86 || last.preset != SectorPresets.frozenForest){
                throw new IllegalStateException("Last sector did not reload");
            }
            if(reloaded.getInt("serpulo-last-sector", -1) != 86){
                throw new IllegalStateException("Persisted last-sector key did not reload");
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
            "UPSTREAM_CAMPAIGN_PLANET path=" + (live ? "live" : "tmp")
            + " planet=serpulo sector=86 preset=frozenForest lastSector=86"
            + " reload=ok sectors=" + sectors);
    }
}
