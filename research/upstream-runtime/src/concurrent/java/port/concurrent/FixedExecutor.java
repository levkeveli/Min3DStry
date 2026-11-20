package port.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

final class FixedExecutor implements ExecutorService {
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final List<Worker> workers = new ArrayList<>();
    private final int limit;
    private final ThreadFactory factory;
    private boolean shutdown;

    FixedExecutor(int limit, ThreadFactory factory) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
        this.limit = limit;
        this.factory = Objects.requireNonNull(factory);
    }

    @Override
    public synchronized void execute(Runnable job) {
        Objects.requireNonNull(job);
        if (shutdown) {
            throw new RejectedExecutionException("Executor has shut down");
        }
        // Start while holding the monitor so a worker cannot poll before the add.
        if (workers.size() < limit) {
            startWorker();
        }
        pending.add(job);
    }

    private void startWorker() {
        Worker worker = new Worker();
        worker.thread = factory.newThread(worker);
        if (worker.thread == null) {
            throw new RejectedExecutionException("Thread factory returned no worker");
        }
        workers.add(worker);
        try {
            worker.thread.start();
        } catch (RuntimeException | Error error) {
            workers.remove(worker);
            throw error;
        }
    }

    private synchronized Runnable nextJob() {
        Thread.interrupted();
        return pending.poll();
    }

    @Override
    public <T> Future<T> submit(Callable<T> callable) {
        Work<T> work = new Work<>(Objects.requireNonNull(callable));
        execute(work);
        return work;
    }

    @Override
    public Future<?> submit(Runnable job) {
        return submit(job, null);
    }

    @Override
    public <T> Future<T> submit(Runnable job, T result) {
        Objects.requireNonNull(job);
        return submit(() -> {
            job.run();
            return result;
        });
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
        notifyAll();
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> queued = new ArrayList<>(pending);
        pending.clear();
        for (Worker worker : workers) {
            worker.thread.interrupt();
        }
        notifyAll();
        return queued;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown && workers.isEmpty() && pending.isEmpty();
    }

    @Override
    public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long budget = unit.toNanos(timeout);
        long remaining = budget;
        long start = System.nanoTime();
        while (!isTerminated()) {
            if (remaining <= 0) {
                return false;
            }
            wait(roundUpMillis(remaining));
            remaining = budget - (System.nanoTime() - start);
        }
        return true;
    }

    private static long roundUpMillis(long nanos) {
        return 1 + (nanos - 1) / 1_000_000;
    }

    private final class Worker implements Runnable {
        Thread thread;

        @Override
        public void run() {
            try {
                Runnable job;
                while ((job = nextJob()) != null) {
                    job.run();
                }
            } finally {
                synchronized (FixedExecutor.this) {
                    workers.remove(this);
                    FixedExecutor.this.notifyAll();
                    if (!pending.isEmpty() && workers.size() < limit) {
                        startWorker();
                    }
                }
            }
        }
    }

    private static final class Work<T> implements Future<T>, Runnable {
        private Callable<T> job;
        private Thread runner;
        private boolean done;
        private boolean cancelled;
        private T result;
        private Throwable failure;

        Work(Callable<T> job) {
            this.job = job;
        }

        @Override
        public void run() {
            Callable<T> callable;
            synchronized (this) {
                if (done || runner != null) {
                    return;
                }
                runner = Thread.currentThread();
                callable = job;
            }
            T value = null;
            Throwable error = null;
            try {
                value = callable.call();
            } catch (Throwable thrown) {
                error = thrown;
            }
            synchronized (this) {
                runner = null;
                job = null;
                if (!done) {
                    result = value;
                    failure = error;
                    done = true;
                    notifyAll();
                }
            }
        }

        @Override
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            cancelled = done = true;
            job = null;
            if (mayInterruptIfRunning && runner != null) {
                runner.interrupt();
            }
            notifyAll();
            return true;
        }

        @Override
        public synchronized boolean isDone() {
            return done;
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }

        @Override
        public synchronized T get() throws InterruptedException, ExecutionException {
            while (!done) {
                wait();
            }
            return readResult();
        }

        @Override
        public synchronized T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            long budget = unit.toNanos(timeout);
            long remaining = budget;
            long start = System.nanoTime();
            while (!done) {
                if (remaining <= 0) {
                    throw new TimeoutException();
                }
                wait(roundUpMillis(remaining));
                remaining = budget - (System.nanoTime() - start);
            }
            return readResult();
        }

        private T readResult() throws ExecutionException {
            if (cancelled) {
                throw new CancellationException();
            }
            if (failure != null) {
                throw new ExecutionException(failure);
            }
            return result;
        }
    }
}
