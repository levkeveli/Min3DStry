import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.ai.WaveSpawner;
import mindustry.content.SectorPresets;
import mindustry.io.JsonIO;
import mindustry.io.SaveIO;
import mindustry.mod.Mods;
import mindustry.type.Sector;

/** Discovers a sector slot by filename and SaveIO validity without Saves.load(). */
public final class UpstreamCampaignSlotDiscoveryProbe {
    public static void main(String[] args) throws Exception{
        String temporary = System.getProperty("java.io.tmpdir");
        String base = args.length > 0
            ? args[0]
            : (temporary != null
                ? temporary + "/m3ds-campaign-slot-discovery"
                : "/3ds/min3dstry");
        boolean live = base.startsWith("/3ds/");
        Fi directory = new Fi(base).child("tmp/slot-discovery");
        directory.mkdirs();
        directory.emptyDirectory();

        String mapPath = args.length > 1 ? args[1] : base + "/glacier.msav";
        UpstreamWorldLoadProbe.load(new String[]{mapPath});

        Core.settings = new Settings();
        Core.settings.setAutosave(false);
        Core.settings.setJson(JsonIO.json);
        Vars.spawner = new WaveSpawner();
        Vars.indexer = new BlockIndexer();
        Vars.mods = new Mods();

        Sector sector = SectorPresets.groundZero.sector;
        if(sector == null || sector.id != 15 || sector.preset != SectorPresets.groundZero){
            throw new IllegalStateException("Ground zero sector did not initialize");
        }

        Vars.state.rules.sector = sector;
        Vars.state.wave = 5;
        Fi slotFile = directory.child("sector-serpulo-15.msav");
        SaveIO.write(slotFile);

        int slots = 0;
        String name = null;
        for(Fi file : directory.list()){
            if("msav".equals(file.extension()) && !file.name().contains("backup") && SaveIO.isSaveValid(file)){
                slots++;
                name = file.name();
            }
        }

        if(slots != 1 || !"sector-serpulo-15.msav".equals(name)){
            throw new IllegalStateException("Expected one discovered slot, got " + slots + ": " + name);
        }

        long bytes = slotFile.length();
        directory.delete();
        System.out.println(
            "UPSTREAM_CAMPAIGN_SLOT_DISCOVERY path=" + (live ? "live" : "tmp")
            + " slots=1 file=sector-serpulo-15.msav bytes=" + bytes + " discovery=ok");
    }
}
