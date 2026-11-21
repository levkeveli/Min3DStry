import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.Settings;
import arc.files.Fi;
import arc.math.Mathf;
import arc.mock.MockAudio;
import arc.mock.MockFiles;
import arc.mock.MockGraphics;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.BaseRegistry;
import mindustry.ai.BlockIndexer;
import mindustry.ai.ControlPathfinder;
import mindustry.ai.Pathfinder;
import mindustry.ai.Pathfinder.Flowfield;
import mindustry.ai.WaveSpawner;
import mindustry.async.AsyncCore;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.core.NetServer;
import mindustry.core.World;
import mindustry.entities.EntityCollisions;
import mindustry.entities.units.AIController;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.CacheLayer;
import mindustry.io.JsonIO;
import mindustry.logic.GlobalVars;
import mindustry.net.Net;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.ContinuousTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild;
import mindustry.world.blocks.defense.turrets.LaserTurret;
import mindustry.world.blocks.defense.turrets.PointDefenseTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;
import mindustry.world.blocks.defense.turrets.ReloadTurret;
import mindustry.world.blocks.defense.turrets.TractorBeamTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

/**
 * Host-only verification baseline for upstream Mindustry v160.8
 * (19a0f40d44514f4802a8b79770456e7e32e34da0) turret combat, multi-turret
 * damage/power behavior, flowfield pathfinding with dynamic obstacle rerouting,
 * and repeated-run determinism.
 *
 * Advances #30 using a temporary settings directory removed on exit.
 */
public final class UpstreamCombatSimulationBaseline {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public static void main(String[] args) throws Exception {
        final Path tempDataDir = Files.createTempDirectory("min3dstry-combat-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(tempDataDir)));

        ProbeApplication app = initializeApp(tempDataDir);
        verifyTurretCatalog();

        ScenarioResult run1 = runCombatAndPathfindingScenario(app, true);
        ScenarioResult run2 = runCombatAndPathfindingScenario(app, false);
        if (run1.totalTicks != run2.totalTicks || run1.traceHash != run2.traceHash) {
            throw new IllegalStateException(
                String.format(
                    Locale.ROOT,
                    "Determinism mismatch: run1=(%d, 0x%016x) run2=(%d, 0x%016x)",
                    run1.totalTicks, run1.traceHash, run2.totalTicks, run2.traceHash));
        }

        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_COMBAT_ORDER runs=2 ticks=%d trace_hash=0x%016x%n",
            run1.totalTicks,
            run1.traceHash);
    }

    private static ProbeApplication initializeApp(Path tempDataDir) {
        CacheLayer.init();
        ProbeApplication app = new ProbeApplication();
        Core.app = app;
        Core.audio = new MockAudio();
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

    private static void verifyTurretCatalog() throws Exception {
        Block[] turrets = {
            Blocks.duo,
            Blocks.scatter,
            Blocks.scorch,
            Blocks.hail,
            Blocks.wave,
            Blocks.lancer,
            Blocks.arc,
            Blocks.parallax,
            Blocks.swarmer,
            Blocks.salvo,
            Blocks.segment,
            Blocks.tsunami,
            Blocks.fuse,
            Blocks.ripple,
            Blocks.cyclone,
            Blocks.foreshadow,
            Blocks.spectre,
            Blocks.meltdown,
        };

        StringBuilder manifest = new StringBuilder();
        for (Block block : turrets) {
            if (!(block instanceof BaseTurret bt)) {
                throw new IllegalStateException("Expected BaseTurret subclass for " + block);
            }
            manifest.append(describeTurret(bt)).append('\n');
        }

        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(manifest.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }

        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_COMBAT_TURRETS blocks=%d manifest_sha256=%s%n",
            turrets.length,
            hex.toString());
    }

    private static String describeTurret(BaseTurret bt) {
        String className = bt.getClass().getSimpleName();
        StringBuilder sb = new StringBuilder();
        sb.append(bt.id)
          .append(':').append(bt.name)
          .append(":type=").append(className)
          .append(":size=").append(bt.size)
          .append(":health=").append(bt.health)
          .append(":range=").append(Float.floatToIntBits(bt.range));
        if (bt instanceof ReloadTurret rt) {
            sb.append(":reload=").append(Float.floatToIntBits(rt.reload));
        }
        if (bt instanceof Turret t) {
            sb.append(":maxAmmo=").append(t.maxAmmo)
              .append(":air=").append(t.targetAir)
              .append(":ground=").append(t.targetGround);
        } else if (bt instanceof PointDefenseTurret p) {
            sb.append(":pdmg=").append(Float.floatToIntBits(p.bulletDamage));
        } else if (bt instanceof TractorBeamTurret tb) {
            sb.append(":tdmg=").append(Float.floatToIntBits(tb.damage))
              .append(":air=").append(tb.targetAir)
              .append(":ground=").append(tb.targetGround);
        }
        return sb.toString();
    }

    private static ScenarioResult runCombatAndPathfindingScenario(
            ProbeApplication app, boolean printSummary) throws InterruptedException {
        try {
            Events.clear();
            Groups.clear();
            Groups.init();
            Time.clear();
            Mathf.rand.setSeed(160_008L);

            Vars.state = new GameState();
            Vars.state.rules.attackMode = true;
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
            Vars.pathfinder = new Pathfinder();
            Vars.controlPath = new ControlPathfinder();
            Vars.asyncCore = new AsyncCore();

            Vars.world.loadGenerator(32, 32, tiles -> {
                for (int tx = 0; tx < tiles.width; tx++) {
                    for (int ty = 0; ty < tiles.height; ty++) {
                        tiles.set(tx, ty, new Tile(tx, ty, Blocks.stone, Blocks.air, Blocks.air));
                    }
                }
            });
            app.runPosted();

            Team team = Vars.state.rules.defaultTeam;
            Team enemy = Vars.state.rules.waveTeam;
            Vars.world.tile(6, 16).setBlock(Blocks.coreShard, team, 0);
            CoreBuild core = team.core();
            if (core == null) {
                throw new IllegalStateException("Failed to place coreShard at (6, 16)");
            }
            Vars.logic.play();
            app.runPosted();

            long traceHash = FNV_OFFSET;
            int totalTicks = 0;

            // Phase 1a: Duo conveyor-fed copper ammo duel against stationary Dagger.
            Tile duoTile = Vars.world.tile(10, 10);
            Tile duoBeltTile = Vars.world.tile(9, 10);
            Tile duoSourceTile = Vars.world.tile(8, 10);
            duoTile.setBlock(Blocks.duo, team, 0);
            duoBeltTile.setBlock(Blocks.conveyor, team, 0);
            duoSourceTile.setBlock(Blocks.junction, team, 0);
            ItemTurretBuild duo = (ItemTurretBuild) duoTile.build;
            Building duoBelt = duoBeltTile.build;

            Unit duoTarget = createStationaryDagger(enemy, 14, 10);
            float duoStartHp = duoTarget.health;
            int supplied = 0;
            int duoDelivery = 0;
            int duoShot = 0;
            int duoHit = 0;
            int duoDestroyed = 0;

            for (int tick = 1; tick <= 600; tick++) {
                if (supplied < 20 && duoBelt.acceptItem(duoSourceTile.build, Items.copper)) {
                    duoBelt.handleItem(duoSourceTile.build, Items.copper);
                    supplied++;
                }
                stepSimulation(app);
                totalTicks++;
                if (duoDelivery == 0 && duo.totalAmmo > 0) duoDelivery = tick;
                if (duoShot == 0 && Groups.bullet.size() > 0) duoShot = tick;
                if (duoHit == 0 && duoTarget.health < duoStartHp) duoHit = tick;
                traceHash = fnvMix(traceHash, totalTicks);
                traceHash = fnvMix(traceHash, duo.totalAmmo);
                traceHash = fnvMix(traceHash, Math.round(duoTarget.health * 100f));
                if (duoTarget.dead || !duoTarget.isAdded()) {
                    duoDestroyed = tick;
                    break;
                }
            }
            if (duoDelivery == 0 || duoShot == 0 || duoHit == 0 || duoDestroyed == 0) {
                throw new IllegalStateException(
                    "Duo duel incomplete: " + duoDelivery + "/" + duoShot + "/" + duoHit + "/" + duoDestroyed);
            }

            duoTile.setBlock(Blocks.air);
            duoBeltTile.setBlock(Blocks.air);
            duoSourceTile.setBlock(Blocks.air);
            Groups.bullet.clear();

            // Phase 1b: Hail graphite artillery splash across two adjacent Daggers.
            Tile hailTile = Vars.world.tile(10, 22);
            hailTile.setBlock(Blocks.hail, team, 0);
            ItemTurretBuild hail = (ItemTurretBuild) hailTile.build;
            for (int i = 0; i < 10 && hail.acceptItem(hail, Items.graphite); i++) {
                hail.handleItem(hail, Items.graphite);
            }
            Unit hailTarget1 = createStationaryDagger(enemy, 18, 22);
            Unit hailTarget2 = createStationaryDagger(enemy, 18, 23);
            float h1Start = hailTarget1.health;
            float h2Start = hailTarget2.health;
            int hailSplashTick = 0;
            for (int tick = 1; tick <= 360; tick++) {
                stepSimulation(app);
                totalTicks++;
                traceHash = fnvMix(traceHash, totalTicks);
                traceHash = fnvMix(traceHash, Math.round(hailTarget1.health * 100f));
                traceHash = fnvMix(traceHash, Math.round(hailTarget2.health * 100f));
                if (hailTarget1.health < h1Start && hailTarget2.health < h2Start) {
                    hailSplashTick = tick;
                    break;
                }
            }
            if (hailSplashTick == 0) {
                throw new IllegalStateException(
                    "Hail did not splash both adjacent Daggers: hp1="
                    + hailTarget1.health + " hp2=" + hailTarget2.health);
            }

            hailTile.setBlock(Blocks.air);
            hailTarget1.remove();
            hailTarget2.remove();
            Groups.bullet.clear();

            // Phase 1c: Lancer powered by full battery firing piercing laser at stationary Dagger.
            Tile batteryTile = Vars.world.tile(9, 26);
            Tile lancerTile = Vars.world.tile(10, 26);
            batteryTile.setBlock(Blocks.battery, team, 0);
            lancerTile.setBlock(Blocks.lancer, team, 0);
            Building battery = batteryTile.build;
            TurretBuild lancer = (TurretBuild) lancerTile.build;
            battery.power.status = 1f;

            Unit lancerTarget = createStationaryDagger(enemy, 15, 26);
            float lancerTargetStartHp = lancerTarget.health;
            int lancerHitTick = 0;
            for (int tick = 1; tick <= 240; tick++) {
                stepSimulation(app);
                totalTicks++;
                traceHash = fnvMix(traceHash, totalTicks);
                traceHash = fnvMix(traceHash, Math.round(lancerTarget.health * 100f));
                traceHash = fnvMix(traceHash, Float.floatToIntBits(battery.power.status));
                if (lancerTarget.health < lancerTargetStartHp && battery.power.status < 1f) {
                    lancerHitTick = tick;
                    break;
                }
            }
            if (lancerHitTick == 0) {
                throw new IllegalStateException(
                    "Lancer failed to discharge battery and hit target: hp="
                    + lancerTarget.health + " batt=" + battery.power.status
                    + " eff=" + lancer.efficiency);
            }

            lancerTile.setBlock(Blocks.air);
            batteryTile.setBlock(Blocks.air);
            lancerTarget.remove();
            Groups.bullet.clear();

            if (printSummary) {
                System.out.printf(
                    Locale.ROOT,
                    "UPSTREAM_COMBAT_DUEL duo=%d/%d/%d/%d hailSplash=2@%d lancerHit=%d%n",
                    duoDelivery, duoShot, duoHit, duoDestroyed, hailSplashTick, lancerHitTick);
            }

            // Phase 2: Pathfinder flowfield and dynamic copper-wall barrier rerouting.
            Flowfield field = Vars.pathfinder.getField(enemy, Pathfinder.costGround, Pathfinder.fieldCore);
            int[] openWeights = waitForStableWeights(app, field, null);
            long openHash = hashInts(openWeights);

            for (int wy = 14; wy <= 18; wy++) {
                Tile wall = Vars.world.tile(16, wy);
                wall.setBlock(Blocks.copperWall, team, 0);
                Vars.pathfinder.updateTile(wall);
            }
            // Exceed Pathfinder.refreshIntervalMs (100ms) so afterGameUpdate schedules dirty rebuild.
            Thread.sleep(130);
            stepSimulation(app);

            int[] blockedWeights = waitForStableWeights(app, field, openWeights);
            long blockedHash = hashInts(blockedWeights);
            if (blockedHash == openHash) {
                throw new IllegalStateException("Flowfield did not change after placing copperWall barrier");
            }

            Tile spawnTile = Vars.world.tile(20, 16);
            Unit runner = UnitTypes.dagger.create(enemy);
            runner.set(spawnTile.worldx(), spawnTile.worldy());
            runner.add();

            float startDist = Mathf.dst(runner.x, runner.y, core.x, core.y);
            float minDist = startDist;
            int maxAbsDy = 0;

            for (int tick = 1; tick <= 360; tick++) {
                stepSimulation(app);
                totalTicks++;
                int dy = Math.abs(runner.tileY() - 16);
                if (dy > maxAbsDy) {
                    maxAbsDy = dy;
                }
                float dist = Mathf.dst(runner.x, runner.y, core.x, core.y);
                if (dist < minDist) {
                    minDist = dist;
                }
                traceHash = fnvMix(traceHash, totalTicks);
                traceHash = fnvMix(traceHash, runner.tileX());
                traceHash = fnvMix(traceHash, runner.tileY());
            }

            int endX = runner.tileX();
            int endY = runner.tileY();
            float endDist = Mathf.dst(runner.x, runner.y, core.x, core.y);
            if (maxAbsDy < 2 || endDist >= startDist || endX >= 20) {
                throw new IllegalStateException(
                    "Ground unit did not reroute around copperWall barrier: end="
                    + endX + "," + endY + " maxDy=" + maxAbsDy
                    + " dist=" + startDist + "->" + endDist);
            }

            if (printSummary) {
                System.out.printf(
                    Locale.ROOT,
                    "UPSTREAM_COMBAT_PATHFINDING openHash=%016x blockedHash=%016x start=20,16 end=%d,%d maxDy=%d dist=%d->%d%n",
                    openHash, blockedHash, endX, endY, maxAbsDy, Math.round(startDist), Math.round(endDist));
            }

            return new ScenarioResult(totalTicks, traceHash);
        } finally {
            Events.fire(new ResetEvent());
        }
    }

    private static Unit createStationaryDagger(Team team, int tileX, int tileY) {
        Unit unit = UnitTypes.dagger.create(team);
        unit.controller(new AIController() {
            @Override
            public void updateUnit() {}
        });
        Tile tile = Vars.world.tile(tileX, tileY);
        unit.set(tile.worldx(), tile.worldy());
        unit.add();
        return unit;
    }

    private static void stepSimulation(ProbeApplication app) {
        Vars.asyncCore.begin();
        Vars.logic.update();
        Vars.asyncCore.end();
        app.runPosted();
    }

    private static int[] waitForStableWeights(
            ProbeApplication app, Flowfield field, int[] previousWeights) throws InterruptedException {
        int[] last = null;
        int stableCount = 0;
        for (int poll = 0; poll < 300; poll++) {
            stepSimulation(app);
            if (field.hasCompleteWeights()) {
                int[] snapshot = Arrays.copyOf(field.completeWeights, field.completeWeights.length);
                boolean changedFromPrevious = previousWeights == null || !Arrays.equals(snapshot, previousWeights);
                if (changedFromPrevious) {
                    if (last != null && Arrays.equals(snapshot, last)) {
                        stableCount++;
                        if (stableCount >= 3) {
                            return snapshot;
                        }
                    } else {
                        last = snapshot;
                        stableCount = 1;
                    }
                }
            }
            Thread.sleep(16);
        }
        throw new IllegalStateException("Flowfield did not converge cleanly");
    }

    private static long hashInts(int[] values) {
        long hash = FNV_OFFSET;
        for (int value : values) {
            hash = fnvMix(hash, value);
        }
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
