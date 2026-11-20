package port.concurrent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public interface Future<T> {
    boolean cancel(boolean mayInterruptIfRunning);
    boolean isCancelled();
    boolean isDone();
    T get() throws InterruptedException, ExecutionException;
    T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;
}
