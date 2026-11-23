#ifndef MIN3DSTRY_PLATFORM_FRAME_STATS_H
#define MIN3DSTRY_PLATFORM_FRAME_STATS_H

#include <stdbool.h>
#include <stdint.h>

/* A four-second window at 60 FPS; enough for a stable p95 without heap use. */
#ifndef M3DS_FRAME_STATS_WINDOW
#define M3DS_FRAME_STATS_WINDOW 256u
#endif

typedef struct {
  uint32_t intervals[M3DS_FRAME_STATS_WINDOW];
  uint32_t count;
  uint32_t write_index;
  uint64_t last_tick;
  bool started;
} M3dsFrameStats;

typedef struct {
  uint32_t frames;
  uint32_t fps_x100;
  uint32_t avg_ms_x100;
  uint32_t p95_ms_x100;
  uint32_t elapsed_ms;
} M3dsFrameStatsSummary;

void m3ds_frame_stats_reset(M3dsFrameStats *stats);
void m3ds_frame_stats_sample(M3dsFrameStats *stats, uint64_t now_tick);
bool m3ds_frame_stats_ready(const M3dsFrameStats *stats);
bool m3ds_frame_stats_summarize(const M3dsFrameStats *stats, uint32_t tick_hz,
                                M3dsFrameStatsSummary *out_summary);

#endif /* MIN3DSTRY_PLATFORM_FRAME_STATS_H */
