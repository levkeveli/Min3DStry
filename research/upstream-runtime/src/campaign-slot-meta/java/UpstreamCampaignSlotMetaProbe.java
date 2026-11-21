import arc.Core;
import arc.Settings;
import arc.files.Fi;
import java.io.DataInputStream;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.ai.WaveSpawner;
import mindustry.content.SectorPresets;
import mindustry.type.Sector;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.mod.Mods;

/** Writes a campaign sector slot with real sector metadata and reloads it. */
public final class UpstreamCampaignSlotMetaProbe {
    /** Sector info is validated through the .msav, not live settings. */
    private static final class ProbeSettings extends Settings {
        @Override
        public synchronized void putJson(String name, Object value){
        }

        @Override
        public synchronized void putJson(String name, Class<?> elementType, Object value){
        }
    }

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

        UpstreamWorldLoadProbe.load(new String[]{paths[0]});
        Vars.spawner = new WaveSpawner();
        Vars.indexer = new BlockIndexer();
        Vars.mods = new Mods();

        // SaveVersion writes sector and tech state through Core.settings.
        Settings settings = new ProbeSettings();
        settings.setAutosave(false);
        Core.settings = settings;

        Sector sector = SectorPresets.groundZero.sector;
        if(sector == null || sector.preset != SectorPresets.groundZero){
            throw new IllegalStateException("Ground zero sector preset did not initialize");
        }
        Vars.state.rules.sector = sector;

        SaveIO.write(output);
        if(!output.exists() || output.length() <= 0){
            throw new IllegalStateException("Sector slot was not written");
        }
        if(!SaveIO.isSaveValid(output)){
            throw new IllegalStateException("Sector slot failed SaveIO validation");
        }

        int version;
        try(DataInputStream input = SaveIO.getStream(output)){
            SaveIO.readHeader(input);
            version = input.readInt();
        }
        if(version != 13){
            throw new IllegalStateException("Unexpected save version: " + version);
        }

        SaveMeta meta = SaveIO.getMeta(output);
        if(meta.wave != Vars.state.wave){
            throw new IllegalStateException("Save wave did not reload");
        }
        if(!"groundZero".equals(meta.tags.get("sectorPreset"))){
            throw new IllegalStateException("Sector preset tag did not reload: " + meta.tags.get("sectorPreset"));
        }
        // v160.8 SaveVersion.writeMeta stores dimensions as tags; SaveMeta has no width/height fields.
        if(!"150".equals(meta.tags.get("width")) || !"250".equals(meta.tags.get("height"))){
            throw new IllegalStateException("Save dimensions did not reload");
        }

        long bytes = output.length();
        output.delete();
        System.out.println(
            "UPSTREAM_CAMPAIGN_SLOT_META slot=" + output.name()
            + " bytes=" + bytes + " version=13 preset=groundZero wave=" + meta.wave
            + " size=150x250 meta=ok");
    }
}
