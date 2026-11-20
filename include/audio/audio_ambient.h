#ifndef MIN3DSTRY_AUDIO_AUDIO_AMBIENT_H
#define MIN3DSTRY_AUDIO_AUDIO_AMBIENT_H

#include "audio/audio_events.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Current harness approximation for upstream block ambients: one conveyor
 * loop and one drill loop, started globally and stopped while the scene is
 * paused. Positional per-block ambient mixing is still pending.
 */
typedef struct M3dsAudioAmbient {
    uint32_t conveyor_voice;
    uint32_t drill_voice;
} M3dsAudioAmbient;

void m3ds_audio_ambient_start(
    M3dsAudioRegistry *events,
    float volume,
    M3dsAudioAmbient *out_ambient);

void m3ds_audio_ambient_stop(
    M3dsAudioMixer *mixer,
    M3dsAudioAmbient *ambient);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_AUDIO_AUDIO_AMBIENT_H */
