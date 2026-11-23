#include "platform/frame_stats.h"

#include <string.h>

void m3ds_frame_stats_reset(M3dsFrameStats *stats) {
  if (!stats) {
    return;
  }
  memset(stats, 0, sizeof(*stats));
}

void m3ds_frame_stats_sample(M3dsFrameStats *stats, uint64_t now_tick) {
  if (!stats) {
    return;
  }
  if (!stats->started) {
    stats->last_tick = now_tick;
    stats->started = true;
    return;
  }

  uint64_t delta = now_tick - stats->last_tick;
  stats->last_tick = now_tick;
  if (delta == 0u) {
    return;
  }
  if (delta > UINT32_MAX) {
    delta = UINT32_MAX;
  }

  stats->intervals[stats->write_index] = (uint32_t)delta;
  stats->write_index = (stats->write_index + 1u) % M3DS_FRAME_STATS_WINDOW;
  if (stats->count < M3DS_FRAME_STATS_WINDOW) {
    stats->count++;
  }
}

bool m3ds_frame_stats_ready(const M3dsFrameStats *stats) {
  return stats && stats->count >= M3DS_FRAME_STATS_WINDOW;
}

bool m3ds_frame_stats_summarize(const M3dsFrameStats *stats, uint32_t tick_hz,
                                M3dsFrameStatsSummary *out_summary) {
  if (!stats || !out_summary || tick_hz == 0u || stats->count == 0u) {
    return false;
  }

  uint64_t elapsed_ticks = 0u;
  for (uint32_t i = 0u; i < stats->count; ++i) {
    elapsed_ticks += stats->intervals[i];
  }
  if (elapsed_ticks == 0u) {
    return false;
  }

  uint32_t sorted[M3DS_FRAME_STATS_WINDOW];
  memcpy(sorted, stats->intervals, stats->count * sizeof(sorted[0]));
  for (uint32_t i = 1u; i < stats->count; ++i) {
    uint32_t value = sorted[i];
    uint32_t j = i;
    while (j > 0u && sorted[j - 1u] > value) {
      sorted[j] = sorted[j - 1u];
      --j;
    }
    sorted[j] = value;
  }

  uint32_t p95_index = (stats->count * 95u + 99u) / 100u;
  if (p95_index == 0u) {
    p95_index = 1u;
  }
  p95_index--;

  uint64_t frames = stats->count;
  uint64_t frame_hz = (uint64_t)tick_hz * frames;
  uint64_t p95_ticks = sorted[p95_index];

  out_summary->frames = stats->count;
  out_summary->fps_x100 =
      (uint32_t)((frames * tick_hz * 100u + elapsed_ticks / 2u) / elapsed_ticks);
  out_summary->avg_ms_x100 =
      (uint32_t)((elapsed_ticks * 100000u + frame_hz / 2u) / frame_hz);
  out_summary->p95_ms_x100 =
      (uint32_t)((p95_ticks * 100000u + tick_hz / 2u) / tick_hz);
  out_summary->elapsed_ms =
      (uint32_t)((elapsed_ticks * 1000u + tick_hz / 2u) / tick_hz);
  return true;
}
