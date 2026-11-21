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
    M3DS_AUDIO_EVENT_SHOOT_DUO = 3,
    M3DS_AUDIO_EVENT_ENEMY_KILL = 4,
    M3DS_AUDIO_EVENT_CONVEYOR_LOOP = 5,
    M3DS_AUDIO_EVENT_DRILL_LOOP = 6,
    M3DS_AUDIO_EVENT_BLOCK_PLACE = 7,
    M3DS_AUDIO_EVENT_BLOCK_BREAK = 8,
    M3DS_AUDIO_EVENT_COUNT = 9
} M3dsAudioEvent;

/* Returns the registry ID, or NULL for an invalid event. */
const char *m3ds_audio_event_id(M3dsAudioEvent event);

/*
 * Resolves an event to a registered mixer clip, or M3DS_AUDIO_INVALID_CLIP
 * when the registry is absent or the clip is not loaded.
 */
int m3ds_audio_event_clip(
    M3dsAudioRegistry *registry,
    M3dsAudioEvent event);

/*
 * Plays the registry clip for an upstream event. Returns the mixer voice
 * handle, or M3DS_AUDIO_INVALID_VOICE when the registry is absent, the clip
 * is not loaded, or the mixer has no DSP-ready voices.
 */
uint32_t m3ds_audio_event_play(
    M3dsAudioRegistry *registry,
    M3dsAudioEvent event,
    const M3dsAudioPlayParams *params);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_AUDIO_AUDIO_EVENTS_H */
