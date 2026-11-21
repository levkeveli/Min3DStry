import arc.ApplicationCore;
import arc.Core;
import arc.assets.AssetManager;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.content.Planets;
import mindustry.content.SectorPresets;
import mindustry.content.TechTree;
import mindustry.ctype.UnlockableContent;
import mindustry.core.ContentLoader;
import mindustry.core.Logic;
import mindustry.core.NetServer;
import mindustry.game.Objectives;
import mindustry.game.Saves;
import mindustry.game.SectorInfo;
import mindustry.io.JsonIO;
import mindustry.net.Net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Host-only verification baseline for upstream Mindustry v160.8
 * (19a0f40d44514f4802a8b79770456e7e32e34da0) persistent sector progression and
 * tech-tree state using Core.settings (settings.bin), TechTree.TechNode,
 * UnlockableContent, Sector/SectorInfo, Objectives, and Saves.SaveSlot.
 *
 * Advances #41 using a temporary settings directory removed on exit.
 */
public class UpstreamCampaignPersistenceBaseline {
    public static void main(String[] args) throws Exception {
        final Path tempDataDir = Files.createTempDirectory("min3dstry-campaign-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(tempDataDir)));

        new HeadlessApplication(new ApplicationCore() {
            @Override
            public void setup() {
                Core.settings.setDataDirectory(new Fi(tempDataDir.toFile()));
                Vars.headless = true;
                Core.settings.setJson(JsonIO.json);
                Core.settings.load();
                Vars.net = new Net(null);
                Vars.init();
                Core.assets = new AssetManager();
                Vars.content.createBaseContent();
                add(Vars.logic = new Logic());
                add(Vars.netServer = new NetServer());
                Vars.content.init();
            }

            @Override
            public void init() {
                super.init();
                try {
                    runSuite();
                    Core.app.exit();
                } catch (Throwable t) {
                    t.printStackTrace(System.err);
                    System.exit(1);
                }
            }
        }, error -> {
            error.printStackTrace(System.err);
            System.exit(1);
        });
    }

    private static void runSuite() throws IOException {
        Saves saves = new Saves();
        verifyInitialLocksAndObjectives();
        verifySaveAndReload(saves);
        verifyResetAndInvalidData();
        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_CAMPAIGN_PASS tech_nodes=%d sectors=%d%n",
            TechTree.all.size,
            Vars.content.sectors().size);
    }

    private static void verifyInitialLocksAndObjectives() {
        if (!SectorPresets.groundZero.alwaysUnlocked
                || SectorPresets.groundZero.originalPosition != 15
                || SectorPresets.groundZero.sector.id != 170
                || !SectorPresets.groundZero.sector.unlocked()) {
            throw new IllegalStateException("Expected groundZero (Serpulo sector 170) initially unlocked");
        }
        if (SectorPresets.frozenForest.originalPosition != 86
                || SectorPresets.frozenForest.sector.id != 64
                || !SectorPresets.frozenForest.locked()
                || !SectorPresets.frozenForest.sector.locked()) {
            throw new IllegalStateException("Expected frozenForest (Serpulo sector 64) initially locked");
        }
        TechTree.TechNode frozenNode = SectorPresets.frozenForest.techNode;
        if (frozenNode == null || frozenNode.parent == null
                || frozenNode.parent.content != SectorPresets.groundZero) {
            throw new IllegalStateException("Expected frozenForest parent to be groundZero");
        }
        Objectives.SectorComplete groundZeroObj = findSectorComplete(frozenNode, SectorPresets.groundZero);
        if (groundZeroObj == null || groundZeroObj.complete()) {
            throw new IllegalStateException("Expected frozenForest SectorComplete(groundZero) initially incomplete");
        }

        if (!Blocks.coreShard.alwaysUnlocked
                || !Blocks.conveyor.locked()
                || !Blocks.junction.locked()
                || !Blocks.router.locked()) {
            throw new IllegalStateException("Expected conveyor -> junction -> router initially locked");
        }
        if (Blocks.router.techNode == null
                || Blocks.router.techNode.requirements.length == 0
                || Blocks.router.techNode.finishedRequirements[0].amount != 0) {
            throw new IllegalStateException("Expected router requirements initially 0");
        }

        System.out.println(
            "UPSTREAM_CAMPAIGN_INIT root=groundZero(id=170,unlocked=true) frozenForest(id=64,locked=true,objComplete=false) conveyorLocked=true junctionLocked=true routerLocked=true");
    }

    private static void verifySaveAndReload(Saves saves) {
        Blocks.conveyor.unlock();
        Blocks.junction.quietUnlock();
        if (!Blocks.conveyor.unlocked() || !Blocks.junction.unlocked() || !Blocks.router.locked()) {
            throw new IllegalStateException("Unlock state mismatch before save");
        }

        TechTree.TechNode routerNode = Blocks.router.techNode;
        int reqAmount = routerNode.requirements[0].amount;
        int partial = Math.max(1, reqAmount / 2);
        routerNode.finishedRequirements[0].amount = partial;
        routerNode.save();

        Fi saveFile = saves.getSectorFile(SectorPresets.groundZero.sector);
        saveFile.parent().mkdirs();
        SectorPresets.groundZero.sector.save = saves.new SaveSlot(saveFile);
        SectorInfo info = SectorPresets.groundZero.sector.info;
        info.wasCaptured = true;
        info.hasCore = true;
        info.waves = false;
        info.attack = false;
        info.wave = 15;
        info.winWave = 15;
        info.bestCoreType = Blocks.coreShard;
        info.storageCapacity = 4000;
        // SectorInfo.resources is arc.struct.Seq<UnlockableContent>; add elements explicitly.
        Seq<UnlockableContent> resources = info.resources;
        resources.add(Items.copper);
        resources.add(Items.lead);
        SectorPresets.groundZero.sector.saveInfo();
        SectorPresets.groundZero.unlock();

        if (!SectorPresets.groundZero.sector.isCaptured() || !SectorPresets.groundZero.sector.hasBase()) {
            throw new IllegalStateException("Expected groundZero to report captured base");
        }
        Objectives.SectorComplete objBefore = findSectorComplete(
            SectorPresets.frozenForest.techNode, SectorPresets.groundZero);
        if (objBefore == null || !objBefore.complete()) {
            throw new IllegalStateException("Expected frozenForest objective complete after groundZero capture");
        }

        Core.settings.forceSave();
        Fi settingsFile = Core.settings.getSettingsFile();
        if (!settingsFile.exists() || settingsFile.length() <= 0L) {
            throw new IllegalStateException("Expected non-empty settings.bin after forceSave");
        }
        long settingsBytes = settingsFile.length();

        // Simulate cold restart from persisted settings.bin.
        Core.settings.clear();
        Core.settings.load();
        TechTree.all.clear();
        TechTree.roots.clear();
        Planets.serpulo.sectors.each(s -> {
            s.save = null;
            s.info = new SectorInfo();
        });
        Planets.erekir.sectors.each(s -> {
            s.save = null;
            s.info = new SectorInfo();
        });
        // Recreating ContentLoader + createBaseContent() + init() rebuilds Planets/SectorPresets and runs Planet.init() + SectorPreset.init() (SectorPreset.initialize()).
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        if (SectorPresets.groundZero.originalPosition != 15
                || SectorPresets.groundZero.sector.id != 170
                || SectorPresets.frozenForest.originalPosition != 86
                || SectorPresets.frozenForest.sector.id != 64) {
            throw new IllegalStateException("Reloaded SectorPreset ID remapping mismatch");
        }

        SectorPresets.groundZero.sector.loadInfo();
        SectorPresets.groundZero.sector.save = saves.new SaveSlot(saveFile);

        if (!Blocks.conveyor.unlocked() || !Blocks.junction.unlocked() || !Blocks.router.locked()) {
            throw new IllegalStateException("Reloaded block unlock state mismatch");
        }
        TechTree.TechNode reloadedRouter = Blocks.router.techNode;
        if (reloadedRouter.finishedRequirements[0].amount != partial) {
            throw new IllegalStateException(
                "Reloaded router progress mismatch: " + reloadedRouter.finishedRequirements[0].amount);
        }
        SectorInfo reloadedInfo = SectorPresets.groundZero.sector.info;
        if (!reloadedInfo.wasCaptured || !reloadedInfo.hasCore
                || reloadedInfo.wave != 15 || reloadedInfo.winWave != 15
                || reloadedInfo.storageCapacity != 4000 || reloadedInfo.bestCoreType != Blocks.coreShard
                || !reloadedInfo.resources.contains(Items.copper)
                || !reloadedInfo.resources.contains(Items.lead)) {
            throw new IllegalStateException("Reloaded groundZero SectorInfo mismatch");
        }
        if (!SectorPresets.groundZero.sector.isCaptured()) {
            throw new IllegalStateException("Reloaded groundZero sector is not captured");
        }
        Objectives.SectorComplete objAfter = findSectorComplete(
            SectorPresets.frozenForest.techNode, SectorPresets.groundZero);
        if (objAfter == null || !objAfter.complete()) {
            throw new IllegalStateException("Expected reloaded frozenForest objective complete");
        }

        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_CAMPAIGN_RELOAD settings_bytes=%d conveyorUnlocked=true junctionUnlocked=true routerLocked=true routerProgress=%d/%d groundZeroCaptured=true frozenForestObjComplete=true%n",
            settingsBytes,
            partial,
            reqAmount);
    }

    private static void verifyResetAndInvalidData() throws IOException {
        // Test legacy Blocks.water -> Liquids.water sanitization in Sector.loadInfo() (Sector.java:109-116).
        // Blocks.water and Liquids.water both extend UnlockableContent; Sector.loadInfo() replaces Blocks.water in Seq<UnlockableContent> resources.
        Seq<UnlockableContent> resources = SectorPresets.groundZero.sector.info.resources;
        resources.add(Blocks.water);
        SectorPresets.groundZero.sector.saveInfo();
        SectorPresets.groundZero.sector.loadInfo();
        Seq<UnlockableContent> migratedResources = SectorPresets.groundZero.sector.info.resources;
        if (migratedResources.contains(Blocks.water) || !migratedResources.contains(Liquids.water)) {
            throw new IllegalStateException("Sector.loadInfo() did not migrate Blocks.water to Liquids.water");
        }

        Blocks.conveyor.clearUnlock();
        Blocks.router.techNode.reset();
        SectorPresets.groundZero.sector.clearInfo();
        if (!Blocks.conveyor.locked()
                || Blocks.router.techNode.finishedRequirements[0].amount != 0
                || SectorPresets.groundZero.sector.info.wasCaptured) {
            throw new IllegalStateException("Progression reset failed");
        }

        // Step 3a: Corrupt settings.bin while the backup saved by the prior load() exists -> Arc restores it.
        Files.write(Core.settings.getSettingsFile().file().toPath(), new byte[] {0x7F, 0x00, 0x01});
        Core.settings.clear();
        Core.settings.load();
        boolean recoveredBackup = Core.settings.getBool("conveyor-unlocked", false)
            && Core.settings.getBool("junction-unlocked", false)
            && Core.settings.getInt("req-router-copper", 0) > 0;
        if (!recoveredBackup) {
            throw new IllegalStateException("Expected Arc Settings backup recovery to restore persisted state");
        }

        // Step 3b: Remove backups too -> loadValues() catches backup failure and falls back to defaults.
        Files.deleteIfExists(Core.settings.getBackupSettingsFile().file().toPath());
        deleteTree(Core.settings.getBackupFolder().file().toPath());
        Files.write(Core.settings.getSettingsFile().file().toPath(), new byte[] {0x7F, 0x00, 0x01});
        Core.settings.clear();
        Core.settings.load();
        if (Core.settings.getBool("conveyor-unlocked", false)
                || Core.settings.getInt("req-router-copper", 0) != 0) {
            throw new IllegalStateException("Expected empty defaults after unrecoverable settings corruption");
        }

        System.out.println(
            "UPSTREAM_CAMPAIGN_RESILIENCE waterMigrated=true resetOk=true backupRecovered=true corruptHandled=true");
    }

    private static Objectives.SectorComplete findSectorComplete(
            TechTree.TechNode node, mindustry.type.SectorPreset preset) {
        if (node == null) {
            return null;
        }
        for (Objectives.Objective obj : node.objectives) {
            if (obj instanceof Objectives.SectorComplete sc && sc.preset == preset) {
                return sc;
            }
        }
        return null;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
