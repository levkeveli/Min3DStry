package port.concurrent;

public final class RejectedExecutionException extends RuntimeException {
    public RejectedExecutionException(String message) {
        super(message);
    }
}
