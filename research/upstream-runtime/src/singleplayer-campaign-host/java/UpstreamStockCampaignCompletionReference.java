import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import arc.util.Time;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.SectorPresets;
import mindustry.core.Logic;
import mindustry.type.Sector;

public final class UpstreamStockCampaignCompletionReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected staged campaign assets and a scratch directory");
        var assets = new Fi(args[0]);
        var scratch = new Fi(args[1]);
        Core.bundle = I18NBundle.createBundle(assets.child("bundle"), Locale.ENGLISH);
        var session = UpstreamSinglePlayerSession.create(scratch, "en", assets.child("planets"),
            UpstreamSinglePlayerSession.loadSettings(scratch), assets.child("maps"));
        try {
            checkCapture(session, SectorPresets.groundZero.sector, false, scratch);
            checkCapture(session, SectorPresets.planetaryTerminal.sector, true, scratch);
            checkCapture(session, SectorPresets.origin.sector, true, scratch);
        } finally {
            session.shutdown();
        }
    }

    private static void checkCapture(UpstreamSinglePlayerSession session, Sector sector, boolean last, Fi scratch)
            throws Exception {
        var slot = session.sectorFile(sector);
        session.launchNewSector(sector, sector, slot);
        if (sector.info.wasCaptured || Vars.state.rules.sector != sector) throw new AssertionError("Expected a fresh stock sector");
        if (last) {
            if (!Vars.state.rules.attackMode) throw new AssertionError("The stock final sector must use its attack rules");
            var opposingCores = Vars.state.rules.waveTeam.cores().copy();
            if (opposingCores.isEmpty()) throw new AssertionError("Missing original enemy bases");
            opposingCores.each(core -> core.tile.remove());
            session.save(slot);
            var replay = scratch.child("replay").child(sector.planet.name);
            replay.child("saves").mkdirs();
            slot.copyTo(replay.child("saves").child(slot.name()));
            Core.settings.getSettingsFile().copyTo(replay.child("settings.bin"));
            session.stepSimulation(0f, 0f);
            if (!sector.info.wasCaptured) throw new AssertionError("Stock Logic did not recognize the final capture");
        } else {
            Time.updateGlobal();
            Logic.sectorCapture();
        }
        advance(119);
        if (session.takeCampaignCompletion() != null) throw new AssertionError("The final-sector dialog opened early");
        advance(1);
        var completion = session.takeCampaignCompletion();
        if (completion != (last ? sector.planet : null)) throw new AssertionError("Wrong stock completion for " + sector.id);
        if (last) {
            session.beginPlaytimeInterval(0);
            session.updatePlaytime(9_000_000_000_000L);
            session.save(slot);
            String text = String.join("\n", UpstreamSinglePlayerApp.campaignCompletionCaptions(completion));
            if (!text.contains("The enemy on " + sector.planet.localizedName + " has been defeated.")
                    || !text.contains("The final sector has been conquered.")
                    || !text.contains("Sector Playtime: 2:30")
                    || !text.contains("A " + Core.bundle.get("continue"))
                    || !text.contains("B " + Core.bundle.get("menu"))) {
                throw new AssertionError("Stock campaign completion strings or playtime differ: " + text);
            }
        }
        Logic.sectorCapture();
        advance(120);
        if (session.takeCampaignCompletion() != null) throw new AssertionError("Repeated capture reopened completion");
        System.out.println("UPSTREAM_CAMPAIGN_COMPLETION_REFERENCE planet=" + sector.planet.name
            + " sector=" + sector.id + " final=" + last + " delayed=ok repeat=suppressed");
    }

    private static void advance(int frames) {
        for (int i = 0; i < frames; i++) Time.update();
    }
}
