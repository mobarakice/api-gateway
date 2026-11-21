package apigw.controlplane.changes;

/**
 * Write limits (ADR 0020). etcd rejects transactions with more than {@code --max-txn-ops} operations
 * per branch (default 128) and requests over {@code --max-request-bytes} (default 1.5 MiB).
 *
 * @param maxTxnOps     etcd's --max-txn-ops; a publish adds {@value #PUBLISH_OVERHEAD} ops of its own
 * @param maxTxnBytes   bound on the encoded publish transaction (JSON, base64), below etcd's request limit
 * @param maxValueBytes bound on one stored object
 * @param historyLimit  published change sets kept under history/entries/ (older ones cannot be rolled back)
 */
public record Limits(int maxTxnOps, int maxTxnBytes, int maxValueBytes, int historyLimit) {

    /** history entry, history head, change-set delete, audit event, history trim. */
    public static final int PUBLISH_OVERHEAD = 5;

    public static final Limits DEFAULTS = new Limits(128, 1_000_000, 256 * 1024, 100);

    public Limits {
        if (maxTxnOps <= PUBLISH_OVERHEAD + 1 || maxTxnBytes < 4096 || maxValueBytes < 1024 || historyLimit < 1) {
            throw new IllegalArgumentException("invalid control-plane write limits");
        }
    }

    /** Staged ops allowed in one change set. */
    public int maxOps() {
        return maxTxnOps - PUBLISH_OVERHEAD;
    }
}
