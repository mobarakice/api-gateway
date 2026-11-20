package apigw.config.worker;

import java.time.Instant;

/**
 * What the config worker is doing, for {@code /configz}, the node heartbeat and logs. Immutable;
 * replaced as a whole.
 *
 * @param appliedRevision etcd revision of the snapshot being served (0 = none)
 * @param source          where the served snapshot came from: {@code etcd}, {@code lkg} or {@code none}
 * @param configError     why the newest etcd state was rejected; null when it was applied
 * @param etcdError       last etcd failure while not synced; null when connected
 */
public record WorkerStatus(State state, long appliedRevision, long etcdRevision, String source,
        String configError, String etcdError, Instant updatedAt) {

    /** Worker state. */
    public enum State {
        /** Loading the keyspace (boot or resync after compaction). */
        SYNCING,
        /** Watching etcd; the served snapshot is the newest valid state. */
        SERVING,
        /** etcd unreachable; serving the last applied or last-known-good snapshot. */
        SERVING_STALE,
        /** The newest etcd state was rejected; the previous snapshot keeps serving. */
        INVALID_CONFIG,
        /** No snapshot at all: not ready. */
        NO_CONFIG
    }

    static WorkerStatus initial() {
        return new WorkerStatus(State.NO_CONFIG, 0, 0, "none", null, null, Instant.now());
    }
}
