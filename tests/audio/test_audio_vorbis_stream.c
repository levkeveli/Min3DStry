#include "audio/audio_vorbis_stream.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct MockBackendCtx {
    M3dsAudioBackendState init_state;
    uint32_t last_sample_rate;
    uint8_t last_channels;
    bool slot_busy[M3DS_AUDIO_STREAM_BUFFER_COUNT];
    uint32_t slot_frames[M3DS_AUDIO_STREAM_BUFFER_COUNT];
    uint32_t queued_slots[64];
    uint32_t queued_count;
    int16_t rendered_samples[65536];
    size_t rendered_sample_count;
} MockBackendCtx;

static M3dsAudioBackendState mock_init(void *raw, uint32_t *out_rc) {
    MockBackendCtx *ctx = (MockBackendCtx *)raw;
    if (out_rc) {
        *out_rc = 0u;
    }
    return ctx->init_state;
}

static void mock_shutdown(void *raw) {
    (void)raw;
}

static bool mock_configure(
    void *raw,
    uint32_t sample_rate,
    uint8_t channels,
    float gain_left,
    float gain_right) {
    MockBackendCtx *ctx = (MockBackendCtx *)raw;
    (void)gain_left;
    (void)gain_right;
    ctx->last_sample_rate = sample_rate;
    ctx->last_channels = channels;
    ctx->queued_count = 0u;
    ctx->rendered_sample_count = 0u;
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        ctx->slot_busy[i] = false;
        ctx->slot_frames[i] = 0u;
    }
    return true;
}

static bool mock_buffer_is_free(void *raw, uint32_t slot) {
    const MockBackendCtx *ctx = (const MockBackendCtx *)raw;
    return slot < M3DS_AUDIO_STREAM_BUFFER_COUNT && !ctx->slot_busy[slot];
}

static bool mock_buffer_submit(
    void *raw,
    uint32_t slot,
    const int16_t *samples,
    uint32_t frame_count) {
    MockBackendCtx *ctx = (MockBackendCtx *)raw;
    if (slot >= M3DS_AUDIO_STREAM_BUFFER_COUNT || ctx->slot_busy[slot]) {
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

static void mock_set_mix(void *raw, float gain_left, float gain_right) {
    (void)raw;
    (void)gain_left;
    (void)gain_right;
}

static void mock_set_paused(void *raw, bool paused) {
    (void)raw;
    (void)paused;
}

static void mock_stop(void *raw) {
    MockBackendCtx *ctx = (MockBackendCtx *)raw;
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        ctx->slot_busy[i] = false;
        ctx->slot_frames[i] = 0u;
    }
}

static void mock_complete_next(MockBackendCtx *ctx, uint32_t *completed_index) {
    assert(*completed_index < ctx->queued_count);
    uint32_t slot = ctx->queued_slots[(*completed_index)++];
    assert(ctx->slot_busy[slot]);
    ctx->slot_busy[slot] = false;
}

static M3dsAudioStreamBackend make_mock_backend(MockBackendCtx *ctx) {
    M3dsAudioStreamBackend backend = {
        .ctx = ctx,
        .name = "mock-vorbis-stream",
        .init = mock_init,
        .shutdown = mock_shutdown,
        .configure = mock_configure,
        .buffer_is_free = mock_buffer_is_free,
        .buffer_submit = mock_buffer_submit,
        .set_mix = mock_set_mix,
        .set_paused = mock_set_paused,
        .stop = mock_stop,
    };
    return backend;
}

typedef struct MockVorbisDecoder {
    uint32_t total_bytes;
    uint32_t cursor;
    const uint32_t *chunk_schedule;
    size_t schedule_len;
    size_t schedule_idx;
    bool inject_error;
    int32_t error_code;
    bool inject_overrun;
    uint32_t switch_bitstream_at_byte;
    int alt_bitstream;
    bool fail_seek;
    uint32_t seek_calls;
    uint32_t close_calls;
} MockVorbisDecoder;

static uint8_t pattern_byte_at(uint32_t byte_offset) {
    uint32_t sample_idx = byte_offset >> 1u;
    int16_t val = (int16_t)((int32_t)(sample_idx % 20000u) - 10000);
    return (byte_offset & 1u) == 0u
               ? (uint8_t)((uint16_t)val & 0xFFu)
               : (uint8_t)(((uint16_t)val >> 8u) & 0xFFu);
}

static int16_t expected_sample(uint32_t sample_idx) {
    return (int16_t)((int32_t)(sample_idx % 20000u) - 10000);
}

static int32_t mock_vorbis_read_pcm(
    void *raw_ctx,
    uint8_t *dst_bytes,
    uint32_t max_bytes,
    int *out_bitstream) {
    MockVorbisDecoder *dec = (MockVorbisDecoder *)raw_ctx;
    if (dec->inject_error) {
        return dec->error_code;
    }
    if (dec->inject_overrun) {
        *out_bitstream = 0;
        return (int32_t)(max_bytes + 4u);
    }
    if (dec->cursor >= dec->total_bytes) {
        *out_bitstream = 0;
        return 0;
    }

    uint32_t avail = dec->total_bytes - dec->cursor;
    uint32_t take = avail < max_bytes ? avail : max_bytes;
    if (dec->chunk_schedule && dec->schedule_len > 0u) {
        uint32_t sched = dec->chunk_schedule[dec->schedule_idx % dec->schedule_len];
        dec->schedule_idx += 1u;
        if (sched < take) {
            take = sched;
        }
    }

    if (dec->switch_bitstream_at_byte > 0u &&
        dec->cursor >= dec->switch_bitstream_at_byte) {
        *out_bitstream = dec->alt_bitstream;
    } else {
        *out_bitstream = 0;
    }

    for (uint32_t i = 0u; i < take; ++i) {
        dst_bytes[i] = pattern_byte_at(dec->cursor + i);
    }
    dec->cursor += take;
    return (int32_t)take;
}

static bool mock_vorbis_seek_zero(void *raw_ctx) {
    MockVorbisDecoder *dec = (MockVorbisDecoder *)raw_ctx;
    dec->seek_calls += 1u;
    if (dec->fail_seek) {
        return false;
    }
    dec->cursor = 0u;
    dec->schedule_idx = 0u;
    return true;
}

static void mock_vorbis_close(void *raw_ctx) {
    MockVorbisDecoder *dec = (MockVorbisDecoder *)raw_ctx;
    dec->close_calls += 1u;
}

static void test_music_romfs_paths_and_init_validation(void) {
    static const char *const expected[M3DS_AUDIO_MUSIC_COUNT] = {
        "romfs:/audio/music/boss1.ogg",
        "romfs:/audio/music/boss2.ogg",
        "romfs:/audio/music/editor.ogg",
        "romfs:/audio/music/fine.ogg",
        "romfs:/audio/music/game1.ogg",
        "romfs:/audio/music/game2.ogg",
        "romfs:/audio/music/game3.ogg",
        "romfs:/audio/music/game4.ogg",
        "romfs:/audio/music/game5.ogg",
        "romfs:/audio/music/game6.ogg",
        "romfs:/audio/music/game7.ogg",
        "romfs:/audio/music/game8.ogg",
        "romfs:/audio/music/game9.ogg",
        "romfs:/audio/music/land.ogg",
        "romfs:/audio/music/launch.ogg",
        "romfs:/audio/music/menu.ogg",
    };

    for (uint32_t i = 0u; i < M3DS_AUDIO_MUSIC_COUNT; ++i) {
        const char *path = m3ds_audio_vorbis_music_romfs_path((M3dsUpstreamMusic)i);
        assert(path != NULL);
        assert(strcmp(path, expected[i]) == 0);
    }
    assert(m3ds_audio_vorbis_music_romfs_path(M3DS_AUDIO_MUSIC_COUNT) == NULL);
    assert(m3ds_audio_vorbis_music_romfs_path((M3dsUpstreamMusic)-1) == NULL);

    M3dsVorbisStreamSource vorbis_src;
    M3dsVorbisStreamSource untouched_vorbis;
    memset(&vorbis_src, 0x5A, sizeof(vorbis_src));
    memset(&untouched_vorbis, 0x5A, sizeof(untouched_vorbis));
    M3dsAudioStreamSource stream_src;
    M3dsAudioStreamSource untouched_stream;
    memset(&stream_src, 0xA5, sizeof(stream_src));
    memset(&untouched_stream, 0xA5, sizeof(untouched_stream));
    M3dsVorbisDecoderOps good_ops = {
        NULL,
        mock_vorbis_read_pcm,
        mock_vorbis_seek_zero,
        mock_vorbis_close,
    };
    M3dsVorbisDecoderOps null_read = {NULL, NULL, NULL, NULL};

    assert(!m3ds_audio_vorbis_stream_init(
        NULL, &good_ops, (M3dsAudioStreamFormat){44100u, 2u}, &stream_src));
    assert(!m3ds_audio_vorbis_stream_init(
        &vorbis_src, NULL, (M3dsAudioStreamFormat){44100u, 2u}, &stream_src));
    assert(!m3ds_audio_vorbis_stream_init(
        &vorbis_src, &null_read, (M3dsAudioStreamFormat){44100u, 2u}, &stream_src));
    assert(!m3ds_audio_vorbis_stream_init(
        &vorbis_src, &good_ops, (M3dsAudioStreamFormat){44100u, 2u}, NULL));
    assert(!m3ds_audio_vorbis_stream_init(
        &vorbis_src, &good_ops, (M3dsAudioStreamFormat){44100u, 0u}, &stream_src));
    assert(!m3ds_audio_vorbis_stream_init(
        &vorbis_src, &good_ops, (M3dsAudioStreamFormat){44100u, 3u}, &stream_src));
    assert(!m3ds_audio_vorbis_stream_init(
        &vorbis_src, &good_ops, (M3dsAudioStreamFormat){3999u, 2u}, &stream_src));
    assert(!m3ds_audio_vorbis_stream_init(
        &vorbis_src, &good_ops, (M3dsAudioStreamFormat){48001u, 2u}, &stream_src));

    /* Failed init must leave caller structs untouched. */
    assert(memcmp(&vorbis_src, &untouched_vorbis, sizeof(vorbis_src)) == 0);
    assert(memcmp(&stream_src, &untouched_stream, sizeof(stream_src)) == 0);

    /* Optional seek_zero = NULL produces a non-seekable M3dsAudioStreamSource. */
    M3dsVorbisDecoderOps no_seek = {NULL, mock_vorbis_read_pcm, NULL, NULL};
    assert(m3ds_audio_vorbis_stream_init(
        &vorbis_src, &no_seek, (M3dsAudioStreamFormat){22050u, 1u}, &stream_src));
    assert(vorbis_src.open);
    assert(stream_src.rewind == NULL);
    assert(stream_src.close != NULL);
    stream_src.close(stream_src.ctx);
    assert(!vorbis_src.open);
    /* Second close is idempotent. */
    stream_src.close(stream_src.ctx);
}

static void test_fragmented_reads_and_audio_stream_loop_drain(void) {
    static const uint32_t odd_schedule[] = {7u, 13u, 512u, 3u, 1024u};
    MockVorbisDecoder dec;
    memset(&dec, 0, sizeof(dec));
    /* 10,000 stereo frames * 4 bytes/frame = 40,000 bytes (crosses 4096-frame chunks cleanly). */
    dec.total_bytes = 10000u * 4u;
    dec.chunk_schedule = odd_schedule;
    dec.schedule_len = sizeof(odd_schedule) / sizeof(odd_schedule[0]);

    M3dsVorbisDecoderOps ops = {
        &dec,
        mock_vorbis_read_pcm,
        mock_vorbis_seek_zero,
        mock_vorbis_close,
    };
    M3dsVorbisStreamSource vorbis_src;
    M3dsAudioStreamSource src;
    assert(m3ds_audio_vorbis_stream_init(
        &vorbis_src, &ops, (M3dsAudioStreamFormat){44100u, 2u}, &src));

    static MockBackendCtx bctx;
    static M3dsAudioStream stream;
    memset(&bctx, 0, sizeof(bctx));
    bctx.init_state = M3DS_AUDIO_STATE_READY;
    M3dsAudioStreamBackend backend = make_mock_backend(&bctx);
    assert(m3ds_audio_stream_init(&stream, &backend));

    const M3dsAudioStreamFormat fmt = {44100u, 2u};
    assert(m3ds_audio_stream_start(&stream, &fmt, &src, true));
    assert(bctx.queued_count == 2u); /* 4096 + 4096 frames primed */

    uint32_t completed_idx = 0u;
    /* Complete slot 0 -> fills trailing 1808 frames into slot 0 (total 10,000 frames submitted). */
    mock_complete_next(&bctx, &completed_idx);
    m3ds_audio_stream_update(&stream);
    assert(bctx.queued_count == 3u);
    assert(dec.seek_calls == 0u);

    /* Complete slot 1 -> hits EOF, calls rewind(), and submits chunk 0 (4096 frames) of pass 2. */
    mock_complete_next(&bctx, &completed_idx);
    m3ds_audio_stream_update(&stream);
    assert(dec.seek_calls == 1u);
    assert(vorbis_src.rewind_calls == 1u);
    assert(m3ds_audio_stream_is_playing(&stream));

    /* Disable looping and drain pass 2 cleanly to natural EOF. */
    m3ds_audio_stream_set_looping(&stream, false);
    uint32_t drain_steps = 0u;
    while (m3ds_audio_stream_is_playing(&stream)) {
        assert(drain_steps < 16u);
        mock_complete_next(&bctx, &completed_idx);
        m3ds_audio_stream_update(&stream);
        drain_steps += 1u;
    }
    assert(completed_idx == bctx.queued_count);
    assert(!m3ds_audio_stream_is_playing(&stream));
    assert(dec.close_calls == 1u);
    assert(!vorbis_src.open);
    assert(vorbis_src.decode_errors == 0u);
    assert(vorbis_src.bitstream_errors == 0u);
    assert(vorbis_src.read_calls > 10u);

    /* Two complete 10,000-frame stereo passes = 40,000 samples rendered bit-exactly. */
    assert(bctx.rendered_sample_count == 40000u);
    for (uint32_t i = 0u; i < 40000u; ++i) {
        assert(bctx.rendered_samples[i] == expected_sample(i % 20000u));
    }

    /* Stopping cleanly handles already-closed source without calling dec.close twice. */
    m3ds_audio_stream_shutdown(&stream);
    assert(dec.close_calls == 1u);
}

static void test_vorbis_error_and_edge_cases(void) {
    /* 1. Bitstream switch mid-stream fails cleanly through M3dsAudioStream. */
    static const uint32_t split_schedule[] = {1024u};
    MockVorbisDecoder dec;
    memset(&dec, 0, sizeof(dec));
    dec.total_bytes = 20000u;
    dec.chunk_schedule = split_schedule;
    dec.schedule_len = 1u;
    dec.switch_bitstream_at_byte = 4096u;
    dec.alt_bitstream = 1;

    M3dsVorbisDecoderOps ops = {
        &dec,
        mock_vorbis_read_pcm,
        mock_vorbis_seek_zero,
        mock_vorbis_close,
    };
    M3dsVorbisStreamSource vorbis_src;
    M3dsAudioStreamSource src;
    assert(m3ds_audio_vorbis_stream_init(
        &vorbis_src, &ops, (M3dsAudioStreamFormat){44100u, 2u}, &src));

    static MockBackendCtx bctx;
    static M3dsAudioStream stream;
    memset(&bctx, 0, sizeof(bctx));
    bctx.init_state = M3DS_AUDIO_STATE_READY;
    M3dsAudioStreamBackend backend = make_mock_backend(&bctx);
    assert(m3ds_audio_stream_init(&stream, &backend));

    const M3dsAudioStreamFormat fmt = {44100u, 2u};
    assert(!m3ds_audio_stream_start(&stream, &fmt, &src, false));
    assert(vorbis_src.bitstream_errors == 1u);
    assert(dec.close_calls == 1u);

    M3dsAudioStreamStats stats;
    m3ds_audio_stream_get_stats(&stream, &stats);
    assert(stats.read_errors == 1u);
    assert(!m3ds_audio_stream_is_playing(&stream));
    m3ds_audio_stream_shutdown(&stream);

    /* 2. Negative Tremor error return (e.g. OV_HOLE / OV_EBADLINK). */
    int16_t buf[64];
    uint32_t out_frames = 99u;
    memset(&dec, 0, sizeof(dec));
    dec.inject_error = true;
    dec.error_code = -131;
    assert(m3ds_audio_vorbis_stream_init(
        &vorbis_src, &ops, (M3dsAudioStreamFormat){44100u, 2u}, &src));
    assert(!src.read_frames(src.ctx, buf, 16u, &out_frames));
    assert(out_frames == 0u);
    assert(vorbis_src.decode_errors == 1u);
    src.close(src.ctx);

    /* 3. Truncated mid-frame EOF on 4-byte stereo frame boundaries is rejected. */
    memset(&dec, 0, sizeof(dec));
    dec.total_bytes = 6u; /* 1 full stereo frame + 2 trailing half-frame bytes */
    out_frames = 99u;
    assert(m3ds_audio_vorbis_stream_init(
        &vorbis_src, &ops, (M3dsAudioStreamFormat){44100u, 2u}, &src));
    assert(!src.read_frames(src.ctx, buf, 16u, &out_frames));
    assert(out_frames == 0u);
    assert(vorbis_src.decode_errors == 1u);
    src.close(src.ctx);

    /* 4. Decoder returning more bytes than requested is rejected. */
    memset(&dec, 0, sizeof(dec));
    dec.inject_overrun = true;
    out_frames = 99u;
    assert(m3ds_audio_vorbis_stream_init(
        &vorbis_src, &ops, (M3dsAudioStreamFormat){44100u, 2u}, &src));
    assert(!src.read_frames(src.ctx, buf, 16u, &out_frames));
    assert(out_frames == 0u);
    assert(vorbis_src.decode_errors == 1u);
    src.close(src.ctx);

    /* 5. Rewind failure leaves stream open until closed. */
    memset(&dec, 0, sizeof(dec));
    dec.total_bytes = 16u;
    dec.fail_seek = true;
    assert(m3ds_audio_vorbis_stream_init(
        &vorbis_src, &ops, (M3dsAudioStreamFormat){44100u, 2u}, &src));
    assert(src.read_frames(src.ctx, buf, 4u, &out_frames));
    assert(out_frames == 4u);
    assert(!src.rewind(src.ctx));
    assert(dec.seek_calls == 1u);
    assert(vorbis_src.rewind_calls == 0u);
    src.close(src.ctx);
    assert(dec.close_calls == 1u);
}

int main(void) {
    test_music_romfs_paths_and_init_validation();
    test_fragmented_reads_and_audio_stream_loop_drain();
    test_vorbis_error_and_edge_cases();
    printf("test_audio_vorbis_stream: all assertions passed.\n");
    return 0;
}
