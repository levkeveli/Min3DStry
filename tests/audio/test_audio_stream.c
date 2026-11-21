#include "audio/audio_stream.h"

#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

typedef struct MockStreamBackendCtx {
    M3dsAudioBackendState init_state;
    uint32_t init_status_code;
    bool init_called;
    uint32_t shutdown_calls;
    uint32_t configure_calls;
    uint32_t last_sample_rate;
    uint8_t last_channels;
    float gain_left;
    float gain_right;
    uint32_t mix_calls;
    uint32_t pause_calls;
    bool paused;
    uint32_t stop_calls;
    bool slot_busy[M3DS_AUDIO_STREAM_BUFFER_COUNT];
    uint32_t slot_frames[M3DS_AUDIO_STREAM_BUFFER_COUNT];
    uint32_t queued_slots[64];
    uint32_t queued_count;
    int16_t rendered_samples[65536];
    size_t rendered_sample_count;
    bool fail_configure;
    bool fail_submit;
} MockStreamBackendCtx;

static M3dsAudioBackendState mock_init(void *raw, uint32_t *out_rc) {
    MockStreamBackendCtx *ctx = (MockStreamBackendCtx *)raw;
    ctx->init_called = true;
    if (out_rc) {
        *out_rc = ctx->init_status_code;
    }
    return ctx->init_state;
}

static void mock_shutdown(void *raw) {
    MockStreamBackendCtx *ctx = (MockStreamBackendCtx *)raw;
    ctx->shutdown_calls += 1u;
}

static bool mock_configure(
    void *raw,
    uint32_t sample_rate,
    uint8_t channels,
    float gain_left,
    float gain_right) {
    MockStreamBackendCtx *ctx = (MockStreamBackendCtx *)raw;
    ctx->configure_calls += 1u;
    if (ctx->fail_configure) {
        return false;
    }
    ctx->last_sample_rate = sample_rate;
    ctx->last_channels = channels;
    ctx->gain_left = gain_left;
    ctx->gain_right = gain_right;
    ctx->queued_count = 0u;
    ctx->rendered_sample_count = 0u;
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        ctx->slot_busy[i] = false;
        ctx->slot_frames[i] = 0u;
    }
    return true;
}

static bool mock_buffer_is_free(void *raw, uint32_t slot) {
    const MockStreamBackendCtx *ctx = (const MockStreamBackendCtx *)raw;
    return slot < M3DS_AUDIO_STREAM_BUFFER_COUNT && !ctx->slot_busy[slot];
}

static bool mock_buffer_submit(
    void *raw,
    uint32_t slot,
    const int16_t *samples,
    uint32_t frame_count) {
    MockStreamBackendCtx *ctx = (MockStreamBackendCtx *)raw;
    if (ctx->fail_submit || slot >= M3DS_AUDIO_STREAM_BUFFER_COUNT ||
        ctx->slot_busy[slot]) {
        return false;
    }
    size_t sample_count = (size_t)frame_count * (size_t)ctx->last_channels;
    assert(ctx->rendered_sample_count + sample_count <=
           sizeof(ctx->rendered_samples) / sizeof(ctx->rendered_samples[0]));
    memcpy(
        ctx->rendered_samples + ctx->rendered_sample_count,
        samples,
        sample_count * sizeof(int16_t));
    ctx->rendered_sample_count += sample_count;
    ctx->slot_busy[slot] = true;
    ctx->slot_frames[slot] = frame_count;
    assert(ctx->queued_count <
           sizeof(ctx->queued_slots) / sizeof(ctx->queued_slots[0]));
    ctx->queued_slots[ctx->queued_count++] = slot;
    return true;
}

static void mock_finish_oldest_slot(MockStreamBackendCtx *ctx) {
    if (ctx->queued_count == 0u) {
        return;
    }
    uint32_t slot = ctx->queued_slots[0];
    for (uint32_t i = 1u; i < ctx->queued_count; ++i) {
        ctx->queued_slots[i - 1u] = ctx->queued_slots[i];
    }
    ctx->queued_count -= 1u;
    ctx->slot_busy[slot] = false;
}

static void mock_set_mix(void *raw, float gain_left, float gain_right) {
    MockStreamBackendCtx *ctx = (MockStreamBackendCtx *)raw;
    ctx->gain_left = gain_left;
    ctx->gain_right = gain_right;
    ctx->mix_calls += 1u;
}

static void mock_set_paused(void *raw, bool paused) {
    MockStreamBackendCtx *ctx = (MockStreamBackendCtx *)raw;
    ctx->paused = paused;
    ctx->pause_calls += 1u;
}

static void mock_stop(void *raw) {
    MockStreamBackendCtx *ctx = (MockStreamBackendCtx *)raw;
    ctx->stop_calls += 1u;
    ctx->queued_count = 0u;
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        ctx->slot_busy[i] = false;
    }
}

static M3dsAudioStreamBackend make_mock_backend(
    MockStreamBackendCtx *ctx,
    M3dsAudioBackendState state,
    uint32_t status_code) {
    memset(ctx, 0, sizeof(*ctx));
    ctx->init_state = state;
    ctx->init_status_code = status_code;

    M3dsAudioStreamBackend b;
    memset(&b, 0, sizeof(b));
    b.ctx = ctx;
    b.name = "mock-stream";
    b.init = mock_init;
    b.shutdown = mock_shutdown;
    b.configure = mock_configure;
    b.buffer_is_free = mock_buffer_is_free;
    b.buffer_submit = mock_buffer_submit;
    b.set_mix = mock_set_mix;
    b.set_paused = mock_set_paused;
    b.stop = mock_stop;
    return b;
}

typedef struct MemPcmSource {
    const int16_t *samples;
    uint32_t total_frames;
    uint8_t channels;
    uint32_t cursor_frame;
    uint32_t max_step_frames;
    int fail_after_reads;
    int reads;
    int rewinds;
    int closes;
} MemPcmSource;

static bool mem_read_frames(
    void *raw,
    int16_t *out_samples,
    uint32_t max_frames,
    uint32_t *out_frames) {
    MemPcmSource *src = (MemPcmSource *)raw;
    src->reads += 1;
    if (src->fail_after_reads > 0 && src->reads >= src->fail_after_reads) {
        return false;
    }
    if (src->cursor_frame >= src->total_frames) {
        *out_frames = 0u;
        return true;
    }
    uint32_t rem = src->total_frames - src->cursor_frame;
    uint32_t count = max_frames;
    if (src->max_step_frames > 0u && count > src->max_step_frames) {
        count = src->max_step_frames;
    }
    if (count > rem) {
        count = rem;
    }
    memcpy(
        out_samples,
        src->samples + (size_t)src->cursor_frame * (size_t)src->channels,
        (size_t)count * (size_t)src->channels * sizeof(int16_t));
    src->cursor_frame += count;
    *out_frames = count;
    return true;
}

static bool mem_rewind(void *raw) {
    MemPcmSource *src = (MemPcmSource *)raw;
    src->cursor_frame = 0u;
    src->rewinds += 1;
    return true;
}

static void mem_close(void *raw) {
    MemPcmSource *src = (MemPcmSource *)raw;
    src->closes += 1;
}

static M3dsAudioStreamSource make_mem_source(MemPcmSource *mem) {
    M3dsAudioStreamSource s = {mem, mem_read_frames, mem_rewind, mem_close};
    return s;
}

static bool near_f(float a, float b) {
    float diff = (a > b) ? (a - b) : (b - a);
    return diff < 0.001f;
}

static void test_pinned_music_catalog(void) {
    static const char *const expected_names[M3DS_AUDIO_MUSIC_COUNT] = {
        "boss1", "boss2", "editor", "fine",
        "game1", "game2", "game3", "game4",
        "game5", "game6", "game7", "game8",
        "game9", "land", "launch", "menu",
    };

    for (unsigned int i = 0u; i < (unsigned int)M3DS_AUDIO_MUSIC_COUNT; ++i) {
        M3dsUpstreamMusic track = (M3dsUpstreamMusic)i;
        assert(strcmp(m3ds_audio_music_name(track), expected_names[i]) == 0);
        char up[64];
        char rf[64];
        snprintf(up, sizeof(up), "music/%s.ogg", expected_names[i]);
        snprintf(rf, sizeof(rf), "romfs:/audio/music/%s.pcm", expected_names[i]);
        assert(strcmp(m3ds_audio_music_upstream_path(track), up) == 0);
        assert(strcmp(m3ds_audio_music_romfs_path(track), rf) == 0);

        M3dsUpstreamMusic decoded = M3DS_AUDIO_MUSIC_COUNT;
        assert(m3ds_audio_music_from_name(expected_names[i], &decoded));
        assert(decoded == track);
    }

    assert(m3ds_audio_music_name(M3DS_AUDIO_MUSIC_COUNT) == NULL);
    assert(m3ds_audio_music_upstream_path(M3DS_AUDIO_MUSIC_COUNT) == NULL);
    assert(m3ds_audio_music_romfs_path(M3DS_AUDIO_MUSIC_COUNT) == NULL);
    M3dsUpstreamMusic untouched = M3DS_AUDIO_MUSIC_COUNT;
    assert(!m3ds_audio_music_from_name(NULL, &untouched));
    assert(!m3ds_audio_music_from_name("unknown_track", &untouched));
    assert(untouched == M3DS_AUDIO_MUSIC_COUNT);
    assert(m3ds_audio_music_from_name("menu", NULL));
}

static void test_disabled_and_fallback_no_dsp(void) {
    static M3dsAudioStream stream;
    static int16_t pcm[64];
    for (int i = 0; i < 64; ++i) {
        pcm[i] = (int16_t)(i + 1);
    }

    assert(!m3ds_audio_stream_init(NULL, NULL));
    assert(m3ds_audio_stream_init(&stream, NULL));
    assert(m3ds_audio_stream_state(&stream) == M3DS_AUDIO_STATE_DISABLED);

    M3dsAudioStreamFormat fmt = {22050u, 1u};
    MemPcmSource mem = {pcm, 64u, 1u, 0u, 0u, 0, 0, 0, 0};
    M3dsAudioStreamSource src = make_mem_source(&mem);
    assert(!m3ds_audio_stream_start(&stream, &fmt, &src, false));
    assert(mem.closes == 1);

    M3dsAudioStreamStats stats;
    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.dropped_starts == 1u);
    m3ds_audio_stream_shutdown(&stream);
    assert(m3ds_audio_stream_state(&stream) == M3DS_AUDIO_STATE_UNINITIALIZED);

    MockStreamBackendCtx ctx;
    M3dsAudioStreamBackend backend = make_mock_backend(
        &ctx, M3DS_AUDIO_STATE_FALLBACK_NO_DSP, 0xd880a7fau);
    assert(m3ds_audio_stream_init(&stream, &backend));
    assert(ctx.init_called);
    assert(m3ds_audio_stream_state(&stream) == M3DS_AUDIO_STATE_FALLBACK_NO_DSP);
    assert(m3ds_audio_stream_status_code(&stream) == 0xd880a7fau);

    mem = (MemPcmSource){pcm, 64u, 1u, 0u, 0u, 0, 0, 0, 0};
    assert(!m3ds_audio_stream_start(&stream, &fmt, &src, true));
    assert(mem.closes == 1);
    assert(ctx.configure_calls == 0u);
    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.dropped_starts == 1u);

    m3ds_audio_stream_shutdown(&stream);
    m3ds_audio_stream_shutdown(&stream);
    assert(ctx.shutdown_calls == 1u);
}

static void test_ping_pong_multi_chunk_and_eof_drain(void) {
    static M3dsAudioStream stream;
    static MockStreamBackendCtx ctx;
    static int16_t stereo_pcm[10000u * 2u];

    for (uint32_t f = 0u; f < 10000u; ++f) {
        stereo_pcm[f * 2u] = (int16_t)(f & 0x3fffu);
        stereo_pcm[f * 2u + 1u] = (int16_t)(-(int32_t)(f & 0x3fffu));
    }

    M3dsAudioStreamBackend backend = make_mock_backend(&ctx, M3DS_AUDIO_STATE_READY, 0u);
    assert(m3ds_audio_stream_init(&stream, &backend));

    M3dsAudioStreamFormat fmt = {22050u, 2u};
    MemPcmSource mem = {stereo_pcm, 10000u, 2u, 0u, 0u, 0, 0, 0, 0};
    M3dsAudioStreamSource src = make_mem_source(&mem);
    assert(m3ds_audio_stream_start(&stream, &fmt, &src, false));
    assert(m3ds_audio_stream_is_playing(&stream));
    assert(ctx.last_sample_rate == 22050u);
    assert(ctx.last_channels == 2u);
    assert(near_f(ctx.gain_left, 0.7f));
    assert(near_f(ctx.gain_right, 0.7f));

    /* Primed slots 0 and 1 with 4096 + 4096 = 8192 frames. */
    M3dsAudioStreamStats stats;
    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.chunks_submitted == 2u);
    assert(stats.frames_submitted == 8192u);
    assert(ctx.queued_count == 2u);

    /* Finish slot 0 -> harvests 4096 frames and pulls final 1808 frames into slot 0. */
    mock_finish_oldest_slot(&ctx);
    m3ds_audio_stream_update(&stream);
    assert(m3ds_audio_stream_is_playing(&stream));
    assert(m3ds_audio_stream_position_frames(&stream) == 4096u);
    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.chunks_completed == 1u);
    assert(stats.chunks_submitted == 3u);
    assert(stats.frames_submitted == 10000u);

    /* Finish slot 1 -> stream still playing while slot 0 holds final 1808 frames. */
    mock_finish_oldest_slot(&ctx);
    m3ds_audio_stream_update(&stream);
    assert(m3ds_audio_stream_is_playing(&stream));
    assert(m3ds_audio_stream_position_frames(&stream) == 8192u);

    /* Finish final slot 0 -> natural EOF drain closes source and stops. */
    mock_finish_oldest_slot(&ctx);
    m3ds_audio_stream_update(&stream);
    assert(!m3ds_audio_stream_is_playing(&stream));
    assert(mem.closes == 1);
    assert(m3ds_audio_stream_position_frames(&stream) == 10000u);
    double sec = m3ds_audio_stream_position_seconds(&stream);
    assert(sec > 0.453 && sec < 0.454);

    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.frames_submitted == 10000u);
    assert(stats.frames_completed == 10000u);
    assert(stats.chunks_completed == 3u);
    assert(stats.tracks_completed == 1u);
    assert(stats.underruns == 0u);
    assert(memcmp(ctx.rendered_samples, stereo_pcm, sizeof(stereo_pcm)) == 0);

    m3ds_audio_stream_shutdown(&stream);
}

static void test_looping_empty_underrun_error_and_controls(void) {
    static M3dsAudioStream stream;
    static MockStreamBackendCtx ctx;
    static int16_t short_pcm[500];
    for (int i = 0; i < 500; ++i) {
        short_pcm[i] = (int16_t)(i * 3 - 700);
    }

    M3dsAudioStreamBackend backend = make_mock_backend(&ctx, M3DS_AUDIO_STATE_READY, 0u);
    assert(m3ds_audio_stream_init(&stream, &backend));
    M3dsAudioStreamFormat fmt = {22050u, 1u};

    /* 0-frame stream must not infinite-loop even with looping=true. */
    MemPcmSource empty_mem = {short_pcm, 0u, 1u, 0u, 0u, 0, 0, 0, 0};
    M3dsAudioStreamSource empty_src = make_mem_source(&empty_mem);
    assert(!m3ds_audio_stream_start(&stream, &fmt, &empty_src, true));
    assert(empty_mem.closes == 1);
    assert(empty_mem.rewinds == 1);

    /* Short 500-frame looping stream primes chunk 0 + rewinds immediately for chunk 1. */
    MemPcmSource loop_mem = {short_pcm, 500u, 1u, 0u, 0u, 0, 0, 0, 0};
    M3dsAudioStreamSource loop_src = make_mem_source(&loop_mem);
    assert(m3ds_audio_stream_start(&stream, &fmt, &loop_src, true));
    M3dsAudioStreamStats stats;
    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.loops_completed == 1u);
    assert(stats.chunks_submitted == 2u);

    /* Simulate underrun by finishing BOTH hardware slots before update(). */
    mock_finish_oldest_slot(&ctx);
    mock_finish_oldest_slot(&ctx);
    m3ds_audio_stream_update(&stream);
    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.underruns == 1u);
    assert(stats.loops_completed == 3u);

    /* Volume, master, mute, pause controls. */
    m3ds_audio_stream_set_volume(&stream, 2.0f);
    assert(near_f(m3ds_audio_stream_volume(&stream), 1.0f));
    m3ds_audio_stream_set_master_volume(&stream, 0.5f);
    assert(near_f(m3ds_audio_stream_master_volume(&stream), 0.5f));
    assert(near_f(ctx.gain_left, 0.5f));

    m3ds_audio_stream_set_volume(&stream, NAN);
    assert(near_f(m3ds_audio_stream_volume(&stream), 0.0f));
    assert(near_f(ctx.gain_left, 0.0f));

    m3ds_audio_stream_set_volume(&stream, 0.8f);
    m3ds_audio_stream_set_muted(&stream, true);
    assert(m3ds_audio_stream_is_muted(&stream));
    assert(near_f(ctx.gain_left, 0.0f));
    m3ds_audio_stream_set_muted(&stream, false);
    assert(near_f(ctx.gain_left, 0.4f));

    m3ds_audio_stream_set_paused(&stream, true);
    assert(m3ds_audio_stream_is_paused(&stream));
    assert(ctx.paused);
    mock_finish_oldest_slot(&ctx);
    m3ds_audio_stream_update(&stream); /* No refill while paused. */
    m3ds_audio_stream_set_paused(&stream, false);
    assert(!ctx.paused);

    m3ds_audio_stream_set_looping(&stream, false);
    assert(!m3ds_audio_stream_is_looping(&stream));
    m3ds_audio_stream_stop(&stream);
    assert(!m3ds_audio_stream_is_playing(&stream));
    assert(loop_mem.closes == 1);

    /* Read error during priming fails cleanly and closes source. */
    MemPcmSource prime_err_mem = {short_pcm, 500u, 1u, 0u, 200u, 2, 0, 0, 0};
    M3dsAudioStreamSource prime_err_src = make_mem_source(&prime_err_mem);
    assert(!m3ds_audio_stream_start(&stream, &fmt, &prime_err_src, false));
    assert(!m3ds_audio_stream_is_playing(&stream));
    assert(prime_err_mem.closes == 1);
    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.read_errors == 1u);

    /* Mid-stream read error during update() stops cleanly and closes source. */
    MemPcmSource err_mem = {short_pcm, 500u, 1u, 0u, 200u, 3, 0, 0, 0};
    M3dsAudioStreamSource err_src = make_mem_source(&err_mem);
    assert(m3ds_audio_stream_start(&stream, &fmt, &err_src, false));
    mock_finish_oldest_slot(&ctx);
    m3ds_audio_stream_update(&stream);
    assert(!m3ds_audio_stream_is_playing(&stream));
    assert(err_mem.closes == 1);
    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.read_errors == 2u);

    m3ds_audio_stream_shutdown(&stream);
}

static void test_stdio_pcm_file_streaming(void) {
    static M3dsAudioStream stream;
    static MockStreamBackendCtx ctx;
    static int16_t file_pcm[6000];
    const char *path = "build/host/test_music_stream.pcm";

    for (int i = 0; i < 6000; ++i) {
        file_pcm[i] = (int16_t)((i * 17) - 30000);
    }

    FILE *fp = fopen(path, "wb");
    assert(fp != NULL);
    assert(fwrite(file_pcm, sizeof(int16_t), 6000u, fp) == 6000u);
    fclose(fp);

    M3dsAudioStdioStreamSource missing_stdio;
    M3dsAudioStreamSource missing_source;
    assert(!m3ds_audio_stream_source_open_stdio(
        &missing_stdio, "build/host/missing_stream.pcm", 1u, &missing_source));

    M3dsAudioStdioStreamSource stdio_src;
    M3dsAudioStreamSource source;
    assert(m3ds_audio_stream_source_open_stdio(&stdio_src, path, 1u, &source));

    M3dsAudioStreamBackend backend = make_mock_backend(&ctx, M3DS_AUDIO_STATE_READY, 0u);
    assert(m3ds_audio_stream_init(&stream, &backend));

    M3dsAudioStreamFormat fmt = {22050u, 1u};
    assert(m3ds_audio_stream_start(&stream, &fmt, &source, false));
    for (int step = 0; step < 4 && m3ds_audio_stream_is_playing(&stream); ++step) {
        mock_finish_oldest_slot(&ctx);
        m3ds_audio_stream_update(&stream);
    }
    assert(!m3ds_audio_stream_is_playing(&stream));
    assert(stdio_src.file == NULL);
    assert(ctx.rendered_sample_count == 6000u);
    assert(memcmp(ctx.rendered_samples, file_pcm, sizeof(file_pcm)) == 0);

    m3ds_audio_stream_shutdown(&stream);
    remove(path);
}

int main(void) {
    test_pinned_music_catalog();
    test_disabled_and_fallback_no_dsp();
    test_ping_pong_multi_chunk_and_eof_drain();
    test_looping_empty_underrun_error_and_controls();
    test_stdio_pcm_file_streaming();
    printf("test_audio_stream: all assertions passed.\n");
    return 0;
}
