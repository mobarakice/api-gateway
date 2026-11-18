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
 */
public record Upstream(
        String id,
        Scheme scheme,
        Optional<Path> caFile,
        List<Target> targets,
        Balancer balancer,
        String hashKey,
        Pool pool,
        boolean http2) {

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

    public Upstream {
        targets = List.copyOf(targets);
    }
}
