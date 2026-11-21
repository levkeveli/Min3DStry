import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.Settings;
import arc.files.Fi;
import arc.func.Cons;
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
import mindustry.content.Liquids;
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
import mindustry.type.ItemStack;
import mindustry.type.LiquidStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.power.Battery;
import mindustry.world.blocks.power.ConsumeGenerator;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;
import mindustry.world.blocks.production.Pump;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItemFilter;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquidFilter;
import mindustry.world.consumers.ConsumePower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Host-only verification baseline for upstream Mindustry v160.8
 * (19a0f40d44514f4802a8b79770456e7e32e34da0) production recipes, power
 * graphs, liquid flow, capacity limits, starvation, and update ordering.
 *
 * Advances #39 using a temporary settings directory removed on exit.
 */
public final class UpstreamProductionBaseline {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public static void main(String[] args) throws Exception {
        final Path tempDataDir = Files.createTempDirectory("min3dstry-production-");
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
            "UPSTREAM_PRODUCTION_ORDER runs=2 ticks=%d trace_hash=0x%016x%n",
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
            Blocks.mechanicalPump,
            Blocks.rotaryPump,
            Blocks.conduit,
            Blocks.liquidRouter,
            Blocks.liquidTank,
            Blocks.combustionGenerator,
            Blocks.steamGenerator,
            Blocks.powerNode,
            Blocks.battery,
            Blocks.graphitePress,
            Blocks.siliconSmelter,
            Blocks.cultivator,
        };

        StringBuilder manifest = new StringBuilder();
        for (Block block : catalog) {
            if (block == null) {
                throw new IllegalStateException("Catalog block missing from Vars.content");
            }
            manifest.append(describeBlock(block)).append('\n');
        }

        if (!(Blocks.mechanicalPump instanceof Pump) || !(Blocks.rotaryPump instanceof Pump)) {
            throw new IllegalStateException("Expected mechanicalPump and rotaryPump to be Pump blocks");
        }
        if (!(Blocks.combustionGenerator instanceof ConsumeGenerator)
                || !(Blocks.steamGenerator instanceof ConsumeGenerator)) {
            throw new IllegalStateException("Expected combustion/steam generators to be ConsumeGenerator blocks");
        }
        if (!(Blocks.battery instanceof Battery) || !(Blocks.powerNode instanceof PowerNode)) {
            throw new IllegalStateException("Expected battery and powerNode block subclasses");
        }
        if (!(Blocks.graphitePress instanceof GenericCrafter press)
                || !(Blocks.siliconSmelter instanceof GenericCrafter smelter)
                || !(Blocks.cultivator instanceof GenericCrafter cultivator)) {
            throw new IllegalStateException("Expected graphitePress, siliconSmelter, cultivator to be GenericCrafter");
        }
        if (press.outputItem == null || press.outputItem.item != Items.graphite
                || smelter.outputItem == null || smelter.outputItem.item != Items.silicon
                || cultivator.outputItem == null || cultivator.outputItem.item != Items.sporePod) {
            throw new IllegalStateException("Unexpected GenericCrafter output items");
        }
        ConsumePower batPower = Blocks.battery.findConsumer(c -> c instanceof ConsumePower);
        if (batPower == null || !batPower.buffered || batPower.capacity <= 0f) {
            throw new IllegalStateException("Expected battery to have buffered ConsumePower capacity");
        }

        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(manifest.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }

        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_PRODUCTION_CATALOG blocks=%d manifest_sha256=%s%n",
            catalog.length,
            hex.toString());
    }

    private static String describeBlock(Block block) {
        StringBuilder sb = new StringBuilder();
        sb.append(block.id)
          .append(':').append(block.name)
          .append(":size=").append(block.size)
          .append(":itemCap=").append(block.itemCapacity)
          .append(":liqCap=").append(Float.floatToIntBits(block.liquidCapacity));

        if (block instanceof Pump pump) {
            sb.append(":pump=").append(Float.floatToIntBits(pump.pumpAmount));
        }
        if (block instanceof PowerGenerator gen) {
            sb.append(":genProd=").append(Float.floatToIntBits(gen.powerProduction));
        }
        if (block instanceof PowerNode node) {
            sb.append(":nodeRange=").append(Float.floatToIntBits(node.laserRange))
              .append(":maxNodes=").append(node.maxNodes);
        }
        if (block instanceof GenericCrafter crafter) {
            sb.append(":craftTime=").append(Float.floatToIntBits(crafter.craftTime));
            if (crafter.outputItem != null) {
                sb.append(":outItem=").append(crafter.outputItem.item.name)
                  .append('x').append(crafter.outputItem.amount);
            }
            if (crafter.outputItems != null) {
                for (ItemStack stack : crafter.outputItems) {
                    sb.append(":outItems=").append(stack.item.name).append('x').append(stack.amount);
                }
            }
            if (crafter.outputLiquid != null) {
                sb.append(":outLiq=").append(crafter.outputLiquid.liquid.name)
                  .append('@').append(Float.floatToIntBits(crafter.outputLiquid.amount));
            }
            if (crafter.outputLiquids != null) {
                for (LiquidStack stack : crafter.outputLiquids) {
                    sb.append(":outLiqs=").append(stack.liquid.name)
                      .append('@').append(Float.floatToIntBits(stack.amount));
                }
            }
        }
        for (Consume c : block.consumers) {
            if (c instanceof ConsumePower cp) {
                sb.append(":consPow=").append(Float.floatToIntBits(cp.usage))
                  .append(',').append(Float.floatToIntBits(cp.capacity))
                  .append(',').append(cp.buffered);
            } else if (c instanceof ConsumeItems ci) {
                for (ItemStack st : ci.items) {
                    sb.append(":consItem=").append(st.item.name).append('x').append(st.amount);
                }
            } else if (c instanceof ConsumeLiquid cl) {
                sb.append(":consLiq=").append(cl.liquid.name)
                  .append('@').append(Float.floatToIntBits(cl.amount));
            } else if (c instanceof ConsumeLiquidFilter clf) {
                sb.append(":consLiqFilter=").append(Float.floatToIntBits(clf.amount));
            } else if (c instanceof ConsumeItemFilter) {
                sb.append(":consItemFilter");
            }
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
        if (team.core() == null) {
            throw new IllegalStateException("Failed to place coreShard for default team");
        }

        Tile pumpTile = Vars.world.tile(8, 12);
        Tile pipeTile = Vars.world.tile(9, 12);
        Tile crafterTile = Vars.world.tile(10, 12);
        Tile batteryTile = Vars.world.tile(10, 11);
        Tile generatorA = Vars.world.tile(10, 10);
        Tile generatorB = Vars.world.tile(9, 11);
        Tile pressTile = Vars.world.tile(14, 12);

        pumpTile.setFloor(Blocks.water.asFloor());
        pumpTile.setBlock(Blocks.mechanicalPump, team, 0);
        pipeTile.setBlock(Blocks.conduit, team, 0);
        crafterTile.setBlock(Blocks.cultivator, team, 0);
        batteryTile.setBlock(Blocks.battery, team, 0);
        generatorA.setBlock(Blocks.combustionGenerator, team, 0);
        generatorB.setBlock(Blocks.combustionGenerator, team, 0);
        pressTile.setBlock(Blocks.graphitePress, team, 0);

        GenericCrafterBuild crafter = (GenericCrafterBuild) crafterTile.build;
        GenericCrafterBuild press = (GenericCrafterBuild) pressTile.build;
        Building genABuild = generatorA.build;
        Building genBBuild = generatorB.build;

        Vars.logic.play();
        app.runPosted();

        long traceHash = FNV_OFFSET;
        int totalTicks = 0;

        // Stage 1: Starvation (60 ticks with no fuel in generators and no coal in graphitePress).
        for (int i = 0; i < 60; i++) {
            Vars.logic.update();
            app.runPosted();
            totalTicks++;
            traceHash = mixTick(traceHash, totalTicks, crafter, press, genABuild, genBBuild);
        }
        int waterMilli = Math.round(crafter.liquids.get(Liquids.water) * 1000f);
        if (waterMilli <= 0 || crafter.liquids.get(Liquids.water) > crafter.block.liquidCapacity
                || crafter.power.status != 0f || crafter.items.total() != 0 || crafter.progress != 0f) {
            throw new IllegalStateException("Expected cultivator power starvation during unfueled ticks");
        }
        if (press.items.get(Items.graphite) != 0 || press.progress != 0f) {
            throw new IllegalStateException("Expected graphitePress item starvation during unfueled ticks");
        }
        if (printRecords) {
            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_PRODUCTION_STARVED ticks=60 waterMilli=%d pods=0 graphite=0%n",
                waterMilli);
        }

        // Stage 2: Feed graphitePress 2 coal (1 craft) and fuel generators -> craft + power disconnect.
        supplyItem(press, press, Items.coal, 2);
        fuel(genABuild, crafter);
        fuel(genBBuild, crafter);
        int initialFuel = genABuild.items.get(Items.coal);
        boolean consumedFuel = false;
        int graphiteTick = 0;
        int first = 0;

        for (int elapsed = 1; elapsed <= 1200; elapsed++) {
            if (elapsed % 120 == 0) {
                fuel(genABuild, crafter);
                fuel(genBBuild, crafter);
            }
            Vars.logic.update();
            app.runPosted();
            totalTicks++;
            traceHash = mixTick(traceHash, totalTicks, crafter, press, genABuild, genBBuild);

            consumedFuel |= genABuild.items.get(Items.coal) < initialFuel;
            if (graphiteTick == 0 && press.items.get(Items.graphite) == 1) {
                graphiteTick = elapsed;
            }
            if (crafter.items.get(Items.sporePod) == 1) {
                first = elapsed;
                break;
            }
        }
        if (graphiteTick <= 0 || press.items.get(Items.graphite) != 1 || press.items.get(Items.coal) != 0) {
            throw new IllegalStateException(
                "Unexpected graphitePress state: graphiteTick=" + graphiteTick
                + " graphite=" + press.items.get(Items.graphite)
                + " coal=" + press.items.get(Items.coal));
        }
        if (first <= 0 || crafter.power.status <= 0f || !consumedFuel) {
            throw new IllegalStateException(
                "Expected sporePod + active generator consumption: first=" + first
                + " power=" + crafter.power.status);
        }

        // Remove battery to split power graph cleanly between generators and cultivator.
        batteryTile.remove();
        app.runPosted();
        if (genABuild.power.graph == crafter.power.graph || genBBuild.power.graph == crafter.power.graph) {
            throw new IllegalStateException("Expected battery removal to isolate generator and cultivator graphs");
        }
        float pausedProgress = crafter.progress;
        for (int i = 0; i < 60; i++) {
            Vars.logic.update();
            app.runPosted();
            totalTicks++;
            traceHash = mixTick(traceHash, totalTicks, crafter, press, genABuild, genBBuild);
        }
        if (crafter.power.status != 0f
                || crafter.items.get(Items.sporePod) != 1
                || crafter.progress != pausedProgress
                || crafter.liquids.get(Liquids.water) <= 0f
                || press.items.get(Items.graphite) != 1) {
            throw new IllegalStateException("Cultivator continued crafting across power disconnect");
        }
        if (printRecords) {
            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_PRODUCTION_DISCONNECT graphiteTick=%d first=%d paused=60 pods=1%n",
                graphiteTick,
                first);
        }

        // Stage 3: Reconnect battery -> verify merged graph, fill capacity, clamp.
        batteryTile.setBlock(Blocks.battery, team, 0);
        app.runPosted();
        if (genABuild.power.graph != crafter.power.graph || genBBuild.power.graph != crafter.power.graph) {
            throw new IllegalStateException("Expected battery reconnection to merge power graphs");
        }
        int capacity = crafter.block.itemCapacity;
        int filled = 0;
        for (int elapsed = 1; elapsed <= 5000; elapsed++) {
            if (elapsed % 120 == 1) {
                fuel(genABuild, crafter);
                fuel(genBBuild, crafter);
            }
            Vars.logic.update();
            app.runPosted();
            totalTicks++;
            traceHash = mixTick(traceHash, totalTicks, crafter, press, genABuild, genBBuild);

            if (crafter.items.get(Items.sporePod) == capacity) {
                filled = elapsed;
                break;
            }
        }
        if (filled <= 0) {
            throw new IllegalStateException("Reconnected cultivator never reached capacity");
        }
        float fullProgress = crafter.progress;
        for (int i = 0; i < 120; i++) {
            Vars.logic.update();
            app.runPosted();
            totalTicks++;
            traceHash = mixTick(traceHash, totalTicks, crafter, press, genABuild, genBBuild);
        }
        if (crafter.items.get(Items.sporePod) != capacity
                || crafter.progress != fullProgress
                || crafter.liquids.get(Liquids.water) > crafter.block.liquidCapacity
                || crafter.shouldConsume()) {
            throw new IllegalStateException("Cultivator exceeded item/liquid capacity after filling");
        }
        if (printRecords) {
            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_PRODUCTION_FULL filled=%d held=120 pods=%d waterCapOk=true%n",
                filled,
                capacity);
        }

        return new ScenarioResult(totalTicks, traceHash);
    }

    private static long mixTick(
            long hash,
            int tick,
            GenericCrafterBuild crafter,
            GenericCrafterBuild press,
            Building genA,
            Building genB) {
        int waterMilli = Math.round(crafter.liquids.get(Liquids.water) * 1000f);
        int pods = crafter.items.get(Items.sporePod);
        int graphite = press.items.get(Items.graphite);
        int genACoal = genA.items.get(Items.coal);
        int genBCoal = genB.items.get(Items.coal);
        int powerStatusMilli = Math.round(crafter.power.status * 1000f);

        hash = fnvMix(hash, tick);
        hash = fnvMix(hash, waterMilli);
        hash = fnvMix(hash, pods);
        hash = fnvMix(hash, graphite);
        hash = fnvMix(hash, genACoal);
        hash = fnvMix(hash, genBCoal);
        hash = fnvMix(hash, powerStatusMilli);
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

    private static void supplyItem(Building target, Building source, mindustry.type.Item item, int count) {
        for (int i = 0; i < count && target.acceptItem(source, item); i++) {
            target.handleItem(source, item);
        }
    }

    private static void fuel(Building generator, Building source) {
        int room = generator.block.itemCapacity - generator.items.get(Items.coal);
        supplyItem(generator, source, Items.coal, room);
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
