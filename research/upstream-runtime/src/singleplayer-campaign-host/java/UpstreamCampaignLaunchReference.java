import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.StringMap;
import arc.util.I18NBundle;
import java.io.IOException;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Loadouts;
import mindustry.content.SectorPresets;
import mindustry.game.EventType.SectorCaptureEvent;
import mindustry.game.EventType.SectorLaunchEvent;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.io.SaveIO;
import mindustry.type.ItemSeq;
import mindustry.type.Sector;

/** Launch original preset and generated campaign sectors through the offline session. */
public final class UpstreamCampaignLaunchReference {
    public static void main(String[] args) throws Exception {
        Fi assets = new Fi(args[0]), root = new Fi(args[1]);
        boolean manual = args.length > 2 && args[2].equals("manual");
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
            var starters = session.campaignSlots(session.sectorSaves());
            if (starters.size() != 2 || starters.get(0).origin != zero || starters.get(1).origin != onset
                    || settings.getSettingsFile().exists()) {
                throw new AssertionError("A fresh campaign must offer just the two unstarted planets until one is played");
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
            // Seed the last wave with no surviving enemies and let upstream Logic do the capture.
            UpstreamSectorCapture.install();
            var captured = new Sector[1];
            Events.on(SectorCaptureEvent.class, event -> captured[0] = event.sector);
            if (!Vars.state.rules.waves || Vars.state.rules.winWave <= 0 || zero.info.wasCaptured) {
                throw new AssertionError("Expected a fresh wave-based Ground Zero campaign");
            }
            Vars.state.wave = Vars.state.rules.winWave;
            session.stepSimulation(0f, 0f);
            if (captured[0] != zero || !zero.isCaptured() || !zero.info.wasCaptured) {
                throw new AssertionError("Stock wave completion did not capture Ground Zero");
            }
            zero.loadInfo();
            if (!zero.info.wasCaptured || zero.info.waves || zero.info.attack) {
                throw new AssertionError("The capture event did not persist the original sector state");
            }
            if (session.campaignSlots(session.sectorSaves()).stream().anyMatch(slot -> slot.origin == zero && slot.sector.preset == null)) {
                throw new AssertionError("Serpulo must require a Core Foundation for numbered-sector launch");
            }
            var team = Vars.state.rules.defaultTeam;
            team.core().tile.setBlock(Blocks.coreFoundation, team);
            session.save(origin);
            var launchSlots = session.campaignSlots(session.sectorSaves(), zero);
            var selected = launchSlots.stream()
                .filter(slot -> slot.origin == zero && slot.sector.preset == null && !slot.sector.hasEnemyBase())
                .findFirst().orElseThrow();
            var target = selected.sector;
            if (Vars.bases.cores.isEmpty()) throw new AssertionError("No upstream base parts");
            Blocks.coreFoundation.quietUnlock();
            var stockSchematic = Loadouts.basicFoundation;
            var custom = new Schematic(stockSchematic.tiles.copy(), StringMap.of("name", "Campaign test"),
                stockSchematic.width, stockSchematic.height);
            Fi customFile = root.child("schematics/campaign-test.msch");
            customFile.parent().mkdirs();
            Schematics.write(custom, customFile);
            settings.put("lastloadout-core-foundation", customFile.nameWithoutExtension());
            var alternatives = UpstreamCampaignLoadout.choices(zero, target);
            if (!alternatives.options().contains(stockSchematic, true)
                    || !alternatives.options().get(alternatives.selected()).name().equals("Campaign test")) {
                throw new AssertionError("Stock Schematics must load the default and remembered custom launch option");
            }
            settings.remove("lastloadout-core-foundation");
            var unfunded = UpstreamCampaignLoadout.prepare(zero, target);
            if (unfunded.affordable() || selected.file.exists()) {
                throw new AssertionError("An unfunded departure must not be able to launch");
            }
            try {
                session.launchNewSector(zero, target, selected.file, unfunded);
                throw new AssertionError("Insufficient resources should be rejected before launch");
            } catch (IOException expected) {
                if (selected.file.exists() || Vars.state.rules.sector != zero) {
                    throw new AssertionError("Rejected launch changed the current sector", expected);
                }
            }
            unfunded.schematic().requirements().each(team.items()::add);
            team.items().add(Items.copper, 23);
            settings.put("maxresources", false);
            var configuredCargo = new ItemSeq();
            configuredCargo.add(Items.copper, 7);
            Vars.universe.updateLaunchResources(configuredCargo);
            var configured = UpstreamCampaignLoadout.prepare(zero, target);
            if (!configured.affordable() || configured.resources().get(Items.copper) != 7) {
                throw new AssertionError("The launch must preserve configured cargo with maximum cargo disabled");
            }
            var editedCargo = configured.resources().copy();
            int capacity = UpstreamCampaignLoadout.cargoCapacity(zero, configured.schematic());
            editedCargo.set(Items.copper, UpstreamCampaignLoadout.adjustCargo(7, 1, capacity, true));
            var edited = UpstreamCampaignLoadout.prepare(zero, target, alternatives, alternatives.selected(), false, editedCargo);
            if (!edited.affordable() || edited.resources().get(Items.copper) != 8
                    || Vars.universe.getLaunchResources().get(Items.copper) != 7
                    || UpstreamCampaignLoadout.adjustCargo(1000, 1, capacity, false) != 1200
                    || UpstreamCampaignLoadout.adjustCargo(capacity, 1, capacity, false) != capacity
                    || UpstreamCampaignLoadout.adjustCargo(0, -1, capacity, false) != 0) {
                throw new AssertionError("Cargo edits must use stock bounds and preserve the stored draft until launch");
            }
            settings.put("maxresources", true);
            settings.put("lastloadout-core-foundation", customFile.nameWithoutExtension());
            session.save(origin);
            var funded = UpstreamCampaignLoadout.prepare(zero, target);
            if (!funded.affordable() || funded.resources().get(Items.copper) != 23
                    || !funded.schematic().name().equals("Campaign test")) {
                throw new AssertionError("Stock max-resource loadout must subtract schematic requirements");
            }
            Fi fixture = root.child("departure");
            fixture.child("saves").mkdirs();
            fixture.child("schematics").mkdirs();
            customFile.copyTo(fixture.child("schematics").child(customFile.name()));
            origin.copyTo(fixture.child("saves").child(origin.name()));
            Fi onsetSave = session.sectorFile(onset);
            onsetSave.copyTo(fixture.child("saves").child(onsetSave.name()));
            settings.getSettingsFile().copyTo(fixture.child("settings.bin"));
            fixture.child("campaign-select.txt").writeString(Integer.toString(launchSlots.indexOf(selected)));
            fixture.child("destination.txt").writeString(target.id + " " + target.getSize());
            var destinations = new StringBuilder();
            for (var candidate : launchSlots) {
                if (candidate.origin == zero && candidate.sector.preset == null) {
                    destinations.append(candidate.sector.id).append(' ').append(candidate.sector.getSize()).append('\n');
                }
            }
            fixture.child("destinations.txt").writeString(destinations.toString());
            Fi generated = selected.file;
            int carried = manual ? 8 : 23;
            int remaining = 23 - carried;
            session.launchNewSector(zero, target, generated, manual ? edited : funded);
            if (zero.items().get(Items.copper) != remaining
                    || Vars.state.rules.defaultTeam.items().get(Items.copper) != carried
                    || settings.getBool("maxresources", true) == manual) {
                throw new AssertionError("Launch must debit origin and deliver cargo to the new base");
            }
            if (Vars.state.rules.sector != target || target.info.origin != zero || target.info.destination != zero
                    || !target.hasBase() || launched[0] != target || !SaveIO.isSaveValid(generated)
                    || SaveIO.getMeta(generated).rules.sector != target) {
                throw new AssertionError("The original generator did not save a numbered campaign sector with its origin");
            }
            System.out.println("UPSTREAM_CAMPAIGN_GENERATED sector=" + target.id + " origin=" + zero.id
                + " size=" + Vars.world.width() + "x" + Vars.world.height());
            session.start(null, origin, "campaign", zero);
            if (zero.items().get(Items.copper) != remaining) {
                throw new AssertionError("Resuming the old base must apply its post-launch inventory");
            }
        } finally {
            session.shutdown();
        }
    }
}
