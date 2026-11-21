import arc.Core;
import arc.Settings;
import mindustry.Vars;
import mindustry.content.SectorPresets;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.net.Net;
import mindustry.type.Sector;

/** Exercises upstream campaign sector play/capture state transitions. */
public final class UpstreamCampaignSectorStateProbe {
    public static void main(String[] args){
        Vars.headless = true;
        Core.settings = new Settings();
        Core.settings.setAutosave(false);
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Vars.net = new Net(null);

        Sector sector = SectorPresets.groundZero.sector;
        if(sector == null || sector.id != 15 || sector.preset != SectorPresets.groundZero){
            throw new IllegalStateException("Ground zero sector did not initialize");
        }

        Vars.state.rules.sector = sector;
        Vars.state.rules.waves = false;
        Vars.state.rules.attackMode = false;
        Vars.state.gameOver = false;
        Vars.state.set(GameState.State.playing);

        if(!sector.isBeingPlayed()){
            throw new IllegalStateException("Sector was not marked as being played");
        }
        if(!sector.isCaptured()){
            throw new IllegalStateException("Quiet sector was not captured while playing");
        }

        Vars.state.rules.waves = true;
        if(sector.isCaptured()){
            throw new IllegalStateException("Waved sector was captured while playing");
        }

        Vars.state.rules.waves = false;
        // v160.8 Sector.isBeingPlayed() checks !Vars.state.gameOver directly.
        Vars.state.gameOver = true;
        if(sector.isBeingPlayed()){
            throw new IllegalStateException("Game-over sector was still being played");
        }

        // The menu transition must also end play state.
        Vars.state.set(GameState.State.menu);
        if(sector.isBeingPlayed()){
            throw new IllegalStateException("Menu sector was still being played");
        }

        System.out.println(
            "UPSTREAM_CAMPAIGN_SECTOR_STATE sector=15 groundZero=ok"
            + " played=ok captured=ok waves=locked gameOver=ok menu=ok");
    }
}
