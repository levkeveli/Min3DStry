import arc.Core;
import arc.Events;
import arc.Settings;
import arc.assets.AssetManager;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.content.SectorPresets;
import mindustry.game.EventType.ResearchEvent;
import mindustry.game.Saves;
import mindustry.io.JsonIO;
import mindustry.type.Sector;

/** Exercises stock research requirements against a real loaded campaign world. */
public final class UpstreamCampaignResearchReference {
    public static void main(String[] args) throws Exception {
        Fi fixtures = new Fi(args[0]);
        Fi output = new Fi(args[1]);
        output.mkdirs();
        Core.bundle = I18NBundle.createBundle(fixtures.child("bundle"), Locale.ENGLISH);
        Vars.tree.addFile("planets/serpulo.json", fixtures.child("planets/serpulo.json"));
        var session = UpstreamSinglePlayerSession.create(output, "en");
        try {
            Core.settings.setDataDirectory(output);
            Core.settings.setJson(JsonIO.json);
            Core.settings.load();
            Vars.saveDirectory = output.child("saves");
            Core.assets = new AssetManager();
            var saves = new Saves();
            var current = SectorPresets.groundZero.sector;
            Fi world = fixtures.child("sector-serpulo-" + current.id + ".msav");
            if (!session.start(null, world, "research-fixture", current)) {
                throw new AssertionError("Could not open the stock Ground Zero sector");
            }
            current.save = saves.new SaveSlot(world);
            current.info.hasCore = true;
            Sector remote = SectorPresets.frozenForest.sector;
            giveRemoteBase(saves, remote, 2);
            giveRemoteBase(saves, SectorPresets.onset.sector, 500);
            session.core().items.set(Items.copper, 1);

            var conveyor = Blocks.conveyor.techNode;
            var research = new UpstreamCampaignResearch(Planets.serpulo.techTree);
            var frozenForest = SectorPresets.frozenForest.techNode;
            var erekirResearch = new UpstreamCampaignResearch(Planets.erekir.techTree);
            if (research.selectable(frozenForest) || research.spend(frozenForest)
                    || !research.selectable(conveyor) || research.available(Items.copper) != 3
                    || erekirResearch.available(Items.copper) != 500 || erekirResearch.selectable(conveyor)) {
                throw new AssertionError("Research ignored a stock objective or included another planet's resources");
            }

            int[] unlocks = {0};
            Events.on(ResearchEvent.class, event -> {
                if (event.content == Blocks.conveyor) unlocks[0]++;
            });
            if (!research.spend(conveyor) || conveyor.content.unlockedHost() || unlocks[0] != 0
                    || Core.settings.getInt("req-conveyor-copper") != 3
                    || remote.info.items.get(Items.copper) != 0 || session.core().items.get(Items.copper) != 0) {
                throw new AssertionError("Partial stock research consumed or persisted the wrong resources");
            }

            remote.info.items.set(Items.copper, 1);
            session.core().items.set(Items.copper, 8);
            research = new UpstreamCampaignResearch(Planets.serpulo.techTree);
            int requirement = conveyor.requirements[0].amount;
            int expectedCore = 8 - (requirement - 3 - 1);
            if (!research.spend(conveyor) || !conveyor.content.unlockedHost() || unlocks[0] != 1
                    || research.spend(conveyor) || session.core().items.get(Items.copper) != expectedCore
                    || remote.info.items.get(Items.copper) != 0) {
                throw new AssertionError("Completed research did not use other sectors before the live core");
            }
            Core.settings.forceSave();
            Settings readback = new Settings();
            readback.setDataDirectory(output);
            readback.load();
            if (!readback.getBool("conveyor-unlocked") || readback.getInt("req-conveyor-copper") != requirement) {
                throw new AssertionError("Original Arc settings lost completed research");
            }
            System.out.println("UPSTREAM_CAMPAIGN_RESEARCH_REFERENCE blocked=frozenForest partial=3/"
                + requirement + " liveCopper=" + expectedCore + " unlocks=1");
        } finally {
            session.shutdown();
        }
    }

    private static void giveRemoteBase(Saves saves, Sector sector, int copper) {
        sector.save = saves.new SaveSlot(saves.getSectorFile(sector));
        sector.info.hasCore = true;
        sector.info.waves = false;
        sector.info.attack = false;
        sector.info.storageCapacity = 1000;
        sector.info.items.set(Items.copper, copper);
    }
}
