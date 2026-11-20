#include "audio/audio_events.h"

static const char *const m3ds_event_ids[M3DS_AUDIO_EVENT_COUNT] = {
    "ui.button",
    "ui.unlock",
    "wave.spawn",
};

const char *m3ds_audio_event_id(M3dsAudioEvent event) {
    if ((unsigned)event >= (unsigned)M3DS_AUDIO_EVENT_COUNT) {
        return NULL;
    }
    return m3ds_event_ids[event];
}

uint32_t m3ds_audio_event_play(
    M3dsAudioRegistry *registry,
    M3dsAudioEvent event,
    const M3dsAudioPlayParams *params) {
    if (!registry || !registry->mixer) {
        return M3DS_AUDIO_INVALID_VOICE;
    }

    const char *id = m3ds_audio_event_id(event);
    if (!id) {
        return M3DS_AUDIO_INVALID_VOICE;
    }

    int clip_id = m3ds_audio_registry_find(registry, id);
    if (clip_id == M3DS_AUDIO_INVALID_CLIP) {
        return M3DS_AUDIO_INVALID_VOICE;
    }

    return m3ds_audio_mixer_play(registry->mixer, clip_id, params);
}
