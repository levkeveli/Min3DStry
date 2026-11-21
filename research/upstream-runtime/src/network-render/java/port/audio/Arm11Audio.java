package port.audio;

import arc.Core;
import arc.audio.Audio;
import arc.audio.AudioBus;
import arc.audio.Sound;
import arc.files.Fi;
import arc.graphics.Camera;
import arc.math.Mathf;
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
        Sounds.shootAlpha = new MixerSound();
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

    private static final class MixerSound extends Sound {
        MixerSound() {
            file = new Fi("sounds/shoot/shootAlpha.ogg");
        }

        @Override
        public boolean valid() {
            return true;
        }

        @Override
        protected int playSource(AudioBus bus, float volume, float pitch, float pan, boolean loop) {
            int handle = playSample(volume, pitch, pan, loop ? 1 : 0);
            return handle == 0 ? -1 : handle;
        }
    }

    @Import(name = "m3ds_live_audio_open")
    private static native int openMixer();

    @Import(name = "m3ds_live_audio_play")
    private static native int playSample(float volume, float pitch, float pan, int loop);

    @Import(name = "m3ds_live_audio_set_mix")
    private static native void setMix(int voice, float pan, float volume);

    @Import(name = "m3ds_live_audio_update")
    private static native void updateMixer();

    @Import(name = "m3ds_live_audio_reset")
    public static native void reset();

    @Import(name = "m3ds_live_audio_close")
    public static native void close();
}
