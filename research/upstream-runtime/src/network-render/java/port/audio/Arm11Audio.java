package port.audio;

import arc.Core;
import arc.assets.AssetDescriptor;
import arc.assets.AssetLoaderParameters;
import arc.assets.AssetManager;
import arc.audio.Audio;
import arc.audio.AudioBus;
import arc.audio.Music;
import arc.audio.Sound;
import arc.files.Fi;
import arc.graphics.Camera;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.audio.SoundControl;
import mindustry.gen.Groups;
import mindustry.gen.Musics;
import mindustry.gen.Sounds;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/** Connects the staged Arc sample submission to the handheld's native mixer. */
@Include("m3ds_live_audio.h")
public final class Arm11Audio extends Audio {
    private AssetManager stockAssets;
    private AssetManager previousAssets;
    private LiveSoundControl soundtrack;
    private boolean hasMusic;

    private Arm11Audio() {
        super(false);
        sfxVolume = 1f;
    }

    public static void open() {
        int capabilities = openMixer();
        if (capabilities == 0) return;
        var audio = new Arm11Audio();
        Core.audio = audio;
        Core.camera = new Camera();
        audio.hasMusic = (capabilities & 2) != 0;
        audio.previousAssets = Core.assets;
        audio.stockAssets = bindStockAudio(audio.hasMusic);
    }

    static AssetManager bindStockAudio(boolean music) {
        var previous = Core.assets;
        var loader = new StockAudioLoader();
        Core.assets = loader;
        try {
            Sounds.load();
            if (music) Musics.load();
            loader.finish();
            return loader;
        } finally {
            Core.assets = previous;
        }
    }

    public static void prepareMusicControl() {
        if (Core.audio instanceof Arm11Audio audio && audio.hasMusic) {
            // The stock Maps constructor expects a desktop ContentLoader when Core.assets is set.
            // Publish the audio-only registry after map and content services have been created.
            Core.assets = audio.stockAssets;
            Core.settings.defaults("musicvol", 100);
            audio.soundtrack = new LiveSoundControl();
        }
    }

    public static void updateClientAudio(long now) {
        var controller = musicControl();
        if (controller != null) {
            controller.sampleLiveBuildingAmbience(Groups.build, now);
            controller.update();
        }
    }

    public static SoundControl musicControl() {
        return Core.audio instanceof Arm11Audio audio ? audio.soundtrack : null;
    }

    public static void updateCamera(float x, float y, float width, float height) {
        if (Core.audio instanceof Arm11Audio audio) {
            Core.camera.position.set(x, y);
            Core.camera.width = width;
            Core.camera.height = height;
            audio.sfxVolume = Mathf.clamp(Core.settings.getInt("sfxvol", 100) / 100f);
            updateMixer();
        }
    }

    @Override
    public boolean initialized() {
        return true;
    }

    @Override
    public void set(int voice, float pan, float volume) {
        if (voice != -1 && Float.isFinite(pan) && Float.isFinite(volume)) setMix(voice, pan, volume);
    }

    @Override
    public boolean isPlaying(int voice) {
        return isVoiceActive(voice) != 0;
    }

    @Override
    public void stop(int voice) {
        stopVoice(voice);
    }

    @Override
    public void setPitch(int voice, float pitch) {
        setVoicePitch(voice, pitch);
    }

    @Override
    public void setPaused(int voice, boolean paused) {
        setVoicePaused(voice, paused ? 1 : 0);
    }

    private static final class LiveSoundControl extends SoundControl {
        LiveSoundControl() {
            reload();
        }

        void enterWorld() {
            stop();
            resetLiveBuildingAmbience();
            lastPlayed = Time.millis();
        }
    }

    private static final class StockAudioLoader extends AssetManager {
        private final Seq<AssetDescriptor<?>> pending = new Seq<>();

        StockAudioLoader() {
            super(Fi::new, false);
        }

        @Override
        public synchronized <T> AssetDescriptor<T> load(
                String fileName, Class<T> type, AssetLoaderParameters<T> parameter) {
            var descriptor = new AssetDescriptor<>(fileName, type, parameter);
            pending.add(descriptor);
            return descriptor;
        }

        @SuppressWarnings("unchecked")
        void finish() {
            int track = 0;
            for (var raw : pending) {
                if (raw.type == Sound.class) {
                    var descriptor = (AssetDescriptor<Sound>) raw;
                    var asset = new MixerSound(descriptor.fileName);
                    addAsset(descriptor.fileName, Sound.class, asset);
                    descriptor.loaded.get(asset);
                } else if (raw.type == Music.class) {
                    var descriptor = (AssetDescriptor<Music>) raw;
                    var asset = new Arm11Music(descriptor.fileName, track++);
                    addAsset(descriptor.fileName, Music.class, asset);
                    descriptor.loaded.get(asset);
                } else {
                    throw new IllegalArgumentException("Unexpected stock audio asset " + raw.fileName);
                }
            }
            pending.clear();
        }
    }

    private static final class MixerSound extends Sound {
        MixerSound(String path) {
            file = new Fi(path);
        }

        @Override
        public boolean valid() {
            return true;
        }

        @Override
        public void stop() {
            stopSoundSource(Sounds.getSoundId(this));
        }

        @Override
        protected int playSource(AudioBus bus, float volume, float pitch, float pan, boolean loop) {
            int handle = playSample(Sounds.getSoundId(this), volume, pitch, pan, loop ? 1 : 0);
            return handle == 0 ? -1 : handle;
        }
    }

    @Import(name = "m3ds_live_audio_open")
    private static native int openMixer();

    @Import(name = "m3ds_live_audio_play")
    private static native int playSample(int soundId, float volume, float pitch, float pan, int loop);

    @Import(name = "m3ds_live_audio_is_active")
    private static native int isVoiceActive(int voice);

    @Import(name = "m3ds_live_audio_stop")
    private static native void stopVoice(int voice);

    @Import(name = "m3ds_live_audio_stop_source")
    private static native void stopSoundSource(int soundId);

    @Import(name = "m3ds_live_audio_set_pitch")
    private static native void setVoicePitch(int voice, float pitch);

    @Import(name = "m3ds_live_audio_set_paused")
    private static native void setVoicePaused(int voice, int paused);

    @Import(name = "m3ds_live_audio_set_mix")
    private static native void setMix(int voice, float pan, float volume);

    @Import(name = "m3ds_live_audio_update")
    private static native void updateMixer();

    @Import(name = "m3ds_live_audio_reset")
    private static native void resetMixer();

    @Import(name = "m3ds_live_audio_close")
    private static native void closeMixer();

    public static void reset() {
        resetMixer();
        if (Core.audio instanceof Arm11Audio audio && audio.soundtrack != null) audio.soundtrack.enterWorld();
    }

    public static void close() {
        closeMixer();
        if (Core.audio instanceof Arm11Audio audio && audio.stockAssets != null) {
            Core.assets = audio.previousAssets;
            audio.stockAssets.dispose();
        }
    }
}
