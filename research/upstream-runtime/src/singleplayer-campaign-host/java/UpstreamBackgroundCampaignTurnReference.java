import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.content.SectorPresets;

/** Checks a complete upstream campaign turn through the ordinary offline update loop. */
public final class UpstreamBackgroundCampaignTurnReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected fixtures, assets, bundle base and output");
        Fi input = new Fi(args[0]), assets = new Fi(args[1]), root = new Fi(args[3]);
        if (root.exists()) throw new IllegalArgumentException("Use a new output directory");
        Fi saves = root.child("saves");
        saves.mkdirs();
        for (Fi file : input.child("saves").list()) file.copyTo(saves.child(file.name()));
        input.child("settings.bin").copyTo(root.child("settings.bin"));
        Core.bundle = I18NBundle.createBundle(new Fi(args[2]), Locale.ENGLISH);
        var settings = UpstreamSinglePlayerSession.loadSettings(root);
        var session = UpstreamSinglePlayerSession.create(root, "en", assets.child("planets"), settings,
            assets.child("maps"));
        try {
            var landing = SectorPresets.groundZero.sector;
            var producer = SectorPresets.frozenForest.sector;
            var frozen = SectorPresets.crateredBattleground.sector;
            var slots = session.sectorSaves();
            var slot = slots.stream().filter(candidate -> candidate.sector == landing).findFirst().orElseThrow();
            if (slots.size() != 3 || !producer.hasBase() || !frozen.isFrozen()
                    || !landing.planet.campaignRules.legacyLaunchPads
                    || producer.info.destination != landing) {
                throw new AssertionError("Stock campaign slots, producer route or legacy launch rules are missing");
            }
            if (!session.start(null, slot.file, "campaign", landing) || session.core().items.get(Items.copper) != 7) {
                throw new AssertionError("Did not reopen the original receiving sector");
            }
            int turn = Vars.universe.turn();
            for (int tick = 0; tick < (int)Vars.turnDuration; tick++) {
                session.stepSimulation(0f, 0f);
            }
            if (Vars.universe.turn() != turn + 1 || session.core().items.get(Items.copper) != 37
                    || landing.info.lastImported.get(Items.copper) != 30
                    || producer.info.items.get(Items.copper) != 100 || producer.info.items.get(Items.lead) != 0
                    || frozen.info.items.get(Items.copper) != 11 || !frozen.isFrozen()) {
                throw new AssertionError("Background production, export, or freeze differs from v160.8");
            }
            session.save(slot.file);
            System.out.println("UPSTREAM_BACKGROUND_TURN live=37 import=30 remote=100 lead=0 frozen=11");
        } finally {
            session.shutdown();
        }
    }
}
