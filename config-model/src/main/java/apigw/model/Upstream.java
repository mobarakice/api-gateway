package apigw.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A set of targets and how to reach them.
 *
 * @param scheme   http or https to the targets
 * @param caFile   PEM CA bundle for https targets; empty = the platform trust store
 * @param balancer target selection algorithm
 * @param hashKey  for {@link Balancer#CONSISTENT_HASH}: {@code ip} or {@code header:<name>}
 * @param http2    use HTTP/2 to the targets (h2 via ALPN for https, prior knowledge for http)
 * @param healthCheck    active probes and passive outlier ejection; {@link HealthCheck#NONE} = off
 * @param circuitBreaker null = off
 * @param clientCert     client certificate for mTLS to https targets; null = none
 */
public record Upstream(
        String id,
        Scheme scheme,
        Optional<Path> caFile,
        List<Target> targets,
        Balancer balancer,
        String hashKey,
        Pool pool,
        boolean http2,
        HealthCheck healthCheck,
        CircuitBreaker circuitBreaker,
        ClientCert clientCert,
        String tenantId) {

    /** Transport to the targets. */
    public enum Scheme { HTTP, HTTPS }

    /** Target selection. */
    public enum Balancer { ROUND_ROBIN, WEIGHTED_ROUND_ROBIN, LEAST_REQUESTS, CONSISTENT_HASH }

    /** One backend address. */
    public record Target(String host, int port, int weight) {
    }

    /**
     * Connection pool limits, per target and per event loop.
     *
     * @param maxConnections HTTP/1.1 connections (HTTP/2: connections, each multiplexed)
     * @param maxPending     requests allowed to wait for a connection before failing fast with 503
     * @param keepAliveSeconds idle pooled connections are closed after this
     */
    public record Pool(int maxConnections, int maxPending, int keepAliveSeconds) {
        public static final Pool DEFAULTS = new Pool(64, 256, 60);
    }

    /** Health of targets: active probes, passive ejection, either or both (null = that part off). */
    public record HealthCheck(Active active, Passive passive) {
        public static final HealthCheck NONE = new HealthCheck(null, null);
    }

    /**
     * Periodic probe of every target, once per process (not per event loop).
     *
     * @param path               request path (GET)
     * @param host               Host header; empty = the target's host:port
     * @param statuses           healthy statuses; empty = any 2xx
     * @param healthyThreshold   consecutive successes to mark an unhealthy target healthy
     * @param unhealthyThreshold consecutive failures to mark a healthy target unhealthy
     */
    public record Active(String path, String host, int intervalMs, int timeoutMs, int healthyThreshold,
            int unhealthyThreshold, List<Integer> statuses) {
        public Active {
            statuses = List.copyOf(statuses);
        }
    }

    /**
     * Outlier ejection from real traffic.
     *
     * @param consecutiveFailures failures in a row (transport errors, timeouts, 502/503/504) that eject a target
     * @param ejectionMs          base ejection time; repeated ejections multiply it (capped at 10x)
     * @param maxEjectionPercent  never eject more than this share of the targets
     */
    public record Passive(int consecutiveFailures, int ejectionMs, int maxEjectionPercent) {
    }

    /**
     * Per-upstream circuit breaker over a sliding time window (ADR 0017).
     *
     * @param failureStatuses response statuses counted as failures (transport errors and timeouts always are)
     * @param slowCallMs      time to response headers above which a call is slow; 0 = slow calls not counted
     * @param halfOpenCalls   trial calls allowed while half-open; all must succeed to close
     */
    public record CircuitBreaker(int windowSeconds, int minCalls, int failureRatePercent, int slowCallMs,
            int slowCallRatePercent, int openMs, int halfOpenCalls, List<Integer> failureStatuses) {
        public CircuitBreaker {
            failureStatuses = List.copyOf(failureStatuses);
        }
    }

    /** Client certificate and key (PEM files, e.g. mounted secrets) presented to https targets. */
    public record ClientCert(Path certFile, Path keyFile) {
    }

    public Upstream {
        targets = List.copyOf(targets);
        healthCheck = healthCheck == null ? HealthCheck.NONE : healthCheck;
    }
}
