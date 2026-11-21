#include "audio/audio_events.h"

#include <float.h>

typedef struct M3dsAudioEventMeta {
    const char *id;
    int upstream_sound_id;
} M3dsAudioEventMeta;

/* Pinned Mindustry v160.8 (19a0f40d44514f4802a8b79770456e7e32e34da0) Sounds.load() IDs. */
static const M3dsAudioEventMeta m3ds_event_meta[M3DS_AUDIO_EVENT_COUNT] = {
    {"ui.button", 185},      /* Sounds.uiButton */
    {"ui.unlock", 189},      /* Sounds.uiUnlock */
    {"wave.spawn", 198},     /* Sounds.waveSpawn */
    {"shoot.duo", 136},      /* Sounds.shootDuo */
    {"enemy.kill", 192},     /* Sounds.unitExplode1 */
    {"conveyor.loop", 63},   /* Sounds.loopConveyor */
    {"drill.loop", 67},      /* Sounds.loopDrill */
    {"block.place", 26},     /* Sounds.blockPlace1 */
    {"block.break", 11},     /* Sounds.blockBreak1 */
};

static bool m3ds_events_is_finite(float val) {
    return val >= -FLT_MAX && val <= FLT_MAX;
}

static float m3ds_events_clamp(float val, float lo, float hi) {
    if (!(val >= lo)) {
        return lo;
    }
    if (!(val <= hi)) {
        return hi;
    }
    return val;
}

const char *m3ds_audio_event_id(M3dsAudioEvent event) {
    if ((unsigned)event >= (unsigned)M3DS_AUDIO_EVENT_COUNT) {
        return NULL;
    }
    return m3ds_event_meta[event].id;
}

int m3ds_audio_event_clip(
    M3dsAudioRegistry *registry,
    M3dsAudioEvent event) {
    if (!registry || !registry->mixer) {
        return M3DS_AUDIO_INVALID_CLIP;
    }

    const char *id = m3ds_audio_event_id(event);
    if (!id) {
        return M3DS_AUDIO_INVALID_CLIP;
    }

    return m3ds_audio_registry_find(registry, id);
}

uint32_t m3ds_audio_event_play(
    M3dsAudioRegistry *registry,
    M3dsAudioEvent event,
    const M3dsAudioPlayParams *params) {
    int clip_id = m3ds_audio_event_clip(registry, event);
    if (clip_id == M3DS_AUDIO_INVALID_CLIP) {
        return M3DS_AUDIO_INVALID_VOICE;
    }

    return m3ds_audio_mixer_play(registry->mixer, clip_id, params);
}

int m3ds_audio_event_upstream_sound_id(M3dsAudioEvent event) {
    if ((unsigned)event >= (unsigned)M3DS_AUDIO_EVENT_COUNT) {
        return M3DS_UPSTREAM_SOUND_UNSET;
    }
    return m3ds_event_meta[event].upstream_sound_id;
}

bool m3ds_audio_event_from_upstream_sound_id(
    int upstream_sound_id,
    M3dsAudioEvent *out_event) {
    if (upstream_sound_id < 0) {
        return false;
    }
    for (unsigned int i = 0u; i < (unsigned int)M3DS_AUDIO_EVENT_COUNT; ++i) {
        if (m3ds_event_meta[i].upstream_sound_id == upstream_sound_id) {
            if (out_event) {
                *out_event = (M3dsAudioEvent)i;
            }
            return true;
        }
    }
    return false;
}

int m3ds_audio_upstream_sound_clip(
    M3dsAudioRegistry *registry,
    int upstream_sound_id) {
    M3dsAudioEvent event = M3DS_AUDIO_EVENT_COUNT;
    if (!m3ds_audio_event_from_upstream_sound_id(upstream_sound_id, &event)) {
        return M3DS_AUDIO_INVALID_CLIP;
    }
    return m3ds_audio_event_clip(registry, event);
}

uint32_t m3ds_audio_upstream_sound_play(
    M3dsAudioRegistry *registry,
    int upstream_sound_id,
    const M3dsAudioPlayParams *params) {
    M3dsAudioEvent event = M3DS_AUDIO_EVENT_COUNT;
    if (!m3ds_audio_event_from_upstream_sound_id(upstream_sound_id, &event)) {
        return M3DS_AUDIO_INVALID_VOICE;
    }
    return m3ds_audio_event_play(registry, event, params);
}

float m3ds_audio_calc_falloff(
    float sound_x,
    float sound_y,
    float cam_x,
    float cam_y,
    float falloff,
    float falloff_offset) {
    if (!m3ds_events_is_finite(sound_x) || !m3ds_events_is_finite(sound_y) ||
        !m3ds_events_is_finite(cam_x) || !m3ds_events_is_finite(cam_y) ||
        !m3ds_events_is_finite(falloff) || !m3ds_events_is_finite(falloff_offset) ||
        !(falloff > 0.0f)) {
        return 0.0f;
    }
    float dx = sound_x - cam_x;
    float dy = sound_y - cam_y;
    float dist_sq = dx * dx + dy * dy;
    if (!m3ds_events_is_finite(dist_sq)) {
        return 0.0f;
    }
    float offset_sq = falloff_offset * falloff_offset;
    float dst2 = dist_sq - offset_sq;
    if (!(dst2 > 0.0f)) {
        return 1.0f;
    }
    float scaled = dst2 / falloff;
    /* Avoid dividing near zero when dst2 <= falloff (matches Math/clamp to 1.0f). */
    if (scaled <= 1.0f) {
        return 1.0f;
    }
    if (!m3ds_events_is_finite(scaled)) {
        return 0.0f;
    }
    return m3ds_events_clamp(1.0f / scaled, 0.0f, 1.0f);
}

float m3ds_audio_calc_pan(float sound_x, float cam_x, float cam_width) {
    if (!m3ds_events_is_finite(sound_x) || !m3ds_events_is_finite(cam_x) ||
        !m3ds_events_is_finite(cam_width) || !(cam_width > 0.0f)) {
        return 0.0f;
    }
    float half_width = cam_width * 0.5f;
    if (!(half_width > 0.0f)) {
        return 0.0f;
    }
    float raw = (sound_x - cam_x) / half_width;
    return m3ds_events_clamp(raw, -0.9f, 0.9f);
}

float m3ds_audio_calc_volume(
    float sound_x,
    float sound_y,
    float cam_x,
    float cam_y,
    float falloff,
    float falloff_offset,
    float sfx_volume) {
    if (!m3ds_events_is_finite(sfx_volume)) {
        return 0.0f;
    }
    float clamped_sfx = m3ds_events_clamp(sfx_volume, 0.0f, 1.0f);
    return m3ds_audio_calc_falloff(
               sound_x, sound_y, cam_x, cam_y, falloff, falloff_offset) *
           clamped_sfx;
}
