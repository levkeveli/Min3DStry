import arc.Core;
import arc.Files;
import arc.Settings;
import arc.assets.AssetManager;
import arc.files.Fi;
import arc.Files.FileType;
import mindustry.Vars;
import mindustry.content.SectorPresets;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.game.Saves;
import mindustry.type.Sector;

/** Verifies the upstream Saves sector-slot path builder on ARM11. */
public final class UpstreamCampaignSavesProbe {
    /** Minimal Files implementation for headless Arc assets. */
    private static final class ProbeFiles implements Files {
        @Override
        public Fi get(String path, FileType type){
            return new Fi(path);
        }

        @Override
        public String getExternalStoragePath(){
            return "/";
        }

        @Override
        public boolean isExternalStorageAvailable(){
            return true;
        }

        @Override
        public String getLocalStoragePath(){
            return "/";
        }

        @Override
        public boolean isLocalStorageAvailable(){
            return true;
        }
    }

    public static void main(String[] args){
        String temporary = System.getProperty("java.io.tmpdir");
        boolean live = temporary == null || temporary.startsWith("/3ds/min3dstry");
        String base = args.length > 0
            ? args[0]
            : (live
                ? "/3ds/min3dstry"
                : temporary + "/m3ds-campaign-saves");
        Fi directory = new Fi(base);
        directory.mkdirs();

        Core.files = new ProbeFiles();
        Core.assets = new AssetManager(path -> new Fi(path), false);
        Vars.saveDirectory = directory;

        Core.settings = new Settings();
        Core.settings.setAutosave(false);
        Vars.headless = true;
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();

        Sector sector = SectorPresets.groundZero.sector;
        if(sector == null || sector.id != 15 || sector.preset != SectorPresets.groundZero){
            throw new IllegalStateException("Ground zero sector did not initialize");
        }

        Saves saves = new Saves();
        Fi file = saves.getSectorFile(sector);
        if(!"sector-serpulo-15.msav".equals(file.name())){
            throw new IllegalStateException("Unexpected sector file name: " + file.name());
        }
        if(!directory.equals(file.parent())){
            throw new IllegalStateException("Sector file parent differs: " + file.parent());
        }

        System.out.println(
            "UPSTREAM_CAMPAIGN_SAVES path=" + (live ? "live" : "tmp")
            + " sector=sector-serpulo-15.msav parent=" + file.parent().name()
            + " extension=msav saves=ok");
    }
}
