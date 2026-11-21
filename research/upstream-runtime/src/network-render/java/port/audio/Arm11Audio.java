package port.audio;

import arc.Core;
import arc.assets.AssetDescriptor;
import arc.assets.AssetLoaderParameters;
import arc.assets.AssetManager;
import arc.audio.Audio;
import arc.audio.AudioBus;
import arc.audio.Sound;
import arc.files.Fi;
import arc.graphics.Camera;
import arc.math.Mathf;
import arc.struct.Seq;
import mindustry.gen.Sounds;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/** Connects the staged Arc sample submission to the handheld's native mixer. */
@Include("m3ds_live_audio.h")
public final class Arm11Audio extends Audio {
    private Arm11Audio() {
        super(false);
        sfxVolume = 1f;
    }

    public static void open() {
        if (openMixer() == 0) return;
        Core.audio = new Arm11Audio();
        Core.camera = new Camera();
        bindStockSounds();
    }

    static void bindStockSounds() {
        var previous = Core.assets;
        var loader = new StockSoundLoader();
        Core.assets = loader;
        try {
            Sounds.load();
            for (var descriptor : loader.pending) {
                descriptor.loaded.get(new MixerSound(descriptor.fileName));
            }
        } finally {
            Core.assets = previous;
            loader.dispose();
        }
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

    private static final class StockSoundLoader extends AssetManager {
        private final Seq<AssetDescriptor<Sound>> pending = new Seq<>();

        StockSoundLoader() {
            super(Fi::new, false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <T> AssetDescriptor<T> load(
                String fileName, Class<T> type, AssetLoaderParameters<T> parameter) {
            if (type != Sound.class) throw new IllegalArgumentException("Expected a stock Sound asset");
            var descriptor = new AssetDescriptor<>(fileName, type, parameter);
            pending.add((AssetDescriptor<Sound>) descriptor);
            return descriptor;
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

    @Import(name = "m3ds_live_audio_set_pitch")
    private static native void setVoicePitch(int voice, float pitch);

    @Import(name = "m3ds_live_audio_set_paused")
    private static native void setVoicePaused(int voice, int paused);

    @Import(name = "m3ds_live_audio_set_mix")
    private static native void setMix(int voice, float pan, float volume);

    @Import(name = "m3ds_live_audio_update")
    private static native void updateMixer();

    @Import(name = "m3ds_live_audio_reset")
    public static native void reset();

    @Import(name = "m3ds_live_audio_close")
    public static native void close();
}
