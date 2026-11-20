package port.concurrent;

public final class Executors {
    private Executors() {
    }

    public static ExecutorService newFixedThreadPool(int threads) {
        return newFixedThreadPool(threads, Thread::new);
    }

    public static ExecutorService newFixedThreadPool(int threads, ThreadFactory factory) {
        return new FixedExecutor(threads, factory);
    }

    public static ExecutorService newSingleThreadExecutor() {
        return newFixedThreadPool(1);
    }

    public static ExecutorService newSingleThreadExecutor(ThreadFactory factory) {
        return newFixedThreadPool(1, factory);
    }
}
