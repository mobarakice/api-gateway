package apigw.app.telemetry;

import apigw.core.observe.AttemptOutcome;
import apigw.observability.metrics.Buckets;
import apigw.observability.metrics.Counter;
import apigw.observability.metrics.Histogram;
import apigw.observability.metrics.MetricRegistry;

/** Attempt counters and latency of one upstream; shared by every route that uses it. */
final class UpstreamMeters {

    static final String ATTEMPTS = "gw_upstream_attempts_total";
    static final String LATENCY = "gw_upstream_response_seconds";
    static final String RETRIES = "gw_upstream_retries_total";
    private static final String[] STATUS = {"1xx", "2xx", "3xx", "4xx", "5xx"};

    final String id;
    private final MetricRegistry registry;
    /** Response classes 1xx..5xx, then one slot per failure outcome; created on first use. */
    private final Counter[] attempts = new Counter[STATUS.length + AttemptOutcome.values().length];
    private final Histogram latency;
    private final Counter retries;

    UpstreamMeters(MetricRegistry registry, String id) {
        this.registry = registry;
        this.id = id;
        this.latency = registry.histogram(LATENCY, "Upstream attempt time to response head (or failure).",
                Buckets.LATENCY, "upstream", id);
        this.retries = registry.counter(RETRIES, "Upstream retry attempts.", "upstream", id);
    }

    void attempt(AttemptOutcome outcome, int status, long nanos, int attempt) {
        int slot;
        String label;
        if (outcome == AttemptOutcome.RESPONSE) {
            int c = status / 100;
            slot = c >= 1 && c <= 5 ? c - 1 : 4;
            label = STATUS[slot];
        } else {
            slot = STATUS.length + outcome.ordinal();
            label = outcome.label();
        }
        Counter counter = attempts[slot];
        if (counter == null) {
            // Benign race: the registry returns the same counter for the same labels.
            counter = registry.counter(ATTEMPTS, "Upstream attempts by result: status class or failure kind.",
                    "upstream", id, "outcome", label);
            attempts[slot] = counter;
        }
        counter.inc();
        if (nanos > 0) {
            latency.observeNanos(nanos);
        }
        if (attempt > 0) {
            retries.inc();
        }
    }
}
