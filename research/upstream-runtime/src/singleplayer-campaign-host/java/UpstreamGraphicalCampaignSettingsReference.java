import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Arrays;
import java.util.Locale;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.SectorPresets;
import mindustry.game.SectorInfo;
import mindustry.io.SaveIO;

/** Exercise the graphical campaign settings lifecycle with a stock PC settings.bin and sector save. */
public final class UpstreamGraphicalCampaignSettingsReference {
    public static void main(String[] args) throws Exception {
        Fi fixtures = new Fi(args[0]), root = new Fi(args[1]);
        if (root.exists()) throw new IllegalArgumentException("Use a new scratch campaign directory");
        Fi sourceSettings = fixtures.child("settings.bin");
        Fi slot = root.child("saves/sector-serpulo-170.msav");
        slot.parent().mkdirs();
        fixtures.child(slot.name()).copyTo(slot);
        sourceSettings.copyTo(root.child("settings.bin"));
        Core.bundle = I18NBundle.createBundle(fixtures.child("bundle"), Locale.ENGLISH);
        var settings = UpstreamSinglePlayerSession.loadSettings(root);
        var session = UpstreamSinglePlayerSession.create(root, "en", fixtures.child("planets"), settings);
        try {
            var zero = SectorPresets.groundZero.sector;
            var forest = SectorPresets.frozenForest.sector;
            var research = Arrays.stream(Blocks.conveyor.techNode.finishedRequirements)
                .filter(stack -> stack.item == Items.copper).findFirst().orElseThrow();
            if (!Blocks.conveyor.unlocked() || research.amount != 7
                    || zero.planet.getLastSector() != forest || !matchesStockSector(zero.info)) {
                throw new AssertionError("PC unlock, research, last sector or sector information was not loaded");
            }
            if (zero.info.items.get(Items.copper) != 19) {
                throw new AssertionError("PC sector inventory was not loaded before world resume");
            }
            var slots = session.sectorSaves();
            if (slots.size() != 1 || slots.get(0).sector != zero || !session.start(null, slot, "campaign", zero)) {
                throw new AssertionError("PC sector save was not resumed");
            }
            Fi second = root.child("sector-serpulo-64.msav");
            fixtures.child(second.name()).copyTo(second);
            slots = session.sectorSaves();
            if (slots.size() != 2 || slots.get(UpstreamSinglePlayerSession.lastSectorRow(slots)).sector != forest) {
                throw new AssertionError("The stock last-sector-save did not highlight Frozen Forest");
            }
            research.amount = 8;
            session.save(slot);
            if (!Arrays.equals(sourceSettings.readBytes(), settings.getBackupSettingsFile().readBytes())) {
                throw new AssertionError("The original PC settings backup was lost");
            }
            var reopened = UpstreamSinglePlayerSession.loadSettings(root);
            if (!reopened.getBool("conveyor-unlocked") || reopened.getInt("req-conveyor-copper") != 8
                    || reopened.getInt("serpulo-last-sector") != zero.id
                    || !reopened.getString("last-sector-save").equals("Stock Ground Zero")
                    || !reopened.getString("campaign-fixture-text").equals("preserved")
                    || reopened.getInt("campaign-fixture-int") != 47
                    || !matchesStockSector(reopened.getJson("serpulo-s-170-info", SectorInfo.class, SectorInfo::new))
                    || SaveIO.getMeta(slot).rules.sector != zero) {
                throw new AssertionError("Stock settings and campaign save did not round-trip");
            }
            System.out.println("UPSTREAM_GRAPHICAL_CAMPAIGN_SETTINGS unlock=conveyor research=7->8 lastSector=64->170 backup=ok");
        } finally {
            session.shutdown();
        }
    }

    private static boolean matchesStockSector(SectorInfo sector) {
        var production = sector.rawProduction.get(Items.copper);
        return "Landing Data".equals(sector.name) && sector.attempts == 3 && sector.wasCaptured
            && sector.origin == SectorPresets.frozenForest.sector && sector.resources.contains(Items.lead)
            && production != null && production.mean == 2.5f && sector.importCooldownTimers.get(Items.lead, -1f) == 3.5f;
    }
}
