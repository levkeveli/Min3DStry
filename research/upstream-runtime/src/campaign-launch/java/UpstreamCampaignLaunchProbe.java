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

/** Writes a sector slot and launches it through the upstream sector save context. */
public final class UpstreamCampaignLaunchProbe {
    public static void main(String[] args) throws Exception{
        if(args.length != 0 && args.length != 2){
            throw new IllegalArgumentException("Expected paths to Glacier and a new sector slot");
        }
        String[] paths = args.length == 2 ? args : new String[]{
            "/3ds/min3dstry/glacier.msav",
            "/3ds/min3dstry/sector-serpulo-15.msav"
        };
        Fi output = new Fi(paths[1]);
        if(output.exists()){
            throw new IllegalArgumentException("Sector slot already exists: " + output);
        }

        var app = UpstreamWorldLoadProbe.load(new String[]{paths[0]});
        Vars.spawner = new WaveSpawner();
        Vars.indexer = new BlockIndexer();
        Vars.mods = new Mods();

        Settings settings = new Settings();
        settings.setAutosave(false);
        settings.setJson(JsonIO.json);
        Core.settings = settings;

        Sector sector = SectorPresets.groundZero.sector;
        if(sector == null || sector.id != 15 || sector.preset != SectorPresets.groundZero){
            throw new IllegalStateException("Ground zero sector did not initialize");
        }

        Vars.state.rules.sector = sector;
        Vars.state.wave = 5;
        SaveIO.write(output);
        if(!SaveIO.isSaveValid(output)){
            throw new IllegalStateException("Sector slot failed SaveIO validation");
        }

        // Launch: load the sector save through its sector context.
        SaveIO.load(output, Vars.world.makeSectorContext(sector));
        app.runPosted();

        if(Vars.state.rules.sector != sector){
            throw new IllegalStateException("Launched save did not restore the sector");
        }
        if(Vars.state.wave != 5){
            throw new IllegalStateException("Launched save did not restore the wave");
        }
        if(Vars.world.width() != 150 || Vars.world.height() != 250){
            throw new IllegalStateException("Launched save did not restore the world");
        }

        long bytes = output.length();
        output.delete();
        System.out.println(
            "UPSTREAM_CAMPAIGN_LAUNCH slot=" + output.name()
            + " bytes=" + bytes + " version=13 sector=15 wave=5"
            + " world=150x250 launch=ok");
    }
}
