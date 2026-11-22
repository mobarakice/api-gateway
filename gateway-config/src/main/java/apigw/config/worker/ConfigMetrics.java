package apigw.config.worker;

import apigw.config.etcd.EtcdHttpClient;
import apigw.observability.metrics.Buckets;
import apigw.observability.metrics.Counter;
import apigw.observability.metrics.Histogram;
import apigw.observability.metrics.MetricRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Config-worker meters: etcd call latency and errors, resyncs, watch restarts, rejected snapshots,
 * and how long a published change took to be served here ({@code history/head.publishedAt} to the
 * snapshot swap; includes clock skew between control-plane and gateway hosts).
 */
public final class ConfigMetrics implements EtcdHttpClient.CallObserver {

    private final MetricRegistry registry;
    private final Map<String, Histogram> latency = new ConcurrentHashMap<>();
    private final Map<String, Counter> errors = new ConcurrentHashMap<>();
    private final Counter resyncs;
    private final Counter watchRestarts;
    private final Counter rejected;
    private final Counter applied;
    private final Histogram propagation;
    private volatile long lastEtcdSuccessMillis;
    private volatile long publishedRevision;

    public ConfigMetrics(MetricRegistry registry) {
        this.registry = registry;
        this.resyncs = registry.counter("gw_config_resyncs_total",
                "Full keyspace loads after boot (compaction, etcd restore).");
        this.watchRestarts = registry.counter("gw_config_watch_restarts_total",
                "etcd watch streams reopened after an error or member change.");
        this.rejected = registry.counter("gw_config_rejected_total",
                "Config revisions rejected by validation or compilation (the previous snapshot kept serving).");
        this.applied = registry.counter("gw_config_snapshots_applied_total", "Snapshots published.");
        this.propagation = registry.histogram("gw_config_propagation_seconds",
                "Control-plane publish to snapshot served by this gateway.", Buckets.CONTROL);
        registry.gauge("gw_config_published_revision",
                "etcd revision of the newest control-plane publish this gateway has seen (0 = none since start).",
                () -> publishedRevision);
        registry.gauge("gw_config_etcd_last_success_timestamp_seconds",
                "Last successful etcd call or watch event, Unix seconds (0 = never).",
                () -> lastEtcdSuccessMillis / 1000.0);
    }

    /** Registers status gauges read from the worker (applied and etcd revision, state). */
    public void bind(Supplier<WorkerStatus> status) {
        registry.gauge("gw_config_applied_revision", "etcd revision of the snapshot being served.",
                () -> status.get().appliedRevision());
        registry.gauge("gw_config_etcd_revision", "Newest etcd revision this gateway has seen.",
                () -> status.get().etcdRevision());
        for (WorkerStatus.State s : WorkerStatus.State.values()) {
            registry.gauge("gw_config_state", "Config worker state (1 = current).",
                    () -> status.get().state() == s ? 1 : 0, "state", s.name());
        }
    }

    @Override
    public void call(String op, boolean ok, long nanos) {
        latency.computeIfAbsent(op, o -> registry.histogram("gw_etcd_request_seconds",
                "etcd request latency by operation.", Buckets.CONTROL, "op", o)).observeNanos(nanos);
        if (ok) {
            etcdOk();
        } else {
            errors.computeIfAbsent(op, o -> registry.counter("gw_etcd_request_errors_total",
                    "Failed etcd requests by operation.", "op", o)).inc();
        }
    }

    void etcdOk() {
        lastEtcdSuccessMillis = System.currentTimeMillis();
    }

    void resynced() {
        resyncs.inc();
    }

    void watchRestarted() {
        watchRestarts.inc();
    }

    void rejected() {
        rejected.inc();
    }

    void applied() {
        applied.inc();
    }

    void published(long revision) {
        if (revision > publishedRevision) {
            publishedRevision = revision;
        }
    }

    void propagated(long millis) {
        propagation.observeNanos(Math.max(0, millis) * 1_000_000L);
    }
}
