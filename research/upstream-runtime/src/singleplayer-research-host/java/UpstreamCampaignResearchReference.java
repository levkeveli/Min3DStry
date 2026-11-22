import arc.Core;
import arc.Events;
import arc.Settings;
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
import mindustry.type.Sector;

/** Exercises stock research requirements against a real loaded campaign world. */
public final class UpstreamCampaignResearchReference {
    public static void main(String[] args) throws Exception {
        Fi fixtures = new Fi(args[0]);
        Fi output = new Fi(args[1]);
        Core.bundle = I18NBundle.createBundle(fixtures.child("bundle"), Locale.ENGLISH);
        verifySerpulo(fixtures, output.child("serpulo"));
        verifyErekir(fixtures, output.child("erekir"));
    }

    private static void verifySerpulo(Fi fixtures, Fi output) throws Exception {
        Fi world = stage(fixtures, output, "serpulo", "sector-serpulo-170.msav");
        var session = create(fixtures, output);
        try {
            var current = SectorPresets.groundZero.sector;
            if (session.sectorSaves().stream().noneMatch(save -> save.sector == current)
                    || !session.start(null, world, "research-fixture", current)) {
                throw new AssertionError("Could not open the stock Ground Zero sector");
            }
            var saves = new Saves();

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

            Fi partial = output.child("serpulo-partial");
            session.save(partial.child(world.name()));
            Core.settings.forceSave();
            output.child("settings.bin").copyTo(partial.child("settings.bin"));

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

    private static void verifyErekir(Fi fixtures, Fi output) throws Exception {
        Fi slot = stage(fixtures, output, "erekir", "sector-erekir-10.msav");
        var session = create(fixtures, output);
        try {
            var sector = SectorPresets.onset.sector;
            var duct = Blocks.duct.techNode;
            if (new UpstreamCampaignResearch(Planets.erekir.techTree).selectable(duct)) {
                throw new AssertionError("Duct research is available without the upstream On Planet objective");
            }
            if (session.sectorSaves().stream().noneMatch(save -> save.sector == sector)
                    || !session.start(null, slot, "research-fixture", sector)) {
                throw new AssertionError("Could not open the stock Onset sector");
            }

            var research = new UpstreamCampaignResearch(Planets.erekir.techTree);
            int initial = research.available(Items.beryllium);
            if (!research.selectable(duct) || initial != 8 || !research.spend(duct)
                    || !duct.content.unlockedHost() || session.core().items.get(Items.beryllium) != 4) {
                throw new AssertionError("Duct selectable=" + research.selectable(duct) + " initial=" + initial
                    + " remaining=" + session.core().items.get(Items.beryllium) + " cost=" + duct.requirements[0].amount
                    + " unlocked=" + duct.content.unlockedHost());
            }
            session.save(slot);
            Core.settings.forceSave();
            System.out.println("UPSTREAM_CAMPAIGN_RESEARCH_REFERENCE tree=erekir unlocked=duct beryllium=4");
        } finally {
            session.shutdown();
        }
    }

    private static Fi stage(Fi fixtures, Fi output, String planet, String name) {
        output.mkdirs();
        fixtures.child(planet + "-settings.bin").copyTo(output.child("settings.bin"));
        Fi slot = output.child("saves").child(name);
        fixtures.child(name).copyTo(slot);
        return slot;
    }

    private static UpstreamSinglePlayerSession create(Fi fixtures, Fi output) {
        Core.assets = null;
        return UpstreamSinglePlayerSession.create(output, "en", fixtures.child("planets"),
            UpstreamSinglePlayerSession.loadSettings(output));
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
