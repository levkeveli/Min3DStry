import java.util.Arrays;

/** Measures wall-clock intervals between completed frames in the playable game. */
final class UpstreamFrameTimeWindow {
    private static final long windowNanos = 5_000_000_000L;
    private final long[] intervals = new long[330];
    private long lastFrame;
    private long duration;
    private int frames;
    private boolean started;

    record Sample(int frames, long durationNanos, long p95Nanos) {
        double fps() {
            return frames * 1_000_000_000d / durationNanos;
        }
    }

    Sample frameCompleted(long now) {
        if (!started) {
            lastFrame = now;
            started = true;
            return null;
        }
        long interval = now - lastFrame;
        if (interval <= 0) return null;
        lastFrame = now;
        intervals[frames++] = interval;
        duration += interval;
        if (duration < windowNanos && frames < intervals.length) return null;

        Arrays.sort(intervals, 0, frames);
        int p95Index = (95 * frames + 99) / 100 - 1;
        var sample = new Sample(frames, duration, intervals[p95Index]);
        frames = 0;
        duration = 0;
        return sample;
    }

    void reset() {
        started = false;
        frames = 0;
        duration = 0;
    }
}
