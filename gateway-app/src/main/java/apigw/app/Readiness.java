package apigw.app;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Readiness = a valid configuration snapshot is loaded and the gateway is not shutting down. The
 * gateway is never ready with an empty config; until the first snapshot is published,
 * {@link #reason()} says why. Shutting down is sticky: a later publish does not make it ready again.
 */
final class Readiness {

    static final String SHUTTING_DOWN = "shutting down";

    private final AtomicReference<String> notReadyReason = new AtomicReference<>("no configuration snapshot loaded");

    boolean ready() {
        return notReadyReason.get() == null;
    }

    /** Why the gateway is not ready, or {@code null} when it is. */
    String reason() {
        return notReadyReason.get();
    }

    /** A snapshot was published. No effect once shutdown started. */
    void markReady() {
        notReadyReason.updateAndGet(r -> SHUTTING_DOWN.equals(r) ? r : null);
    }

    void markNotReady(String reason) {
        notReadyReason.updateAndGet(r -> SHUTTING_DOWN.equals(r) ? r : reason);
    }

    void markShuttingDown() {
        notReadyReason.set(SHUTTING_DOWN);
    }
}
