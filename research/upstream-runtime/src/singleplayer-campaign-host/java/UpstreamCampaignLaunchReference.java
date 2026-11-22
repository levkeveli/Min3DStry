import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Loadouts;
import mindustry.content.SectorPresets;
import mindustry.game.EventType.SectorLaunchEvent;
import mindustry.io.SaveIO;
import mindustry.type.Sector;
import mindustry.world.blocks.storage.CoreBlock;

/** Launch original preset and generated campaign sectors through the offline session. */
public final class UpstreamCampaignLaunchReference {
    public static void main(String[] args) throws Exception {
        Fi assets = new Fi(args[0]), root = new Fi(args[1]);
        if (root.exists()) throw new IllegalArgumentException("Use a new scratch campaign directory");
        root.mkdirs();
        Core.bundle = I18NBundle.createBundle(assets.child("bundle"), Locale.ENGLISH);
        var settings = UpstreamSinglePlayerSession.loadSettings(root);
        var session = UpstreamSinglePlayerSession.create(root, "en", assets.child("planets"), settings,
            assets.child("maps"));
        try {
            if (!session.sectorSaves().isEmpty() || settings.getSettingsFile().exists()) {
                throw new AssertionError("An empty campaign catalog must not create any saves");
            }
            var zero = SectorPresets.groundZero.sector;
            var onset = SectorPresets.onset.sector;
            if (zero.id != 170 || onset.id != 10
                    || zero.planet.getStartSector() != zero || onset.planet.getStartSector() != onset) {
                throw new AssertionError("Stock Serpulo or Erekir starter sector IDs differ");
            }
            var launched = new Sector[1];
            Events.on(SectorLaunchEvent.class, event -> launched[0] = event.sector);
            for (Sector sector : new Sector[]{zero, onset}) {
                if (sector.preset.generator.map == null) {
                    throw new AssertionError("Upstream preset generator has no original map: " + sector.preset.name);
                }
                Fi slot = session.sectorFile(sector);
                session.launchNewSector(sector, sector, slot);
                if (Vars.state.rules.sector != sector || Vars.world.width() != sector.preset.generator.map.width
                        || Vars.world.height() != sector.preset.generator.map.height || Vars.state.rules.defaultTeam.core() == null
                        || sector.info.origin != sector || sector.info.attempts != 1 || launched[0] != sector
                        || !sector.hasBase() || !SaveIO.isSaveValid(slot) || SaveIO.getMeta(slot).rules.sector != sector) {
                    throw new AssertionError("Cannot load the original starter world: " + sector.preset.name);
                }
                settings.getSettingsFile().copyTo(root.child(sector.planet.name + "-settings.bin"));
                System.out.println("UPSTREAM_CAMPAIGN_PRESET planet=" + sector.planet.name
                    + " preset=" + sector.preset.name + " sector=" + sector.id
                    + " size=" + Vars.world.width() + "x" + Vars.world.height());
            }
            var slots = session.sectorSaves();
            if (slots.size() != 2 || !zero.hasBase() || !onset.hasBase()
                    || !settings.getString("last-sector-save").equals("sector-erekir-10")) {
                throw new AssertionError("New campaign bases should be discoverable in stock save slots");
            }
            // Exercise stock eligibility with a captured base before and after a Core Foundation.
            Fi origin = session.sectorFile(zero);
            session.start(null, origin, "campaign", zero);
            zero.info.wasCaptured = true;
            Vars.state.rules.waves = false;
            Vars.state.rules.attackMode = false;
            var generator = zero.planet.generator;
            if (zero.near().contains(sector -> sector.preset == null && generator.allowLanding(sector))) {
                throw new AssertionError("Serpulo must require a Core Foundation for numbered-sector launch");
            }
            var team = Vars.state.rules.defaultTeam;
            team.core().tile.setBlock(Blocks.coreFoundation, team);
            session.save(origin);
            var target = zero.near().find(sector -> sector.preset == null && !sector.hasEnemyBase()
                && generator.allowLanding(sector) && generator.findLaunchCandidate(sector, zero) == zero);
            if (target == null || Vars.bases.cores.isEmpty()) {
                throw new AssertionError("No landing from " + zero.id + " neighbors=" + zero.near()
                    + " allowed=" + zero.planet.allowLaunchToNumbered + " base=" + zero.hasBase()
                    + " attacked=" + zero.isAttacked() + " coreParts=" + Vars.bases.cores.size);
            }
            Fi generated = session.sectorFile(target);
            Vars.universe.updateLoadout((CoreBlock) Blocks.coreFoundation, Loadouts.basicFoundation);
            session.launchNewSector(zero, target, generated);
            if (Vars.state.rules.sector != target || target.info.origin != zero || target.info.destination != zero
                    || !target.hasBase() || launched[0] != target || !SaveIO.isSaveValid(generated)
                    || SaveIO.getMeta(generated).rules.sector != target) {
                throw new AssertionError("The original generator did not save a numbered campaign sector with its origin");
            }
            System.out.println("UPSTREAM_CAMPAIGN_GENERATED sector=" + target.id + " origin=" + zero.id
                + " size=" + Vars.world.width() + "x" + Vars.world.height());
        } finally {
            session.shutdown();
        }
    }
}
