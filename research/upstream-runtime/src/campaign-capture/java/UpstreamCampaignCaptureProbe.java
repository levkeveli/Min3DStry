import arc.Core;
import arc.Events;
import arc.Settings;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.ai.WaveSpawner;
import mindustry.content.SectorPresets;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.game.EventType.SectorCaptureEvent;
import mindustry.io.JsonIO;
import mindustry.mod.Mods;
import mindustry.type.Sector;

/** Exercises upstream sector capture state, event, and settings persistence. */
public final class UpstreamCampaignCaptureProbe {
    public static void main(String[] args) throws Exception{
        String map = args.length > 0 ? args[0] : "/3ds/min3dstry/glacier.msav";
        var app = UpstreamWorldLoadProbe.load(new String[]{map});
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

        boolean[] eventCaptured = {false};
        Events.on(SectorCaptureEvent.class, event -> {
            if(event.sector == sector){
                eventCaptured[0] = true;
            }
        });

        Vars.state.rules.sector = sector;
        Vars.state.rules.waves = true;
        Vars.state.rules.attackMode = true;
        Vars.state.rules.winWave = 1;
        Vars.state.wave = 1;
        Vars.state.gameOver = false;
        Vars.state.set(GameState.State.playing);

        if(sector.isCaptured()){
            throw new IllegalStateException("Sector was captured before the capture call");
        }

        Logic.sectorCapture();
        app.runPosted();

        if(!eventCaptured[0]){
            throw new IllegalStateException("SectorCaptureEvent was not dispatched");
        }
        if(Vars.state.rules.waves || Vars.state.rules.attackMode){
            throw new IllegalStateException("Capture did not clear waves/attack rules");
        }
        if(!sector.info.wasCaptured){
            throw new IllegalStateException("Capture did not mark SectorInfo.wasCaptured");
        }
        if(!sector.isCaptured()){
            throw new IllegalStateException("Captured sector did not report isCaptured");
        }

        sector.info.prepare(sector);
        sector.saveInfo();
        String key = sector.planet.name + "-s-" + sector.id + "-info";
        var persisted = Core.settings.getJson(key, mindustry.game.SectorInfo.class,
            mindustry.game.SectorInfo::new);
        if(!persisted.wasCaptured || persisted.waves || persisted.attack){
            throw new IllegalStateException("Sector capture did not persist through SectorInfo JSON");
        }

        System.out.println("UPSTREAM_CAMPAIGN_CAPTURE sector=15 captured=ok"
            + " waves=off attack=off event=ok persisted=ok");
    }
}
