package apigw.core.plugin;

/** Runs in {@link Phase#LOG} once per exchange, after the response completed or failed. Must not block. */
public interface LogPlugin extends Plugin {

    void onComplete(Exchange exchange, int status, long durationNanos);
}
