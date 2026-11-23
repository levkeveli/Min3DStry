package port.audio;

import arc.audio.Music;
import arc.files.Fi;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/** Arc track state backed by the handheld's dedicated soundtrack stream. */
@Include("m3ds_live_audio.h")
final class Arm11Music extends Music {
    final int track;
    private float pan;
    private float volume = 1f;
    private boolean loop;

    Arm11Music(String path, int track) {
        file = new Fi(path);
        this.track = track;
    }

    @Override public void play() { playTrack(track, pan, volume, loop ? 1 : 0); }
    @Override public void stop() { stopTrack(track); }
    @Override public void pause(boolean paused) { pauseTrack(track, paused ? 1 : 0); }
    @Override public boolean isPlaying() { return isTrackPlaying(track) != 0; }
    @Override public boolean isLooping() { return loop; }
    @Override public float getVolume() { return volume; }
    @Override public float getPosition() { return trackPosition(track); }

    @Override
    public void setLooping(boolean looping) {
        loop = looping;
        setTrackLooping(track, looping ? 1 : 0);
    }

    @Override
    public void setVolume(float volume) {
        set(pan, volume);
    }

    @Override
    public void set(float pan, float volume) {
        this.pan = pan;
        this.volume = volume;
        setTrackMix(track, pan, volume);
    }

    @Import(name = "m3ds_live_music_play")
    private static native void playTrack(int id, float pan, float volume, int looping);
    @Import(name = "m3ds_live_music_stop")
    private static native void stopTrack(int id);
    @Import(name = "m3ds_live_music_pause")
    private static native void pauseTrack(int id, int paused);
    @Import(name = "m3ds_live_music_is_playing")
    private static native int isTrackPlaying(int id);
    @Import(name = "m3ds_live_music_position")
    private static native float trackPosition(int id);
    @Import(name = "m3ds_live_music_set_looping")
    private static native void setTrackLooping(int id, int looping);
    @Import(name = "m3ds_live_music_set_mix")
    private static native void setTrackMix(int id, float pan, float volume);
}
