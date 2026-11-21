#ifndef MIN3DSTRY_AUDIO_AUDIO_EVENTS_H
#define MIN3DSTRY_AUDIO_AUDIO_EVENTS_H

#include "audio/audio_registry.h"

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_UPSTREAM_SOUND_UNSET (-1)
#define M3DS_UPSTREAM_AUDIO_DEFAULT_FALLOFF 16000.0f
#define M3DS_UPSTREAM_AUDIO_MIN_AUDIBLE_VOLUME 0.005f

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

/*
 * Returns the pinned Mindustry v160.8 Sounds.getSoundId(...) index for an
 * audio event, or M3DS_UPSTREAM_SOUND_UNSET (-1) for invalid events.
 */
int m3ds_audio_event_upstream_sound_id(M3dsAudioEvent event);

/* Maps a pinned v160.8 Sounds ID back to an M3dsAudioEvent when present. */
bool m3ds_audio_event_from_upstream_sound_id(
    int upstream_sound_id,
    M3dsAudioEvent *out_event);

/* Resolves or plays a pinned v160.8 Sounds ID via the RomFS clip registry. */
int m3ds_audio_upstream_sound_clip(
    M3dsAudioRegistry *registry,
    int upstream_sound_id);
uint32_t m3ds_audio_upstream_sound_play(
    M3dsAudioRegistry *registry,
    int upstream_sound_id,
    const M3dsAudioPlayParams *params);

/* Arc Sound spatial attenuation and stereo panning helpers. */
float m3ds_audio_calc_falloff(
    float sound_x,
    float sound_y,
    float cam_x,
    float cam_y,
    float falloff,
    float falloff_offset);
float m3ds_audio_calc_pan(float sound_x, float cam_x, float cam_width);
float m3ds_audio_calc_volume(
    float sound_x,
    float sound_y,
    float cam_x,
    float cam_y,
    float falloff,
    float falloff_offset,
    float sfx_volume);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_AUDIO_AUDIO_EVENTS_H */
