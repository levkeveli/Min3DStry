import arc.Core;
import arc.Settings;
import arc.assets.AssetManager;
import arc.files.Fi;
import arc.mock.MockFiles;
import arc.util.Threads;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.ai.WaveSpawner;
import mindustry.content.SectorPresets;
import mindustry.game.Saves;
import mindustry.io.JsonIO;
import mindustry.io.SaveIO;
import mindustry.mod.Mods;
import mindustry.type.Sector;

/** Loads real upstream sector slots through Saves.load() on the target executor. */
public final class UpstreamCampaignSavesLoadProbe {
    public static void main(String[] args) throws Exception{
        try{
            run(args);
        }catch(Throwable failure){
            System.out.println("UPSTREAM_CAMPAIGN_SAVES_LOAD_ERROR type="
                + failure.getClass().getName() + " message=" + failure.getMessage());
            throw failure;
        }
    }

    private static void run(String[] args) throws Exception{
        String temporary = System.getProperty("java.io.tmpdir");
        String base = args.length > 0
            ? args[0]
            : (temporary != null
                ? temporary + "/m3ds-campaign-saves-load"
                : "/3ds/min3dstry");
        boolean live = base.startsWith("/3ds/");
        Fi directory = new Fi(base).child("tmp/saves-load");
        directory.mkdirs();
        directory.emptyDirectory();
        step("directory");

        String mapPath = args.length > 1
            ? args[1]
            : (live ? "/3ds/min3dstry/glacier.msav" : base + "/glacier.msav");
        UpstreamWorldLoadProbe.load(new String[]{mapPath});
        step("world");

        Core.settings = new Settings();
        Core.settings.setAutosave(false);
        Core.settings.setJson(JsonIO.json);
        Core.files = new MockFiles();
        Core.assets = new AssetManager(path -> new Fi(path), false);
        Vars.spawner = new WaveSpawner();
        Vars.indexer = new BlockIndexer();
        Vars.mods = new Mods();
        step("content");

        Sector sector = SectorPresets.groundZero.sector;
        if(sector == null || sector.id != 15 || sector.preset != SectorPresets.groundZero){
            throw new IllegalStateException("Ground zero sector did not initialize");
        }

        Vars.state.rules.sector = sector;
        Vars.state.wave = 5;
        Fi slotFile = directory.child("sector-serpulo-15.msav");
        SaveIO.write(slotFile);
        step("write");

        Vars.saveDirectory = directory;
        Vars.mainExecutor = Threads.executor("campaign-saves-load");
        step("executor");
        try{
            Saves saves = new Saves();
            step("saves");
            saves.load();
            step("load");
            var slots = saves.getSaveSlots();
            if(slots.size != 1 || !"sector-serpulo-15.msav".equals(slots.first().file.name())){
                throw new IllegalStateException(
                    "Saves.load returned " + slots.size + " slot(s): "
                    + (slots.isEmpty() ? "none" : slots.first().file.name()));
            }
            if(saves.getLastSector() != null){
                throw new IllegalStateException("A metadata-free slot was treated as a sector save");
            }
            String name = slots.first().file.name();
            long bytes = slotFile.length();
            directory.emptyDirectory();
            directory.delete();
            System.out.println(
                "UPSTREAM_CAMPAIGN_SAVES_LOAD path=" + (live ? "live" : "tmp")
                + " slots=1 file=" + name + " bytes=" + bytes + " load=ok");
        }finally{
            Vars.mainExecutor.shutdownNow();
        }
    }

    private static void step(String name){
        System.out.println("UPSTREAM_CAMPAIGN_SAVES_LOAD_STEP step=" + name);
    }
}
