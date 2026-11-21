import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.Settings;
import arc.assets.AssetDescriptor;
import arc.assets.AssetLoaderParameters;
import arc.assets.AssetManager;
import arc.audio.Audio;
import arc.audio.AudioBus;
import arc.audio.AudioFilter;
import arc.audio.AudioSource;
import arc.audio.Music;
import arc.audio.RandomSound;
import arc.audio.Sound;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Camera;
import arc.math.Mathf;
import arc.mock.MockFiles;
import arc.mock.MockGraphics;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import mindustry.Vars;
import mindustry.ai.BaseRegistry;
import mindustry.ai.BlockIndexer;
import mindustry.ai.WaveSpawner;
import mindustry.audio.SoundLoop;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.core.NetServer;
import mindustry.core.World;
import mindustry.entities.EntityCollisions;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.AIController;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.game.Team;
import mindustry.game.Universe;
import mindustry.gen.Musics;
import mindustry.gen.Sounds;
import mindustry.graphics.CacheLayer;
import mindustry.io.JsonIO;
import mindustry.logic.GlobalVars;
import mindustry.net.Net;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild;
import mindustry.world.blocks.defense.turrets.PowerTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.distribution.Conveyor.ConveyorBuild;
import mindustry.world.blocks.power.Battery.BatteryBuild;
import mindustry.world.blocks.production.Drill.DrillBuild;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Host-only verification baseline for upstream Mindustry v160.8
 * (19a0f40d44514f4802a8b79770456e7e32e34da0) audio registry loading
 * (Sounds.load / Musics.load), content audio bindings across blocks/units/bullets,
 * Arc positional falloff/panning, SoundLoop ramping, and deterministic
 * simulation audio triggers.
 *
 * Advances #29 using a temporary settings directory removed on exit.
 */
public final class UpstreamAudioBaseline {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException(
                "Usage: UpstreamAudioBaseline [path/to/upstream-audio-map.json]");
        }
        final Path audioMapPath = args.length == 1
            ? Path.of(args[0])
            : Path.of("audio/upstream-audio-map.json");
        final Path tempDataDir = Files.createTempDirectory("min3dstry-audio-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(tempDataDir)));

        DirectAssetManager assets = new DirectAssetManager();
        RecordingAudio audio = new RecordingAudio();
        ProbeApplication app = initializeApp(tempDataDir, assets, audio);

        try {
            verifyRegistry(assets);
            verifyContentBindings(assets, audioMapPath);
            verifyPositionalAndLoops(audio);

            ScenarioResult run1 = runSimulationAudioScenario(app, audio, true);
            ScenarioResult run2 = runSimulationAudioScenario(app, audio, false);
            if (run1.totalTicks != run2.totalTicks
                    || run1.traceHash != run2.traceHash
                    || run1.eventCount != run2.eventCount) {
                throw new IllegalStateException(
                    String.format(
                        Locale.ROOT,
                        "Determinism mismatch: run1=(ticks=%d events=%d hash=0x%016x) run2=(ticks=%d events=%d hash=0x%016x)",
                        run1.totalTicks, run1.eventCount, run1.traceHash,
                        run2.totalTicks, run2.eventCount, run2.traceHash));
            }

            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_AUDIO_ORDER runs=2 ticks=%d events=%d trace_hash=0x%016x%n",
                run1.totalTicks,
                run1.eventCount,
                run1.traceHash);
        } finally {
            assets.dispose();
        }
    }

    private static ProbeApplication initializeApp(
            Path tempDataDir, DirectAssetManager assets, RecordingAudio audio) {
        CacheLayer.init();
        ProbeApplication app = new ProbeApplication();
        Core.app = app;
        Core.assets = assets;
        Core.audio = audio;
        Core.camera = new Camera();
        Core.camera.resize(400f, 240f);
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

        // Load Sound/Music registries before content creation so Blocks/UnitTypes/Bullets
        // bind directly to the registered RecordingSound instances.
        Sounds.load();
        Musics.load();
        assets.finishLoadingAll();

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

    private static void verifyRegistry(DirectAssetManager assets) throws Exception {
        if (Sounds.getSoundId(Sounds.none) != -1 || Sounds.getSoundId(Sounds.unset) != -1) {
            throw new IllegalStateException("Expected Sounds.none and Sounds.unset to map to -1");
        }

        List<String> records = new ArrayList<>();
        int soundCount = 0;
        int uiCount = 0;
        for (int id = 0; id < 1000; id++) {
            Sound s = Sounds.getSound(id);
            if (s == null || (id >= 205 && Sounds.getSoundId(s) == -1)) {
                if (id < 205) {
                    throw new IllegalStateException("Missing sound id " + id);
                }
                break;
            }
            if (!(s instanceof RecordingSound rs)) {
                throw new IllegalStateException("Sound id " + id + " was not loaded via asset descriptor");
            }
            if (Sounds.getSoundId(s) != id) {
                throw new IllegalStateException("Round-trip sound ID mismatch at " + id);
            }
            String fileName = assets.getAssetFileName(s);
            if (!rs.assetPath.equals(fileName)) {
                throw new IllegalStateException("Asset filename mismatch for id " + id + ": " + fileName);
            }
            if (rs.assetPath.startsWith("sounds/ui/")) {
                uiCount++;
            }
            records.add("sound\t" + id + "\t" + rs.assetPath);
            soundCount++;
        }

        Music[] musics = {
            Musics.boss1, Musics.boss2, Musics.editor, Musics.fine,
            Musics.game1, Musics.game2, Musics.game3, Musics.game4,
            Musics.game5, Musics.game6, Musics.game7, Musics.game8,
            Musics.game9, Musics.land, Musics.launch, Musics.menu
        };
        for (int i = 0; i < musics.length; i++) {
            if (!(musics[i] instanceof RecordingMusic rm)) {
                throw new IllegalStateException("Music slot " + i + " was not loaded");
            }
            records.add("music\t" + i + "\t" + rm.assetPath);
        }

        if (soundCount != 205 || musics.length != 16 || uiCount != 7) {
            throw new IllegalStateException(
                "Unexpected registry counts: sounds=" + soundCount + " musics=" + musics.length + " uiSounds=" + uiCount);
        }

        String digest = sha256(String.join("\n", records) + "\n");
        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_AUDIO_REGISTRY sounds=%d musics=%d uiSounds=%d manifest_sha256=%s%n",
            soundCount,
            musics.length,
            uiCount,
            digest);
    }

    private static void verifyContentBindings(
            DirectAssetManager assets, Path audioMapPath) throws Exception {
        if (Sounds.uiButton == null || Sounds.uiUnlock == null || Sounds.waveSpawn == null) {
            throw new IllegalStateException("UI/wave sounds missing after registry load");
        }
        if (Blocks.duo.placeSound != Sounds.blockPlace1 || Blocks.duo.breakSound != Sounds.blockBreak1) {
            throw new IllegalStateException("Unexpected Duo place/break bindings");
        }
        if (((ItemTurret) Blocks.duo).shootSound != Sounds.shootDuo) {
            throw new IllegalStateException("Unexpected Duo shootSound binding");
        }
        ItemTurret hail = (ItemTurret) Blocks.hail;
        BulletType hailGraphite = hail.ammoTypes.get(Items.graphite);
        if (hail.shootSound != Sounds.shootArtillerySmall
                || hailGraphite == null
                || hailGraphite.hitSound != Sounds.explosionArtillery) {
            throw new IllegalStateException("Unexpected Hail audio bindings");
        }
        PowerTurret lancer = (PowerTurret) Blocks.lancer;
        if (lancer.chargeSound != Sounds.chargeLancer || lancer.shootSound != Sounds.shootLancer) {
            throw new IllegalStateException("Unexpected Lancer audio bindings");
        }
        if (Blocks.conveyor.ambientSound != Sounds.loopConveyor
                || Blocks.conveyor.placeSound != Sounds.blockPlace1
                || Blocks.conveyor.breakSound != Sounds.blockBreak1) {
            throw new IllegalStateException("Unexpected Conveyor audio bindings");
        }
        if (Blocks.mechanicalDrill.ambientSound != Sounds.loopDrill
                || Blocks.mechanicalDrill.placeSound != Sounds.blockPlace2
                || Blocks.mechanicalDrill.breakSound != Sounds.blockBreak2) {
            throw new IllegalStateException("Unexpected MechanicalDrill audio bindings");
        }
        if (UnitTypes.dagger.deathSound != Sounds.unitExplode1
                || UnitTypes.dagger.stepSound != Sounds.mechStepSmall) {
            throw new IllegalStateException("Unexpected Dagger audio bindings");
        }

        List<String> lines = new ArrayList<>();
        Seq<Block> blocks = new Seq<>(Vars.content.blocks());
        blocks.sort(Comparator.comparingInt(b -> b.id));
        for (Block block : blocks) {
            StringBuilder row = new StringBuilder();
            row.append("block\t").append(block.id).append('\t').append(block.name)
                .append("\tplace=").append(describeSound(block.placeSound))
                .append("\tbreak=").append(describeSound(block.breakSound))
                .append("\tdestroy=").append(describeSound(block.destroySound))
                .append("\tambient=").append(describeSound(block.ambientSound))
                .append(String.format(Locale.ROOT, "@%.4f", block.ambientSoundVolume))
                .append("\tconfig=").append(describeSound(block.configureSound));
            if (block instanceof Turret t) {
                row.append("\tshoot=").append(describeSound(t.shootSound))
                    .append(String.format(Locale.ROOT, "@%.4f", t.shootSoundVolume))
                    .append("\tcharge=").append(describeSound(t.chargeSound))
                    .append("\tloop=").append(describeSound(t.loopSound))
                    .append(String.format(Locale.ROOT, "@%.4f", t.loopSoundVolume));
            }
            lines.add(row.toString());
        }

        Seq<UnitType> units = new Seq<>(Vars.content.units());
        units.sort(Comparator.comparingInt(u -> u.id));
        for (UnitType unit : units) {
            StringBuilder row = new StringBuilder();
            row.append("unit\t").append(unit.id).append('\t').append(unit.name)
                .append("\tdeath=").append(describeSound(unit.deathSound))
                .append(String.format(Locale.ROOT, "@%.4f", unit.deathSoundVolume))
                .append("\twreck=").append(describeSound(unit.wreckSound))
                .append("\tloop=").append(describeSound(unit.loopSound))
                .append("\tstep=").append(describeSound(unit.stepSound))
                .append("\ttank=").append(describeSound(unit.tankMoveSound))
                .append("\tmove=").append(describeSound(unit.moveSound))
                .append("\tmine=").append(describeSound(unit.mineSound));
            for (int w = 0; w < unit.weapons.size; w++) {
                Weapon weapon = unit.weapons.get(w);
                row.append("\tw").append(w)
                    .append('=').append(describeSound(weapon.shootSound))
                    .append('/').append(describeSound(weapon.chargeSound));
            }
            lines.add(row.toString());
        }

        Seq<BulletType> bullets = new Seq<>(Vars.content.bullets());
        bullets.sort(Comparator.comparingInt(b -> b.id));
        for (BulletType bullet : bullets) {
            lines.add(String.format(
                Locale.ROOT,
                "bullet\t%d\tshoot=%s\thit=%s@%.4f\tdespawn=%s\theal=%s",
                bullet.id,
                describeSound(bullet.shootSound),
                describeSound(bullet.hitSound),
                bullet.hitSoundVolume,
                describeSound(bullet.despawnSound),
                describeSound(bullet.healSound)));
        }

        int mappedCount = verifyAudioMapBindings(assets, audioMapPath);
        String digest = sha256(String.join("\n", lines) + "\n");
        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_AUDIO_BINDINGS blocks=%d units=%d bullets=%d mapped=%d manifest_sha256=%s%n",
            blocks.size,
            units.size,
            bullets.size,
            mappedCount,
            digest);
    }

    private static int verifyAudioMapBindings(DirectAssetManager assets, Path audioMapPath)
            throws IOException {
        if (!Files.isRegularFile(audioMapPath)) {
            throw new IllegalStateException("Missing audio map file: " + audioMapPath);
        }
        JsonValue root = new JsonReader().parse(Files.readString(audioMapPath, StandardCharsets.UTF_8));
        if (!"19a0f40d44514f4802a8b79770456e7e32e34da0".equals(root.getString("upstream_commit", ""))) {
            throw new IllegalStateException(
                "Unexpected upstream_commit in audio map: "
                    + root.getString("upstream_commit", "<missing>"));
        }
        JsonValue entries = root.require("entries");
        if (!entries.isArray() || entries.size == 0) {
            throw new IllegalStateException("Audio map entries must be a non-empty array");
        }

        Map<String, Sound> expectedMapped = new LinkedHashMap<>();
        expectedMapped.put("ui.button", Sounds.uiButton);
        expectedMapped.put("ui.unlock", Sounds.uiUnlock);
        expectedMapped.put("wave.spawn", Sounds.waveSpawn);
        expectedMapped.put("shoot.duo", ((ItemTurret) Blocks.duo).shootSound);
        expectedMapped.put("enemy.kill", UnitTypes.dagger.deathSound);
        expectedMapped.put("conveyor.loop", Blocks.conveyor.ambientSound);
        expectedMapped.put("drill.loop", Blocks.mechanicalDrill.ambientSound);
        expectedMapped.put("block.place", Blocks.conveyor.placeSound);
        expectedMapped.put("block.break", Blocks.conveyor.breakSound);

        Set<String> seenIds = new HashSet<>();
        for (JsonValue entry = entries.child; entry != null; entry = entry.next) {
            String id = entry.getString("id");
            String source = entry.getString("source");
            if (!seenIds.add(id)) {
                throw new IllegalStateException("Duplicate entry id in audio map: " + id);
            }
            if (!source.startsWith("core/assets/")) {
                throw new IllegalStateException(
                    "Audio map entry " + id + " has non-core/assets source: " + source);
            }
            String assetPath = source.substring("core/assets/".length());
            Sound loaded = assets.getOrNull(assetPath, Sound.class);
            if (!(loaded instanceof RecordingSound rs)
                    || !rs.assetPath.equals(assetPath)
                    || Sounds.getSoundId(loaded) < 0) {
                throw new IllegalStateException(
                    "Audio map entry " + id + " path is not loaded in Sounds registry: " + assetPath);
            }
            Sound expected = expectedMapped.get(id);
            if (expected == null) {
                throw new IllegalStateException("Unrecognized/unverified audio map entry id: " + id);
            }
            if (loaded != expected) {
                throw new IllegalStateException(
                    "Audio map entry " + id + " (" + assetPath + ") does not match runtime binding");
            }
        }
        if (!seenIds.equals(expectedMapped.keySet())) {
            throw new IllegalStateException(
                "Audio map entries mismatch expected set: seen="
                    + seenIds
                    + " expected="
                    + expectedMapped.keySet());
        }
        return seenIds.size();
    }

    private static void verifyPositionalAndLoops(RecordingAudio audio) {
        audio.resetLog();
        Core.camera.resize(400f, 240f);
        Core.camera.position.set(256f, 256f);
        Core.camera.update();

        float centerFalloff = Sounds.shootDuo.calcFalloff(256f, 256f);
        float centerPan = Sounds.shootDuo.calcPan(256f, 256f);
        float leftPan = Sounds.shootDuo.calcPan(156f, 256f);
        float leftVol = Sounds.shootDuo.calcVolume(156f, 256f);
        float rightPan = Sounds.shootDuo.calcPan(456f, 256f);
        float rightVol = Sounds.shootDuo.calcVolume(456f, 256f);
        int farResult = Sounds.shootDuo.at(5000f, 5000f);

        if (Math.abs(centerFalloff - 1f) > 1e-5f
                || Math.abs(centerPan) > 1e-5f
                || Math.abs(leftPan + 0.5f) > 1e-5f
                || Math.abs(leftVol - 1f) > 1e-5f
                || Math.abs(rightPan - 0.9f) > 1e-5f
                || Math.abs(rightVol - 0.4f) > 1e-4f
                || farResult != -1) {
            throw new IllegalStateException(
                String.format(
                    Locale.ROOT,
                    "Unexpected positional audio metrics center=(%.3f,%.3f) left=(%.3f,%.3f) right=(%.3f,%.3f) far=%d",
                    centerFalloff, centerPan, leftVol, leftPan, rightVol, rightPan, farResult));
        }

        // Exercise SoundLoop fade-in (start at 0 + 20 steps of fadeSpeed=0.05) and 20-step fade-out.
        Time.setDeltaProvider(() -> 1f);
        Time.delta = 1f;
        SoundLoop loop = new SoundLoop(Sounds.loopDrill, 0.019f);
        RecordingAudio.VoiceState state = null;
        float prevVol = -1f;
        int fadeInSteps = 0;
        boolean midRampChecked = false;
        for (int i = 0; i < 30; i++) {
            loop.update(156f, 256f, true);
            fadeInSteps++;
            if (fadeInSteps == 1) {
                state = audio.singleActiveLoop(Sounds.loopDrill);
                if (Math.abs(state.volume) > 1e-6f || Math.abs(state.pan + 0.5f) > 1e-4f) {
                    throw new IllegalStateException(
                        "Unexpected initial loop start state: pan=" + state.pan + " vol=" + state.volume);
                }
            } else {
                if (state.volume <= prevVol) {
                    throw new IllegalStateException(
                        "Expected monotonic fade-in increase at step " + fadeInSteps
                            + ": prev=" + prevVol + " cur=" + state.volume);
                }
                if (fadeInSteps == 11) {
                    if (Math.abs(state.volume - 0.5f * 0.019f) > 1e-4f) {
                        throw new IllegalStateException(
                            "Unexpected fade-in midpoint volume at step 11: " + state.volume);
                    }
                    midRampChecked = true;
                }
            }
            prevVol = state.volume;
            if (Math.abs(state.volume - 0.019f) <= 1e-4f) {
                break;
            }
        }
        if (state == null || !midRampChecked || fadeInSteps != 21
                || Math.abs(state.pan + 0.5f) > 1e-4f
                || Math.abs(state.volume - 0.019f) > 1e-4f) {
            throw new IllegalStateException(
                "Unexpected loop fade-in state: steps=" + fadeInSteps
                    + " mid=" + midRampChecked
                    + (state == null ? " state=null" : " pan=" + state.pan + " vol=" + state.volume));
        }

        // Pan flips as camera passes / source moves east of camera.
        loop.update(356f, 256f, true);
        if (Math.abs(state.pan - 0.5f) > 1e-4f) {
            throw new IllegalStateException("Expected loop pan to flip to +0.5, got " + state.pan);
        }

        int fadeOutSteps = 0;
        for (int i = 0; i < 25; i++) {
            loop.update(356f, 256f, false);
            fadeOutSteps++;
            if (!audio.isPlaying(state.voiceId)) {
                break;
            }
        }
        if (audio.isPlaying(state.voiceId) || fadeOutSteps != 20) {
            throw new IllegalStateException("Unexpected SoundLoop fade-out step count: " + fadeOutSteps);
        }

        System.out.printf(
            Locale.ROOT,
            "UPSTREAM_AUDIO_POSITIONAL panNeg=%.3f panPos=%.3f farCulled=%s loopFadeIn=%d loopFadeOut=%d%n",
            leftPan,
            rightPan,
            farResult == -1,
            fadeInSteps,
            fadeOutSteps);
    }

    private static ScenarioResult runSimulationAudioScenario(
            ProbeApplication app, RecordingAudio audio, boolean report) {
        audio.resetLog();
        Time.clear();
        Time.setDeltaProvider(() -> 1f);
        Time.delta = 1f;
        app.clearPosted();
        Vars.state.tick = 0f;
        Vars.state.updateId = 0L;
        Mathf.rand.setSeed(160_008L);

        Events.clear();
        Groups.clear();
        Groups.init();
        Vars.state = new GameState();
        Vars.world = new World();
        Vars.indexer = new BlockIndexer();
        Vars.spawner = new WaveSpawner();
        Vars.world.loadGenerator(32, 32, tiles -> {
            for (int tx = 0; tx < tiles.width; tx++) {
                for (int ty = 0; ty < tiles.height; ty++) {
                    tiles.set(tx, ty, new Tile(tx, ty, Blocks.stone, Blocks.air, Blocks.air));
                }
            }
        });
        app.runPosted();

        Vars.state.rules.waves = false;
        Vars.state.rules.attackMode = true;
        Vars.state.rules.defaultTeam = Team.sharded;
        Vars.state.rules.waveTeam = Team.crux;
        Vars.logic = new Logic();
        Vars.universe = new Universe();
        Vars.logicVars = new GlobalVars();
        Vars.logicVars.init();
        Vars.collisions = new EntityCollisions();
        Vars.bases = new BaseRegistry();
        Vars.bases.load();
        Vars.world.tile(6, 16).setBlock(Blocks.coreShard, Team.sharded, 0);
        Vars.logic.play();
        app.runPosted();

        // Position camera so Duo (west of camera center) pans negative and Hail/Drill pan distinct.
        Core.camera.resize(400f, 240f);
        Core.camera.position.set(14f * 8f, 16f * 8f);
        Core.camera.update();

        // Conveyor + Junction -> Duo targeting stationary Dagger.
        var duoTile = Vars.world.tile(10, 16);
        var beltTile = Vars.world.tile(9, 16);
        var sourceTile = Vars.world.tile(8, 16);
        duoTile.setBlock(Blocks.duo, Team.sharded, 0);
        beltTile.setBlock(Blocks.conveyor, Team.sharded, 0);
        sourceTile.setBlock(Blocks.junction, Team.sharded, 0);
        ConveyorBuild conveyor = (ConveyorBuild) beltTile.build;
        ItemTurretBuild duo = (ItemTurretBuild) duoTile.build;

        var dagger = UnitTypes.dagger.create(Team.crux);
        dagger.controller(new AIController() {
            @Override
            public void updateUnit() {}
        });
        dagger.set(17f * 8f, 16f * 8f);
        dagger.add();

        // MechanicalDrill on 2x2 oreCopper + output Conveyor.
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                Vars.world.tile(12 + dx, 10 + dy).setOverlay(Blocks.oreCopper);
            }
        }
        var drillTile = Vars.world.tile(12, 10);
        drillTile.setBlock(Blocks.mechanicalDrill, Team.sharded, 0);
        Vars.world.tile(14, 10).setBlock(Blocks.conveyor, Team.sharded, 0);
        DrillBuild drill = (DrillBuild) drillTile.build;

        // Hail artillery duel (exercises hitSound = Sounds.explosionArtillery + shootArtillerySmall).
        var hailTile = Vars.world.tile(12, 22);
        hailTile.setBlock(Blocks.hail, Team.sharded, 0);
        hailTile.build.handleItem(hailTile.build, Items.graphite);
        hailTile.build.handleItem(hailTile.build, Items.graphite);
        var artilleryTarget = UnitTypes.dagger.create(Team.crux);
        artilleryTarget.controller(new AIController() {
            @Override
            public void updateUnit() {}
        });
        artilleryTarget.set(20f * 8f, 22f * 8f);
        artilleryTarget.add();

        SoundLoop conveyorLoop = new SoundLoop(conveyor.getAmbientSound(), conveyor.block.ambientSoundVolume);
        SoundLoop drillLoop = new SoundLoop(drill.getAmbientSound(), drill.block.ambientSoundVolume);

        int simTicks = 420;
        int supplied = 0;
        int conveyorActiveTicks = 0;
        int drillActiveTicks = 0;
        int clogGatedTicks = 0;
        for (int tick = 1; tick <= simTicks; tick++) {
            audio.setCurrentTick(tick);
            if (supplied < 20 && conveyor.acceptItem(sourceTile.build, Items.copper)) {
                conveyor.handleItem(sourceTile.build, Items.copper);
                supplied++;
            }
            if (tick == 400) {
                conveyor.clogHeat = 0.6f;
            }
            Vars.logic.update();
            app.runPosted();

            boolean conveyorPlay = conveyor.shouldAmbientSound();
            if (conveyorPlay) {
                conveyorActiveTicks++;
            } else {
                clogGatedTicks++;
            }
            conveyorLoop.update(conveyor.x, conveyor.y, conveyorPlay, conveyor.ambientVolume());

            boolean drillPlay = drill.shouldAmbientSound();
            if (drillPlay) {
                drillActiveTicks++;
            }
            drillLoop.update(drill.x, drill.y, drillPlay, drill.ambientVolume());
        }
        conveyorLoop.stop();
        drillLoop.stop();

        if (drill.items.total() == 0 && Vars.world.tile(14, 10).build.items.total() == 0) {
            throw new IllegalStateException("Expected mechanicalDrill to produce items during audio run");
        }
        if (duo.totalAmmo == 0 && supplied == 0) {
            throw new IllegalStateException("Expected copper feed to reach Duo");
        }
        if (!dagger.dead || dagger.isAdded()) {
            throw new IllegalStateException("Expected Duo to destroy target Dagger");
        }

        int duoShots = audio.countPlays(Sounds.shootDuo);
        int hailShots = audio.countPlays(Sounds.shootArtillerySmall);
        int hailHits = audio.countPlays(Sounds.explosionArtillery);
        int unitDeaths = audio.countPlays(Sounds.unitExplode1);
        int conveyorLoops = audio.countPlays(Sounds.loopConveyor);
        int drillLoops = audio.countPlays(Sounds.loopDrill);
        if (duoShots < 10 || hailShots < 1 || hailHits < 1 || unitDeaths != 1
                || conveyorLoops != 1 || drillLoops != 1 || drillActiveTicks == 0 || clogGatedTicks == 0) {
            throw new IllegalStateException(
                String.format(
                    Locale.ROOT,
                    "Incomplete audio trigger trace duo=%d hail=%d hailHit=%d unitDeaths=%d convLoop=%d drillLoop=%d drillTicks=%d clogGated=%d",
                    duoShots, hailShots, hailHits, unitDeaths, conveyorLoops, drillLoops, drillActiveTicks, clogGatedTicks));
        }

        long hash = audio.computeTraceHash(conveyorActiveTicks, drillActiveTicks, clogGatedTicks);
        if (report) {
            RecordingAudio.PlayEvent firstDuo = audio.firstPlay(Sounds.shootDuo);
            RecordingAudio.PlayEvent kill = audio.firstPlay(Sounds.unitExplode1);
            System.out.printf(
                Locale.ROOT,
                "UPSTREAM_AUDIO_SIMULATION ticks=%d events=%d duoShots=%d@tick%d(pan=%.3f,vol=%.3f) hail=%d/%d unitExplode1=1@tick%d(pan=%.3f,vol=%.3f) loops=conv:%d,drill:%d clogGated=%d%n",
                simTicks,
                audio.events.size(),
                duoShots,
                firstDuo.tick,
                firstDuo.pan,
                firstDuo.volume,
                hailShots,
                hailHits,
                kill.tick,
                kill.pan,
                kill.volume,
                conveyorActiveTicks,
                drillActiveTicks,
                clogGatedTicks);
        }

        return new ScenarioResult(simTicks, audio.events.size(), hash);
    }

    private static String describeSound(Sound s) {
        if (s == null) {
            return "null";
        }
        if (s == Sounds.none) {
            return "none";
        }
        if (s == Sounds.unset) {
            return "unset";
        }
        if (s instanceof RandomSound rs) {
            StringBuilder out = new StringBuilder("random(");
            for (int i = 0; i < rs.sounds.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(Sounds.getSoundId(rs.sounds[i]));
            }
            return out.append(')').toString();
        }
        int id = Sounds.getSoundId(s);
        if (id >= 0) {
            return "id:" + id;
        }
        return s.getClass().getSimpleName();
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * FNV_PRIME;
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record ScenarioResult(int totalTicks, int eventCount, long traceHash) {}

    private static final class DirectAssetManager extends AssetManager {
        private final List<AssetDescriptor<?>> pending = new ArrayList<>();

        DirectAssetManager() {
            super(Fi::new, false);
        }

        @Override
        public synchronized <T> AssetDescriptor<T> load(
                String fileName, Class<T> type, AssetLoaderParameters<T> parameter) {
            AssetDescriptor<T> descriptor = new AssetDescriptor<>(fileName, type, parameter);
            pending.add(descriptor);
            return descriptor;
        }

        @SuppressWarnings("unchecked")
        void finishLoadingAll() {
            for (AssetDescriptor<?> raw : pending) {
                if (raw.type == Sound.class) {
                    AssetDescriptor<Sound> desc = (AssetDescriptor<Sound>) raw;
                    RecordingSound sound = new RecordingSound(desc.fileName);
                    addAsset(desc.fileName, Sound.class, sound);
                    if (desc.loaded != null) {
                        desc.loaded.get(sound);
                    }
                } else if (raw.type == Music.class) {
                    AssetDescriptor<Music> desc = (AssetDescriptor<Music>) raw;
                    RecordingMusic music = new RecordingMusic(desc.fileName);
                    addAsset(desc.fileName, Music.class, music);
                    if (desc.loaded != null) {
                        desc.loaded.get(music);
                    }
                } else {
                    throw new IllegalStateException("Unexpected asset type in Sounds/Musics registry: " + raw.type);
                }
            }
            pending.clear();
        }
    }

    private static final class RecordingSound extends Sound {
        final String assetPath;

        RecordingSound(String assetPath) {
            this.assetPath = assetPath;
            this.file = new Fi(assetPath);
        }

        @Override
        public int play(float volume, float pitch, float pan, boolean loop, boolean checkFrame, AudioBus bus) {
            if (Core.audio instanceof RecordingAudio rec) {
                return rec.recordPlay(this, volume, pitch, pan, loop);
            }
            return -1;
        }
    }

    private static final class RecordingMusic extends Music {
        final String assetPath;

        RecordingMusic(String assetPath) {
            this.assetPath = assetPath;
            this.file = new Fi(assetPath);
        }

        @Override public void play() {}
        @Override public void pause(boolean pause) {}
        @Override public void stop() {}
        @Override public boolean isPlaying() { return false; }
        @Override public void setLooping(boolean isLooping) {}
        @Override public void setVolume(float volume) {}
        @Override public void set(float pan, float volume) {}
        @Override public float getPosition() { return 0f; }
        @Override public void setPosition(float position) {}
        @Override public float getLength() { return 0f; }
    }

    private static final class RecordingAudio extends Audio {
        record PlayEvent(int tick, int soundId, int volumeQ, int pitchQ, int panQ, float pan, float volume, boolean loop) {}

        static final class VoiceState {
            final int voiceId;
            final Sound sound;
            float pan;
            float volume;
            float pitch;
            boolean playing = true;
            boolean looping;

            VoiceState(int voiceId, Sound sound, float pan, float volume, float pitch, boolean looping) {
                this.voiceId = voiceId;
                this.sound = sound;
                this.pan = pan;
                this.volume = volume;
                this.pitch = pitch;
                this.looping = looping;
            }
        }

        final List<PlayEvent> events = new ArrayList<>();
        final Map<Integer, VoiceState> voices = new LinkedHashMap<>();
        int nextVoiceId = 1;
        int currentTick = 0;

        RecordingAudio() {
            super(false);
            sfxVolume = 1f;
            falloff = 16000f;
        }

        void resetLog() {
            events.clear();
            voices.clear();
            nextVoiceId = 1;
            currentTick = 0;
        }

        void setCurrentTick(int tick) {
            currentTick = tick;
        }

        int recordPlay(RecordingSound sound, float volume, float pitch, float pan, boolean loop) {
            float cleanVol = (Float.isNaN(volume) || Float.isInfinite(volume)) ? 0f : volume;
            float cleanPitch = (Float.isNaN(pitch) || Float.isInfinite(pitch)) ? 1f : pitch;
            float cleanPan = (Float.isNaN(pan) || Float.isInfinite(pan)) ? 0f : Mathf.clamp(pan, -1f, 1f);
            int voiceId = nextVoiceId++;
            int soundId = Sounds.getSoundId(sound);
            events.add(new PlayEvent(
                currentTick,
                soundId,
                Math.round(cleanVol * 10000f),
                Math.round(cleanPitch * 1000f),
                Math.round(cleanPan * 1000f),
                cleanPan,
                cleanVol,
                loop));
            voices.put(voiceId, new VoiceState(voiceId, sound, cleanPan, cleanVol, cleanPitch, loop));
            return voiceId;
        }

        VoiceState singleActiveLoop(Sound sound) {
            VoiceState match = null;
            for (VoiceState v : voices.values()) {
                if (v.playing && v.sound == sound) {
                    if (match != null) {
                        throw new IllegalStateException("Expected one active loop for sound");
                    }
                    match = v;
                }
            }
            if (match == null) {
                throw new IllegalStateException("Missing active loop");
            }
            return match;
        }

        int countPlays(Sound sound) {
            int id = Sounds.getSoundId(sound);
            int count = 0;
            for (PlayEvent e : events) {
                if (e.soundId == id) {
                    count++;
                }
            }
            return count;
        }

        PlayEvent firstPlay(Sound sound) {
            int id = Sounds.getSoundId(sound);
            for (PlayEvent e : events) {
                if (e.soundId == id) {
                    return e;
                }
            }
            throw new IllegalStateException("Missing play event for sound id " + id);
        }

        long computeTraceHash(int convTicks, int drillTicks, int clogTicks) {
            long h = FNV_OFFSET;
            h = mix(h, convTicks);
            h = mix(h, drillTicks);
            h = mix(h, clogTicks);
            for (PlayEvent e : events) {
                h = mix(h, e.tick);
                h = mix(h, e.soundId);
                h = mix(h, e.volumeQ);
                h = mix(h, e.panQ);
                h = mix(h, e.loop ? 1L : 0L);
            }
            return h;
        }

        @Override
        public boolean isPlaying(int soundId) {
            VoiceState state = voices.get(soundId);
            return state != null && state.playing;
        }

        @Override public void protect(int voice, boolean protect) {}

        @Override
        public int play(AudioSource source, float volume, float pitch, float pan, boolean loop) {
            if (source instanceof RecordingSound rs) {
                return recordPlay(rs, volume, pitch, pan, loop);
            }
            return -1;
        }

        @Override
        public void stop(AudioSource source) {
            for (VoiceState state : voices.values()) {
                if (state.sound == source) {
                    state.playing = false;
                }
            }
        }

        @Override
        public void stop(int soundId) {
            VoiceState state = voices.get(soundId);
            if (state != null) {
                state.playing = false;
            }
        }

        @Override public void setPaused(int soundId, boolean paused) {}

        @Override
        public void setLooping(int soundId, boolean looping) {
            VoiceState state = voices.get(soundId);
            if (state != null) {
                state.looping = looping;
            }
        }

        @Override
        public void setPitch(int soundId, float pitch) {
            VoiceState state = voices.get(soundId);
            if (state != null) {
                state.pitch = pitch;
            }
        }

        @Override
        public void setVolume(int soundId, float volume) {
            VoiceState state = voices.get(soundId);
            if (state != null) {
                state.volume = volume;
            }
        }

        @Override
        public void set(int soundId, float pan, float volume) {
            VoiceState state = voices.get(soundId);
            if (state != null) {
                state.pan = pan;
                state.volume = volume;
            }
        }

        @Override public void fadeFilterParam(int voice, int filter, int attribute, float value, float timeSec) {}
        @Override public void setFilterParam(int voice, int filter, int attribute, float value) {}
        @Override public void setFilter(int index, AudioFilter filter) {}

        @Override
        public int countPlaying(AudioSource source) {
            int count = 0;
            for (VoiceState state : voices.values()) {
                if (state.playing && state.sound == source) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int countTotalPlaying() {
            int count = 0;
            for (VoiceState state : voices.values()) {
                if (state.playing) {
                    count++;
                }
            }
            return count;
        }

        @Override public void dispose() { resetLog(); }
        @Override public Sound newSound(Fi file) { return new Sound(); }
        @Override public Music newMusic(Fi file) { return new Music(); }
    }

    private static final class ProbeApplication implements Application {
        private final Seq<ApplicationListener> listeners = new Seq<>();
        private final ArrayDeque<Runnable> posted = new ArrayDeque<>();

        @Override public Seq<ApplicationListener> getListeners() { return listeners; }
        @Override public ApplicationType getType() { return ApplicationType.desktop; }
        @Override public String getClipboardText() { return ""; }
        @Override public void setClipboardText(String text) {}
        @Override public void post(Runnable job) { posted.add(job); }
        @Override public void exit() {}

        void clearPosted() {
            posted.clear();
        }

        void runPosted() {
            while (!posted.isEmpty()) {
                posted.remove().run();
            }
        }
    }
}
