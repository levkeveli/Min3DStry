import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.assets.AssetDescriptor;
import arc.assets.AssetLoaderParameters;
import arc.assets.AssetManager;
import arc.audio.Audio;
import arc.audio.AudioBus;
import arc.audio.AudioFilter;
import arc.audio.AudioSource;
import arc.audio.Music;
import arc.audio.Sound;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Camera;
import arc.math.Mathf;
import arc.struct.Seq;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.audio.SoundLoop;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.gen.Groups;
import mindustry.gen.Musics;
import mindustry.gen.Sounds;
import mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild;
import mindustry.world.blocks.distribution.Conveyor.ConveyorBuild;
import mindustry.world.blocks.production.Drill.DrillBuild;

/**
 * TeaVM/ARM11 & JVM parity probe for upstream Mindustry v160.8
 * (19a0f40d44514f4802a8b79770456e7e32e34da0) audio registry loading,
 * content sound bindings, positional camera falloff/pan, SoundLoop ambient
 * gating, and deterministic combat audio events on Glacier.
 */
public final class UpstreamAudioTickProbe {
    public static void main(String[] args) throws IOException {
        DirectAssetManager assets = new DirectAssetManager();
        RecordingAudio audio = new RecordingAudio();
        Core.assets = assets;
        Core.audio = audio;

        // Populate Sounds and Musics before UpstreamWorldLoadProbe calls ContentLoader.createBaseContent().
        Sounds.load();
        Musics.load();
        int loadedSounds = assets.finishSounds();
        int loadedMusics = assets.finishMusics();
        // Clear Core.assets before Maps.<init>() in UpstreamWorldLoadProbe.initialize(), which
        // otherwise expects a full ContentLoader AssetLoader registered for map previews.
        Core.assets = null;

        var app = UpstreamSimulationWorld.play(args);
        Core.assets = assets;
        // Enable non-headless Arc Sound pan/falloff calculation while keeping Vars.headless = true.
        Core.app = new NonHeadlessAppProxy(app);
        Core.audio = audio;
        Core.camera = new Camera();
        Core.camera.resize(400f, 240f);

        int duoId = Sounds.getSoundId(Blocks.duo instanceof mindustry.world.blocks.defense.turrets.ItemTurret it ? it.shootSound : Sounds.none);
        int unitExplode1Id = Sounds.getSoundId(UnitTypes.dagger.deathSound);
        int conveyorLoopId = Sounds.getSoundId(Blocks.conveyor.ambientSound);
        int drillLoopId = Sounds.getSoundId(Blocks.mechanicalDrill.ambientSound);
        int uiButtonId = Sounds.getSoundId(Sounds.uiButton);
        int waveSpawnId = Sounds.getSoundId(Sounds.waveSpawn);
        if (loadedSounds != 205 || loadedMusics != 16
                || duoId != Sounds.getSoundId(Sounds.shootDuo)
                || unitExplode1Id != Sounds.getSoundId(Sounds.unitExplode1)
                || conveyorLoopId != Sounds.getSoundId(Sounds.loopConveyor)
                || drillLoopId != Sounds.getSoundId(Sounds.loopDrill)
                || uiButtonId < 0 || waveSpawnId < 0) {
            throw new IllegalStateException("Audio registry or content binding mismatch");
        }
        System.out.println("UPSTREAM_AUDIO_REGISTRY sounds=" + loadedSounds + " musics=" + loadedMusics
            + " duo=" + duoId + " death=" + unitExplode1Id
            + " convLoop=" + conveyorLoopId + " drillLoop=" + drillLoopId);

        var team = Vars.state.rules.defaultTeam;
        var enemy = Vars.state.rules.waveTeam;
        var core = team.core();
        Core.camera.position.set(core.x, core.y);
        Core.camera.update();

        int x = core.tile.x - 8;
        int y = core.tile.y;
        var turretTile = Vars.world.tile(x, y);
        var beltTile = Vars.world.tile(x - 1, y);
        var sourceTile = Vars.world.tile(x - 2, y);
        var targetTile = Vars.world.tile(x - 5, y);
        turretTile.setBlock(Blocks.duo, team, 0);
        beltTile.setBlock(Blocks.conveyor, team, 0);
        sourceTile.setBlock(Blocks.junction, team, 0);
        var turret = (ItemTurretBuild) turretTile.build;
        var belt = (ConveyorBuild) beltTile.build;

        // Place a 2x2 MechanicalDrill over copper ore south of the core for drill ambient SoundLoop.
        int dxBase = core.tile.x + 4;
        int dyBase = core.tile.y - 4;
        for (int ox = 0; ox < 2; ox++) {
            for (int oy = 0; oy < 2; oy++) {
                Vars.world.tile(dxBase + ox, dyBase + oy).setOverlay(Blocks.oreCopper);
            }
        }
        var drillTile = Vars.world.tile(dxBase, dyBase);
        drillTile.setBlock(Blocks.mechanicalDrill, team, 0);
        Vars.world.tile(dxBase + 2, dyBase).setBlock(Blocks.conveyor, team, 0);
        var drill = (DrillBuild) drillTile.build;

        var target = UnitTypes.dagger.create(enemy);
        target.controller(new AIController() {
            @Override
            public void updateUnit() {}
        });
        target.set(targetTile.worldx(), targetTile.worldy());
        target.add();
        Mathf.rand.setSeed(160_008L);

        SoundLoop conveyorLoop = new SoundLoop(belt.getAmbientSound(), belt.block.ambientSoundVolume);
        SoundLoop drillLoop = new SoundLoop(drill.getAmbientSound(), drill.block.ambientSoundVolume);

        int supplied = 0;
        int destroyed = 0;
        int convTicks = 0;
        int drillTicks = 0;
        for (int tick = 1; tick <= 420; tick++) {
            audio.currentTick = tick;
            if (supplied < 20 && belt.acceptItem(sourceTile.build, Items.copper)) {
                belt.handleItem(sourceTile.build, Items.copper);
                supplied++;
            }
            if (tick == 400) {
                belt.clogHeat = 0.6f;
            }
            Vars.logic.update();
            app.runPosted();

            boolean convPlay = belt.shouldAmbientSound();
            if (convPlay) {
                convTicks++;
            }
            conveyorLoop.update(belt.x, belt.y, convPlay, belt.ambientVolume());

            boolean drillPlay = drill.shouldAmbientSound();
            if (drillPlay) {
                drillTicks++;
            }
            drillLoop.update(drill.x, drill.y, drillPlay, drill.ambientVolume());

            if (destroyed == 0 && (target.dead || !target.isAdded())) {
                destroyed = tick;
            }
        }
        conveyorLoop.stop();
        drillLoop.stop();

        int duoShots = audio.countPlays(duoId);
        int deathPlays = audio.countPlays(unitExplode1Id);
        int convLoopStarts = audio.countPlays(conveyorLoopId);
        int drillLoopStarts = audio.countPlays(drillLoopId);
        PlayRecord firstDuo = audio.firstPlay(duoId);
        PlayRecord firstDeath = audio.firstPlay(unitExplode1Id);
        if (duoShots < 10 || deathPlays != 1 || convLoopStarts != 1 || drillLoopStarts != 1
                || convTicks <= 0 || drillTicks <= 0 || destroyed == 0
                || firstDuo.panQ >= 0) {
            throw new IllegalStateException("Unexpected combat/ambient audio trace");
        }

        long traceHash = audio.computeTraceHash(convTicks, drillTicks);
        System.out.println("UPSTREAM_AUDIO_AMBIENT convTicks=" + convTicks + " drillTicks=" + drillTicks
            + " loops=" + convLoopStarts + "," + drillLoopStarts
            + " duoFirstPanMilli=" + firstDuo.panQ + " deathPanMilli=" + firstDeath.panQ);
        System.out.println("UPSTREAM_AUDIO_COMBAT shots=" + duoShots + " firstShot=" + firstDuo.tick
            + " deathTick=" + firstDeath.tick + " destroyed=" + destroyed
            + " ammo=" + turret.totalAmmo + " events=" + audio.events.size()
            + " trace=" + Long.toHexString(traceHash));
    }

    private static final class PlayRecord {
        final int tick;
        final int soundId;
        final int volQ;
        final int panQ;
        final boolean loop;

        PlayRecord(int tick, int soundId, int volQ, int panQ, boolean loop) {
            this.tick = tick;
            this.soundId = soundId;
            this.volQ = volQ;
            this.panQ = panQ;
            this.loop = loop;
        }
    }

    private static final class DirectAssetManager extends AssetManager {
        private final List<AssetDescriptor<?>> pending = new ArrayList<>();

        DirectAssetManager() {
            super(Fi::new, false);
        }

        @Override
        public synchronized <T> AssetDescriptor<T> load(
                String fileName, Class<T> type, AssetLoaderParameters<T> parameter) {
            AssetDescriptor<T> desc = new AssetDescriptor<>(fileName, type, parameter);
            pending.add(desc);
            return desc;
        }

        @SuppressWarnings("unchecked")
        int finishSounds() {
            int count = 0;
            for (AssetDescriptor<?> raw : pending) {
                if (raw.type == Sound.class) {
                    AssetDescriptor<Sound> desc = (AssetDescriptor<Sound>) raw;
                    RecordingSound sound = new RecordingSound(desc.fileName);
                    addAsset(desc.fileName, Sound.class, sound);
                    if (desc.loaded != null) {
                        desc.loaded.get(sound);
                    }
                    count++;
                }
            }
            return count;
        }

        @SuppressWarnings("unchecked")
        int finishMusics() {
            int count = 0;
            for (AssetDescriptor<?> raw : pending) {
                if (raw.type == Music.class) {
                    AssetDescriptor<Music> desc = (AssetDescriptor<Music>) raw;
                    RecordingMusic music = new RecordingMusic(desc.fileName);
                    addAsset(desc.fileName, Music.class, music);
                    if (desc.loaded != null) {
                        desc.loaded.get(music);
                    }
                    count++;
                }
            }
            pending.clear();
            return count;
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
                return rec.recordPlay(this, volume, pan, loop);
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
        final List<PlayRecord> events = new ArrayList<>();
        final List<Boolean> activeVoices = new ArrayList<>();
        int currentTick = 0;

        RecordingAudio() {
            super(false);
            sfxVolume = 1f;
            falloff = 16000f;
        }

        int recordPlay(RecordingSound sound, float volume, float pan, boolean loop) {
            float cleanVol = (Float.isNaN(volume) || Float.isInfinite(volume)) ? 0f : volume;
            float cleanPan = (Float.isNaN(pan) || Float.isInfinite(pan)) ? 0f : Mathf.clamp(pan, -1f, 1f);
            int voiceId = activeVoices.size() + 1;
            activeVoices.add(Boolean.TRUE);
            events.add(new PlayRecord(
                currentTick,
                Sounds.getSoundId(sound),
                Math.round(cleanVol * 10000f),
                Math.round(cleanPan * 1000f),
                loop));
            return voiceId;
        }

        int countPlays(int soundId) {
            int count = 0;
            for (PlayRecord record : events) {
                if (record.soundId == soundId) {
                    count++;
                }
            }
            return count;
        }

        PlayRecord firstPlay(int soundId) {
            for (PlayRecord record : events) {
                if (record.soundId == soundId) {
                    return record;
                }
            }
            throw new IllegalStateException("Missing play record for sound id " + soundId);
        }

        long computeTraceHash(int convTicks, int drillTicks) {
            long h = 0xcbf29ce484222325L;
            h = mix(h, convTicks);
            h = mix(h, drillTicks);
            for (PlayRecord e : events) {
                h = mix(h, e.tick);
                h = mix(h, e.soundId);
                h = mix(h, e.volQ);
                h = mix(h, e.panQ);
                h = mix(h, e.loop ? 1L : 0L);
            }
            return h;
        }

        private static long mix(long h, long value) {
            return (h ^ value) * 0x100000001b3L;
        }

        @Override
        public boolean isPlaying(int voiceId) {
            return voiceId >= 1 && voiceId <= activeVoices.size() && activeVoices.get(voiceId - 1);
        }

        @Override public void protect(int voice, boolean protect) {}

        @Override
        public int play(AudioSource source, float volume, float pitch, float pan, boolean loop) {
            if (source instanceof RecordingSound rs) {
                return recordPlay(rs, volume, pan, loop);
            }
            return -1;
        }

        @Override public void stop(AudioSource source) {}

        @Override
        public void stop(int voiceId) {
            if (voiceId >= 1 && voiceId <= activeVoices.size()) {
                activeVoices.set(voiceId - 1, Boolean.FALSE);
            }
        }

        @Override public void setPaused(int soundId, boolean paused) {}
        @Override public void setLooping(int soundId, boolean looping) {}
        @Override public void setPitch(int soundId, float pitch) {}
        @Override public void setVolume(int soundId, float volume) {}
        @Override public void set(int soundId, float pan, float volume) {}
        @Override public void fadeFilterParam(int voice, int filter, int attribute, float value, float timeSec) {}
        @Override public void setFilterParam(int voice, int filter, int attribute, float value) {}
        @Override public void setFilter(int index, AudioFilter filter) {}
        @Override public int countPlaying(AudioSource source) { return 0; }
        @Override public int countTotalPlaying() { return 0; }
        @Override public void dispose() {}
        @Override public Sound newSound(Fi file) { return new Sound(); }
        @Override public Music newMusic(Fi file) { return new Music(); }
    }

    private static final class NonHeadlessAppProxy implements Application {
        private final UpstreamWorldLoadProbe.ProbeApplication delegate;

        NonHeadlessAppProxy(UpstreamWorldLoadProbe.ProbeApplication delegate) {
            this.delegate = delegate;
        }

        @Override public Seq<ApplicationListener> getListeners() { return delegate.getListeners(); }
        @Override public ApplicationType getType() { return ApplicationType.desktop; }
        @Override public String getClipboardText() { return delegate.getClipboardText(); }
        @Override public void setClipboardText(String text) { delegate.setClipboardText(text); }
        @Override public void post(Runnable job) { delegate.post(job); }
        @Override public void exit() { delegate.exit(); }
    }
}
