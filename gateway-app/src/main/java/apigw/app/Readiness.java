package apigw.app;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Readiness = a valid configuration snapshot is loaded. The gateway is never ready with an empty
 * config; until the config worker publishes its first snapshot, {@link #reason()} says why.
 */
final class Readiness {

    private final AtomicReference<String> notReadyReason = new AtomicReference<>("no configuration snapshot loaded");

    boolean ready() {
        return notReadyReason.get() == null;
    }

    /** Why the gateway is not ready, or {@code null} when it is. */
    String reason() {
        return notReadyReason.get();
    }

    void markReady() {
        notReadyReason.set(null);
    }

    void markNotReady(String reason) {
        notReadyReason.set(reason);
    }
}
