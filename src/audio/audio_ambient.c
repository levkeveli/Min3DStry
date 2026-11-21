#include "audio/audio_ambient.h"

static uint32_t play_loop(
    M3dsAudioRegistry *events,
    M3dsAudioEvent event,
    float volume) {
    uint32_t voice = M3DS_AUDIO_INVALID_VOICE;
    if (events) {
        M3dsAudioPlayParams params =
            m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
        params.volume = volume;
        params.looping = true;
        voice = m3ds_audio_event_play(events, event, &params);
    }
    return voice;
}

void m3ds_audio_ambient_start(
    M3dsAudioRegistry *events,
    float volume,
    M3dsAudioAmbient *out_ambient) {
    if (!out_ambient) {
        return;
    }
    out_ambient->conveyor_voice = M3DS_AUDIO_INVALID_VOICE;
    out_ambient->drill_voice = M3DS_AUDIO_INVALID_VOICE;
    if (!events) {
        return;
    }

    out_ambient->conveyor_voice = play_loop(
        events, M3DS_AUDIO_EVENT_CONVEYOR_LOOP, volume);
    out_ambient->drill_voice = play_loop(
        events, M3DS_AUDIO_EVENT_DRILL_LOOP, volume);
}

void m3ds_audio_ambient_stop(
    M3dsAudioMixer *mixer,
    M3dsAudioAmbient *ambient) {
    if (!ambient) {
        return;
    }
    if (mixer) {
        m3ds_audio_mixer_stop(mixer, ambient->conveyor_voice);
        m3ds_audio_mixer_stop(mixer, ambient->drill_voice);
    }
    ambient->conveyor_voice = M3DS_AUDIO_INVALID_VOICE;
    ambient->drill_voice = M3DS_AUDIO_INVALID_VOICE;
}
