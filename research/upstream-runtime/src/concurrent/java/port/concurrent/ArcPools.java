package port.concurrent;

import arc.util.Log;
import java.util.concurrent.TimeUnit;

/** Factories using this package's ExecutorService and ThreadFactory substitutions. */
public final class ArcPools {
    private ArcPools() {
    }

    public static ExecutorService bounded(String name, int max) {
        return new FixedExecutor(max, 0, max, TimeUnit.MINUTES.toNanos(1), false, factory(name));
    }

    public static ExecutorService unbounded(String name, int min) {
        return new FixedExecutor(min, min, Integer.MAX_VALUE, TimeUnit.MINUTES.toNanos(1), true, factory(name));
    }

    private static ThreadFactory factory(String name) {
        // Match the thread setup in the pinned Arc Threads factory.
        return job -> {
            Thread worker = name == null ? new Thread(job) : new Thread(job, name);
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((thread, failure) -> Log.err(failure));
            return worker;
        };
    }
}
