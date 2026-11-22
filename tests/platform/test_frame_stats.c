#include "platform/frame_stats.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>

static void feed(M3dsFrameStats *stats, uint32_t frames, uint32_t interval,
                 uint64_t *now) {
  for (uint32_t i = 0u; i < frames; ++i) {
    *now += interval;
    m3ds_frame_stats_sample(stats, *now);
  }
}

static void test_steady_sixty_fps(void) {
  M3dsFrameStats stats;
  m3ds_frame_stats_reset(&stats);
  uint64_t now = 1234u;
  m3ds_frame_stats_sample(&stats, now);
  assert(!m3ds_frame_stats_ready(&stats));

  feed(&stats, M3DS_FRAME_STATS_WINDOW - 1u, 16u, &now);
  assert(!m3ds_frame_stats_ready(&stats));
  feed(&stats, 1u, 16u, &now);
  assert(m3ds_frame_stats_ready(&stats));

  M3dsFrameStatsSummary summary;
  assert(m3ds_frame_stats_summarize(&stats, 1000u, &summary));
  assert(summary.frames == M3DS_FRAME_STATS_WINDOW);
  assert(summary.fps_x100 == 6250u);
  assert(summary.avg_ms_x100 == 1600u);
  assert(summary.p95_ms_x100 == 1600u);
  assert(summary.elapsed_ms == 4096u);
}

static void test_p95_uses_nearest_rank(void) {
  M3dsFrameStats stats;
  m3ds_frame_stats_reset(&stats);
  uint64_t now = 0u;
  m3ds_frame_stats_sample(&stats, now);
  feed(&stats, 236u, 16u, &now);
  feed(&stats, 20u, 100u, &now);

  M3dsFrameStatsSummary summary;
  assert(m3ds_frame_stats_summarize(&stats, 1000u, &summary));
  assert(summary.frames == 256u);
  assert(summary.p95_ms_x100 == 10000u);
  assert(summary.avg_ms_x100 == 2256u);
}

static void test_zero_delta_and_reset(void) {
  M3dsFrameStats stats;
  m3ds_frame_stats_reset(&stats);
  m3ds_frame_stats_sample(&stats, 500u);
  m3ds_frame_stats_sample(&stats, 500u);
  assert(stats.count == 0u);
  m3ds_frame_stats_sample(&stats, 510u);
  assert(stats.count == 1u);
  assert(stats.intervals[0] == 10u);

  m3ds_frame_stats_reset(&stats);
  assert(stats.count == 0u);
  assert(!stats.started);
  assert(!m3ds_frame_stats_ready(&stats));
}

static void test_ring_keeps_latest_window(void) {
  M3dsFrameStats stats;
  m3ds_frame_stats_reset(&stats);
  uint64_t now = 0u;
  m3ds_frame_stats_sample(&stats, now);
  feed(&stats, 256u, 16u, &now);
  feed(&stats, 256u, 20u, &now);

  M3dsFrameStatsSummary summary;
  assert(m3ds_frame_stats_summarize(&stats, 1000u, &summary));
  assert(summary.frames == 256u);
  assert(summary.p95_ms_x100 == 2000u);
  assert(summary.avg_ms_x100 == 2000u);
}

static void test_invalid_inputs(void) {
  M3dsFrameStats stats;
  M3dsFrameStatsSummary summary;
  m3ds_frame_stats_reset(NULL);
  m3ds_frame_stats_sample(NULL, 1u);
  assert(!m3ds_frame_stats_ready(NULL));
  assert(!m3ds_frame_stats_summarize(NULL, 1000u, &summary));
  m3ds_frame_stats_reset(&stats);
  assert(!m3ds_frame_stats_summarize(&stats, 1000u, &summary));
  assert(!m3ds_frame_stats_summarize(&stats, 0u, &summary));
  m3ds_frame_stats_sample(&stats, 1u);
  m3ds_frame_stats_sample(&stats, 17u);
  assert(!m3ds_frame_stats_summarize(&stats, 1000u, NULL));
}

int main(void) {
  test_steady_sixty_fps();
  test_p95_uses_nearest_rank();
  test_zero_delta_and_reset();
  test_ring_keeps_latest_window();
  test_invalid_inputs();
  puts("test_frame_stats: all assertions passed.");
  return 0;
}
