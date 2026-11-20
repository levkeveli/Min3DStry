package port.concurrent;

@FunctionalInterface
public interface ThreadFactory {
    Thread newThread(Runnable task);
}
