package apigw.controlplane.audit;

/**
 * External, append-only destination for audit events (one JSON object per line). The log pipeline
 * gets every event too; a sink is for deployments that ship audit separately.
 */
public interface AuditSink {

    /** Appends one event line (without the newline). Never blocks the caller. */
    void append(String jsonLine);
}
