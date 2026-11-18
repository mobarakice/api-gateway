package apigw.core.snapshot;

import java.util.Objects;

/**
 * The single atomic reference the request path reads (one volatile load per request).
 * Only the config worker publishes; publishing is the one and only swap of a complete snapshot.
 */
public final class SnapshotHolder {

    private volatile Snapshot current;

    /** Current snapshot, or {@code null} before the first publish. */
    public Snapshot current() {
        return current;
    }

    public void publish(Snapshot snapshot) {
        current = Objects.requireNonNull(snapshot);
    }
}
