import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.Settings;
import arc.files.Fi;
import arc.math.Mathf;
import arc.mock.MockFiles;
import arc.mock.MockGraphics;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.BaseRegistry;
import mindustry.ai.BlockIndexer;
import mindustry.ai.WaveSpawner;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.core.NetServer;
import mindustry.core.World;
import mindustry.entities.EntityCollisions;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.graphics.CacheLayer;
import mindustry.io.JsonIO;
import mindustry.logic.GlobalVars;
import mindustry.net.Net;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.BufferedItemBridge;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.Junction;
import mindustry.world.blocks.distribution.OverflowGate;
import mindustry.world.blocks.distribution.Router;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Host-only verification baseline for upstream Mindustry v160.8
 * (19a0f40d44514f4802a8b79770456e7e32e34da0) logistics, distribution routing,
 * drilling, core inventory storage, and deterministic update ordering.
 *
 * Advances #26 using a temporary settings directory removed on exit.
 */
public final class UpstreamFactoryBaseline {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public static void main(String[] args) throws Exception {
        final Path tempDataDir = Files.createTempDirectory("min3dstry-factory-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(tempDataDir)));

        ProbeApplication app = initializeApp(tempDataDir);
        verifyCatalog();

        ScenarioResult run1 = runScenario(app, true);
        ScenarioResult run2 = runScenario(app, false);
        if (run1.totalTicks != run2.totalTicks || run1.traceHash != run2.traceHash) {
            throw new IllegalStateException(
                String.format(
                    Locale.ROOT,
                    "Determinism mismatch: run1=(%d, 0x%016x) run2=(%d, 0x%016x)",
                    run1.totalTicks, run1.traceHash, run2.totalTicks, run2.traceHash));
        }

        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_FACTORY_ORDER runs=2 ticks=%d trace_hash=0x%016x%n",
            run1.totalTicks,
            run1.traceHash);
    }

    private static ProbeApplication initializeApp(Path tempDataDir) {
        CacheLayer.init();
        ProbeApplication app = new ProbeApplication();
        Core.app = app;
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(tempDataDir.toFile()));
        Core.settings.setJson(JsonIO.json);
        Core.settings.load();
        Core.graphics = new MockGraphics() {
            @Override
            public float getDeltaTime() {
                return 1f / 60f;
            }
        };
        Core.files = new MockFiles();
        Time.setDeltaProvider(() -> 1f);

        Vars.headless = true;
        Vars.net = new Net(null);
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Groups.init();
        Vars.world = new World();
        Vars.logic = new Logic();
        Vars.netServer = new NetServer();
        return app;
    }

    private static void verifyCatalog() throws Exception {
        Block[] catalog = {
            Blocks.conveyor,
            Blocks.titaniumConveyor,
            Blocks.armoredConveyor,
            Blocks.junction,
            Blocks.itemBridge,
            Blocks.router,
            Blocks.distributor,
            Blocks.sorter,
            Blocks.invertedSorter,
            Blocks.overflowGate,
            Blocks.underflowGate,
            Blocks.mechanicalDrill,
            Blocks.pneumaticDrill,
            Blocks.coreShard,
            Blocks.coreFoundation,
            Blocks.coreNucleus,
        };

        StringBuilder manifest = new StringBuilder();
        for (Block block : catalog) {
            if (block == null) {
                throw new IllegalStateException("Catalog block missing from Vars.content");
            }
            manifest.append(describeBlock(block)).append('\n');
        }

        if (!(Blocks.conveyor instanceof Conveyor)
                || !(Blocks.titaniumConveyor instanceof Conveyor)
                || !(Blocks.armoredConveyor instanceof Conveyor)) {
            throw new IllegalStateException("Expected Conveyor subclasses");
        }
        if (!(Blocks.junction instanceof Junction)
                || !(Blocks.itemBridge instanceof BufferedItemBridge)
                || !(Blocks.router instanceof Router)
                || !(Blocks.distributor instanceof Router)) {
            throw new IllegalStateException("Expected Junction/BufferedItemBridge/Router subclasses");
        }
        if (!(Blocks.sorter instanceof Sorter s) || s.invert
                || !(Blocks.invertedSorter instanceof Sorter is) || !is.invert) {
            throw new IllegalStateException("Expected Sorter/invertedSorter invert flags");
        }
        if (!(Blocks.overflowGate instanceof OverflowGate og) || og.invert
                || !(Blocks.underflowGate instanceof OverflowGate ug) || !ug.invert) {
            throw new IllegalStateException("Expected OverflowGate/underflowGate invert flags");
        }
        if (!(Blocks.mechanicalDrill instanceof Drill md) || md.tier != 2
                || !(Blocks.pneumaticDrill instanceof Drill pd) || pd.tier != 3) {
            throw new IllegalStateException("Expected Drill tiers");
        }
        if (!(Blocks.coreShard instanceof CoreBlock)
                || !(Blocks.coreFoundation instanceof CoreBlock)
                || !(Blocks.coreNucleus instanceof CoreBlock)) {
            throw new IllegalStateException("Expected CoreBlock subclasses");
        }

        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(manifest.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }

        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_FACTORY_CATALOG blocks=%d manifest_sha256=%s%n",
            catalog.length,
            hex.toString());
    }

    private static String describeBlock(Block block) {
        StringBuilder sb = new StringBuilder();
        sb.append(block.id)
          .append(':').append(block.name)
          .append(":size=").append(block.size)
          .append(":itemCap=").append(block.itemCapacity);
        if (block instanceof Conveyor c) {
            sb.append(":convSpeed=").append(Float.floatToIntBits(c.speed))
              .append(":dispSpeed=").append(Float.floatToIntBits(c.displayedSpeed));
        } else if (block instanceof Junction j) {
            sb.append(":juncSpeed=").append(Float.floatToIntBits(j.speed))
              .append(":juncCap=").append(j.capacity);
        } else if (block instanceof BufferedItemBridge b) {
            sb.append(":bridgeSpeed=").append(Float.floatToIntBits(b.speed))
              .append(":bufCap=").append(b.bufferCapacity)
              .append(":range=").append(b.range);
        } else if (block instanceof Router r) {
            sb.append(":routerSpeed=").append(Float.floatToIntBits(r.speed));
        } else if (block instanceof Sorter s) {
            sb.append(":sorterInvert=").append(s.invert);
        } else if (block instanceof OverflowGate o) {
            sb.append(":overflowSpeed=").append(Float.floatToIntBits(o.speed))
              .append(":invert=").append(o.invert);
        } else if (block instanceof Drill d) {
            sb.append(":tier=").append(d.tier)
              .append(":drillTime=").append(Float.floatToIntBits(d.drillTime))
              .append(":rotSpeed=").append(Float.floatToIntBits(d.rotateSpeed));
        } else if (block instanceof CoreBlock cb) {
            sb.append(":coreUnit=").append(cb.unitType != null ? cb.unitType.name : "none");
        }
        return sb.toString();
    }

    private static ScenarioResult runScenario(ProbeApplication app, boolean printRecords) {
        Events.clear();
        Groups.clear();
        Groups.init();
        Time.clear();
        Mathf.rand.setSeed(160_008L);

        Vars.state = new GameState();
        Vars.world = new World();
        Vars.indexer = new BlockIndexer();
        Vars.spawner = new WaveSpawner();
        Vars.collisions = new EntityCollisions();
        Vars.logic = new Logic();
        Vars.universe = new mindustry.game.Universe();
        Vars.logicVars = new GlobalVars();
        Vars.logicVars.init();
        Vars.bases = new BaseRegistry();
        Vars.bases.load();

        Vars.world.loadGenerator(24, 24, tiles -> {
            for (int tx = 0; tx < tiles.width; tx++) {
                for (int ty = 0; ty < tiles.height; ty++) {
                    tiles.set(tx, ty, new Tile(tx, ty, Blocks.stone, Blocks.air, Blocks.air));
                }
            }
        });
        app.runPosted();

        Team team = Vars.state.rules.defaultTeam;
        Vars.world.tile(18, 12).setBlock(Blocks.coreShard, team, 0);
        CoreBuild core = team.core();
        if (core == null) {
            throw new IllegalStateException("Failed to place coreShard for default team");
        }

        // Lane 1: Direct conveyor -> core delivery (matches 29-tick conveyor timing).
        Tile coreConvTile = Vars.world.tile(16, 12);
        Tile coreSourceTile = Vars.world.tile(15, 12);
        coreConvTile.setBlock(Blocks.conveyor, team, 0);
        coreSourceTile.setBlock(Blocks.junction, team, 0);

        // Lane 2: Orthogonal Junction crossing (east copper vs north lead).
        Tile jEastSrc = Vars.world.tile(2, 4);
        Tile jEastIn = Vars.world.tile(3, 4);
        Tile jCross = Vars.world.tile(4, 4);
        Tile jEastOut = Vars.world.tile(5, 4);
        Tile jEastSinkTile = Vars.world.tile(6, 3);
        Tile jNorthSrc = Vars.world.tile(4, 2);
        Tile jNorthIn = Vars.world.tile(4, 3);
        Tile jNorthOut = Vars.world.tile(4, 5);
        Tile jNorthSinkTile = Vars.world.tile(4, 6);
        jEastSrc.setBlock(Blocks.junction, team, 0);
        jEastIn.setBlock(Blocks.conveyor, team, 0);
        jCross.setBlock(Blocks.junction, team, 0);
        jEastOut.setBlock(Blocks.conveyor, team, 0);
        jEastSinkTile.setBlock(Blocks.container, team, 0);
        jNorthSrc.setBlock(Blocks.junction, team, 1);
        jNorthIn.setBlock(Blocks.conveyor, team, 1);
        jNorthOut.setBlock(Blocks.conveyor, team, 1);
        jNorthSinkTile.setBlock(Blocks.container, team, 0);

        // Lane 3: Sorter configured for copper (copper -> forward, lead -> side).
        Tile sortIn = Vars.world.tile(3, 12);
        Tile sortTile = Vars.world.tile(4, 12);
        Tile sortFwdConv = Vars.world.tile(5, 12);
        Tile sortFwdSinkTile = Vars.world.tile(6, 11);
        Tile sortSideConv = Vars.world.tile(4, 13);
        Tile sortSideSinkTile = Vars.world.tile(4, 14);
        sortIn.setBlock(Blocks.junction, team, 0);
        sortTile.setBlock(Blocks.sorter, team, 0);
        sortFwdConv.setBlock(Blocks.conveyor, team, 0);
        sortFwdSinkTile.setBlock(Blocks.container, team, 0);
        sortSideConv.setBlock(Blocks.conveyor, team, 1);
        sortSideSinkTile.setBlock(Blocks.container, team, 0);
        sortTile.build.configure(Items.copper);

        // Lane 4: OverflowGate with saturated forward conveyor -> side overflow.
        Tile ovIn = Vars.world.tile(3, 19);
        Tile ovTile = Vars.world.tile(4, 19);
        Tile ovFwdConv = Vars.world.tile(5, 19); // dead-end conveyor holds 3 items
        Tile ovSideConv = Vars.world.tile(4, 20);
        Tile ovSideSinkTile = Vars.world.tile(4, 21);
        ovIn.setBlock(Blocks.conveyor, team, 0);
        ovTile.setBlock(Blocks.overflowGate, team, 0);
        ovFwdConv.setBlock(Blocks.conveyor, team, 0);
        ovSideConv.setBlock(Blocks.conveyor, team, 1);
        ovSideSinkTile.setBlock(Blocks.container, team, 0);

        Building jEastSink = jEastSinkTile.build;
        Building jNorthSink = jNorthSinkTile.build;
        Building sortFwdSink = sortFwdSinkTile.build;
        Building sortSideSink = sortSideSinkTile.build;
        Building ovFwdBuild = ovFwdConv.build;
        Building ovSideSink = ovSideSinkTile.build;

        Vars.logic.play();
        app.runPosted();

        int initialCopper = core.items.get(Items.copper);

        // Feed initial test items into all four lanes.
        if (!coreConvTile.build.acceptItem(coreSourceTile.build, Items.copper)) {
            throw new IllegalStateException("Conveyor refused copper item");
        }
        coreConvTile.build.handleItem(coreSourceTile.build, Items.copper);

        if (!jEastIn.build.acceptItem(jEastSrc.build, Items.copper)
                || !jNorthIn.build.acceptItem(jNorthSrc.build, Items.lead)) {
            throw new IllegalStateException("Junction input conveyors refused test items");
        }
        jEastIn.build.handleItem(jEastSrc.build, Items.copper);
        jNorthIn.build.handleItem(jNorthSrc.build, Items.lead);

        if (!sortTile.build.acceptItem(sortIn.build, Items.copper)) {
            throw new IllegalStateException("Sorter refused matching copper item");
        }
        sortTile.build.handleItem(sortIn.build, Items.copper);
        if (!sortTile.build.acceptItem(sortIn.build, Items.lead)) {
            throw new IllegalStateException("Sorter refused filtered lead item");
        }
        sortTile.build.handleItem(sortIn.build, Items.lead);

        int ovInjected = 0;
        for (int i = 0; i < 4; i++) {
            if (!ovTile.build.acceptItem(ovIn.build, Items.titanium)) {
                throw new IllegalStateException("OverflowGate refused item #" + (i + 1));
            }
            ovTile.build.handleItem(ovIn.build, Items.titanium);
            ovInjected++;
        }
        if (ovInjected != 4) {
            throw new IllegalStateException("Expected 4 titanium injections into OverflowGate");
        }

        long traceHash = FNV_OFFSET;
        int totalTicks = 0;
        int conveyorArrival = -1;

        for (int tick = 1; tick <= 240; tick++) {
            Vars.logic.update();
            app.runPosted();
            totalTicks++;
            if (conveyorArrival == -1 && core.items.get(Items.copper) == initialCopper + 1) {
                conveyorArrival = tick;
            }
            traceHash = mixTick(
                traceHash, totalTicks, core, jEastSink, jNorthSink,
                sortFwdSink, sortSideSink, ovFwdBuild, ovSideSink);
        }

        if (conveyorArrival != 29 || core.items.get(Items.copper) != initialCopper + 1) {
            throw new IllegalStateException(
                "Expected conveyor arrival at tick 29 (got " + conveyorArrival + "), coreCopper="
                + core.items.get(Items.copper) + " initial=" + initialCopper);
        }
        if (jEastSink.items.get(Items.copper) != 1 || jEastSink.items.get(Items.lead) != 0
                || jNorthSink.items.get(Items.lead) != 1 || jNorthSink.items.get(Items.copper) != 0) {
            throw new IllegalStateException("Junction streams crossed or failed to arrive");
        }
        if (sortFwdSink.items.get(Items.copper) != 1 || sortFwdSink.items.get(Items.lead) != 0
                || sortSideSink.items.get(Items.lead) != 1 || sortSideSink.items.get(Items.copper) != 0) {
            throw new IllegalStateException("Sorter routing mismatch");
        }
        if (ovFwdBuild.items.get(Items.titanium) != 3 || ovSideSink.items.get(Items.titanium) != 1) {
            throw new IllegalStateException(
                "OverflowGate mismatch: fwd=" + ovFwdBuild.items.get(Items.titanium)
                + " side=" + ovSideSink.items.get(Items.titanium));
        }

        if (printRecords) {
            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_FACTORY_LOGISTICS conveyorArrival=%d junctionOk=true sorterOk=true overflowFwd=3 overflowSide=1%n",
                conveyorArrival);
        }

        // Phase 2: MechanicalDrill over 4 copper ore tiles -> Router -> Conveyor -> Core (ticks 241..720).
        coreSourceTile.setBlock(Blocks.router, team, 0);
        Tile drillTile = Vars.world.tile(13, 12);
        drillTile.setBlock(Blocks.mechanicalDrill, team, 0);
        for (int dx = 0; dx < drillTile.block().size; dx++) {
            for (int dy = 0; dy < drillTile.block().size; dy++) {
                Tile ore = Vars.world.tile(drillTile.x + dx, drillTile.y + dy);
                if (!ore.floor().hasSurface()) {
                    throw new IllegalStateException("Invalid drill fixture tile " + ore);
                }
                ore.setOverlay(Blocks.oreCopper);
            }
        }
        DrillBuild drill = (DrillBuild) drillTile.build;
        if (drill.dominantItem != Items.copper || drill.dominantItems != 4) {
            throw new IllegalStateException("MechanicalDrill failed to register 4 copper ore tiles");
        }

        int copper = core.items.get(Items.copper);
        StringBuilder deliveries = new StringBuilder();
        for (int tick = 241; tick <= 720; tick++) {
            Vars.logic.update();
            app.runPosted();
            totalTicks++;
            int current = core.items.get(Items.copper);
            if (current != copper) {
                if (deliveries.length() > 0) {
                    deliveries.append(',');
                }
                deliveries.append(tick).append('=').append(current);
                copper = current;
            }
            traceHash = mixTick(
                traceHash, totalTicks, core, jEastSink, jNorthSink,
                sortFwdSink, sortSideSink, ovFwdBuild, ovSideSink);
        }
        if (deliveries.length() == 0 || core.items.get(Items.copper) <= initialCopper + 1) {
            throw new IllegalStateException("MechanicalDrill never delivered copper to core");
        }
        if (printRecords) {
            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_FACTORY_DRILL ticks=720 coreCopper=%d arrivals=%s%n",
                core.items.get(Items.copper),
                deliveries.toString());
        }

        return new ScenarioResult(totalTicks, traceHash);
    }

    private static long mixTick(
            long hash,
            int tick,
            CoreBuild core,
            Building jEastSink,
            Building jNorthSink,
            Building sortFwdSink,
            Building sortSideSink,
            Building ovFwdBuild,
            Building ovSideSink) {
        hash = fnvMix(hash, tick);
        hash = fnvMix(hash, core.items.get(Items.copper));
        hash = fnvMix(hash, jEastSink.items.get(Items.copper));
        hash = fnvMix(hash, jNorthSink.items.get(Items.lead));
        hash = fnvMix(hash, sortFwdSink.items.get(Items.copper));
        hash = fnvMix(hash, sortSideSink.items.get(Items.lead));
        hash = fnvMix(hash, ovFwdBuild.items.get(Items.titanium));
        hash = fnvMix(hash, ovSideSink.items.get(Items.titanium));
        return hash;
    }

    private static long fnvMix(long hash, int value) {
        hash ^= (value & 0xffL);
        hash *= FNV_PRIME;
        hash ^= ((value >>> 8) & 0xffL);
        hash *= FNV_PRIME;
        hash ^= ((value >>> 16) & 0xffL);
        hash *= FNV_PRIME;
        hash ^= ((value >>> 24) & 0xffL);
        hash *= FNV_PRIME;
        return hash;
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

    private record ScenarioResult(int totalTicks, long traceHash) {}

    private static final class ProbeApplication implements Application {
        private final Seq<ApplicationListener> listeners = new Seq<>();
        private final ArrayDeque<Runnable> posted = new ArrayDeque<>();
        private String clipboardText = "";

        @Override public void addListener(ApplicationListener listener) { listeners.add(listener); }
        @Override public void removeListener(ApplicationListener listener) { listeners.remove(listener); }
        @Override public Seq<ApplicationListener> getListeners() { return listeners; }
        @Override public ApplicationType getType() { return ApplicationType.headless; }
        @Override public String getClipboardText() { return clipboardText; }
        @Override public void setClipboardText(String content) { clipboardText = content == null ? "" : content; }
        @Override public void post(Runnable runnable) { if (runnable != null) posted.addLast(runnable); }
        @Override public void exit() {}

        void runPosted() {
            while (!posted.isEmpty()) {
                posted.removeFirst().run();
            }
        }
    }
}
