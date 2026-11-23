package port.concurrent;

import arc.util.OS;
import arc.util.Threads;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class UpstreamExecutorProbe {
    private static volatile byte[] liveAllocation;

    public static void main(String[] args) throws Exception {
        java.util.concurrent.ExecutorService executor = Threads.executor("ARM11 probe");
        try {
            if (OS.cores != 1 || executor.submit(() -> 37).get() != 37) {
                throw new IllegalStateException("Unexpected processor count or task result");
            }
            checkFailure(executor);
            checkCancellation(executor);
        } finally {
            executor.shutdownNow();
        }
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Executor did not shut down");
        }
        try {
            executor.execute(() -> {});
            throw new IllegalStateException("Executor accepted work after shutdown");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            System.out.println("UPSTREAM_EXECUTOR workers=1 result=37 exception=ok cancel=ok timeout=ok shutdown=ok");
        }
        checkPool(Threads.boundedExecutor("probe-bounded", 1), true);
        checkPool(Threads.unboundedExecutor("probe-unbounded", 0), false);
        System.out.println("UPSTREAM_EXECUTOR_POOLS bounded=ok unbounded=ok");
        checkMonitorAcrossGc(new Object());
        checkMonitorAcrossGc(new int[1]);
        System.out.println("UPSTREAM_EXECUTOR_MONITOR_GC object=ok array=ok");
    }

    private static void checkMonitorAcrossGc(Object monitor) throws Exception {
        int identity = System.identityHashCode(monitor);
        var pool = Executors.newSingleThreadExecutor();
        try {
            synchronized (monitor) {
                // Exceed the generated probe's initial heap via ordinary safepoints.
                for (int i = 0; i < 1024; i++) {
                    liveAllocation = new byte[16 * 1024];
                }
                if (System.identityHashCode(monitor) != identity) {
                    throw new IllegalStateException("Monitor identity changed during collection");
                }
            }
            var handoff = pool.submit(() -> {
                synchronized (monitor) {
                    return System.identityHashCode(monitor);
                }
            });
            if (handoff.get(2, TimeUnit.SECONDS) != identity) {
                throw new IllegalStateException("Monitor identity changed at handoff");
            }
        } finally {
            liveAllocation = null;
            pool.shutdownNow();
        }
        if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Monitor handoff executor did not shut down");
        }
    }

    private static void checkPool(java.util.concurrent.ExecutorService pool, boolean bounded) throws Exception {
        try {
            var blocker = pool.submit(() -> {
                Thread.sleep(30_000);
                return 0;
            });
            var task = pool.submit(() -> 37);
            if (bounded) {
                try {
                    task.get(1, TimeUnit.MILLISECONDS);
                    throw new IllegalStateException("Bounded pool ran more than one worker");
                } catch (java.util.concurrent.TimeoutException expected) {
                    blocker.cancel(true);
                }
            }
            if (task.get(2, TimeUnit.SECONDS) != 37) {
                throw new IllegalStateException("Pool did not return the queued result");
            }
        } finally {
            pool.shutdownNow();
        }
        if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Pool did not shut down");
        }
    }

    private static void checkFailure(java.util.concurrent.ExecutorService executor) throws Exception {
        var job = executor.submit(() -> {
            throw new IllegalArgumentException("worker error");
        });
        try {
            job.get();
            throw new IllegalStateException("Missing worker error");
        } catch (ExecutionException expected) {
            if (!(expected.getCause() instanceof IllegalArgumentException)) {
                throw expected;
            }
        }
    }

    private static void checkCancellation(java.util.concurrent.ExecutorService executor) throws Exception {
        var blocker = executor.submit(() -> {
            Thread.sleep(30_000);
            return 0;
        });
        try {
            var job = executor.submit(() -> 1);
            try {
                job.get(1, TimeUnit.MILLISECONDS);
                throw new IllegalStateException("Queued job finished before the running job");
            } catch (java.util.concurrent.TimeoutException expected) {
                if (!job.cancel(false) || !job.isCancelled() || !job.isDone()) {
                    throw new IllegalStateException("Worker cancellation failed");
                }
            }
            try {
                job.get();
                throw new IllegalStateException("Cancelled job returned normally");
            } catch (CancellationException expected) {
                // A cancelled Future reports cancellation even before it is dequeued.
            }
        } finally {
            blocker.cancel(true);
        }
    }
}
