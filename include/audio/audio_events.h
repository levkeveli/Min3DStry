#ifndef MIN3DSTRY_AUDIO_AUDIO_EVENTS_H
#define MIN3DSTRY_AUDIO_AUDIO_EVENTS_H

#include "audio/audio_registry.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Logical upstream audio events. IDs are pinned to
 * audio/upstream-audio-triggers.json and are not gameplay formats.
 */
typedef enum M3dsAudioEvent {
    M3DS_AUDIO_EVENT_UI_BUTTON = 0,
    M3DS_AUDIO_EVENT_RESEARCH_UNLOCK = 1,
    M3DS_AUDIO_EVENT_WAVE_SPAWN = 2,
    M3DS_AUDIO_EVENT_COUNT = 3
} M3dsAudioEvent;

/* Returns the registry ID, or NULL for an invalid event. */
const char *m3ds_audio_event_id(M3dsAudioEvent event);

/*
 * Plays the registry clip for an upstream event. Returns the mixer voice
 * handle, or M3DS_AUDIO_INVALID_VOICE when the registry is absent or the
 * clip is not loaded.
 */
uint32_t m3ds_audio_event_play(
    M3dsAudioRegistry *registry,
    M3dsAudioEvent event,
    const M3dsAudioPlayParams *params);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_AUDIO_AUDIO_EVENTS_H */
