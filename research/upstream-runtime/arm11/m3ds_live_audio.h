#ifndef MIN3DSTRY_LIVE_AUDIO_H
#define MIN3DSTRY_LIVE_AUDIO_H

#include <stdint.h>

int32_t m3ds_live_audio_open(void);
int32_t m3ds_live_audio_play(int32_t id, float volume, float pitch, float pan, int32_t loop);
int32_t m3ds_live_audio_is_active(int32_t voice);
void m3ds_live_audio_stop(int32_t voice);
void m3ds_live_audio_set_pitch(int32_t voice, float pitch);
void m3ds_live_audio_set_paused(int32_t voice, int32_t paused);
void m3ds_live_audio_set_mix(int32_t voice, float pan, float volume);
void m3ds_live_audio_update(void);
void m3ds_live_audio_reset(void);
void m3ds_live_audio_close(void);

#endif
