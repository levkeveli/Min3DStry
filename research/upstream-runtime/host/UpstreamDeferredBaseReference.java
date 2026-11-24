import mindustry.Vars;
import mindustry.ai.BaseRegistry;
import mindustry.content.Planets;

public class UpstreamDeferredBaseReference {
    public static String run() {
        Vars.bases = new BaseRegistry();
        boolean deferred = Boolean.getBoolean("m3ds.deferred");
        if(!deferred) Vars.bases.load();
        if(deferred && (!Vars.bases.cores.isEmpty() || !Vars.bases.parts.isEmpty())) throw new AssertionError();
        Vars.logic.reset();
        var sector = Planets.serpulo.sectors.find(s -> s.id > 100 && s.preset == null);
        sector.generateEnemyBase = true;
        Vars.world.loadSector(sector);
        long digest = 0xcbf29ce484222325L;
        for(var tile : Vars.world.tiles){
            long value = tile.floor().id | (long)tile.overlay().id << 12 | (long)tile.block().id << 24;
            if(tile.build != null) value |= (long)tile.build.team.id << 36 | (long)tile.build.rotation << 44;
            digest = (digest ^ value) * 0x100000001b3L;
        }
        if(Vars.bases.cores.isEmpty() || Vars.bases.parts.isEmpty()) throw new AssertionError("Catalog missing in generator");
        return "BASE_GENERATION sector=" + sector.id + " cells=" + Vars.world.tiles.width * Vars.world.tiles.height
            + " cores=" + Vars.bases.cores.size + " parts=" + Vars.bases.parts.size + " digest=" + Long.toHexString(digest);
    }
}