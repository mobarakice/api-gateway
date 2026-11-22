package apigw.controlplane.metrics;

import apigw.config.etcd.EtcdHttpClient;
import apigw.observability.metrics.Buckets;
import apigw.observability.metrics.Counter;
import apigw.observability.metrics.Histogram;
import apigw.observability.metrics.MetricRegistry;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps the Admin API handler: counts requests by endpoint, method and status class, and times them.
 * The endpoint label comes from a fixed vocabulary (collection plus action), never from the raw path,
 * so unknown or hostile paths all count as {@code other}.
 */
public final class ApiMetrics implements Handler<HttpServerRequest> {

    private static final Set<String> COLLECTIONS = Set.of("me", "plugins", "routes", "services", "upstreams",
            "consumers", "plugin-configs", "global-plugins", "policies", "certificates", "settings", "changesets",
            "history", "gateways", "audit");
    private static final Set<String> ACTIONS = Set.of("publish", "rollback", "validate", "diff", "ops", "keys",
            "schema", "credentials");
    private static final String[] CLASSES = {"1xx", "2xx", "3xx", "4xx", "5xx"};

    private final MetricRegistry registry;
    private final Handler<HttpServerRequest> next;
    private final Map<String, Counter> requests = new ConcurrentHashMap<>();
    private final Map<String, Histogram> durations = new ConcurrentHashMap<>();

    public ApiMetrics(MetricRegistry registry, Handler<HttpServerRequest> next) {
        this.registry = registry;
        this.next = next;
    }

    @Override
    public void handle(HttpServerRequest req) {
        long start = System.nanoTime();
        String endpoint = endpoint(req.path());
        String method = switch (req.method().name()) {
            case "GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH" -> req.method().name();
            default -> "other";
        };
        req.response().endHandler(v -> {
            int c = req.response().getStatusCode() / 100;
            String code = CLASSES[c >= 1 && c <= 5 ? c - 1 : 4];
            requests.computeIfAbsent(endpoint + ' ' + method + ' ' + code, k -> registry.counter(
                    "cp_http_requests_total", "Admin API requests by endpoint, method and status class.",
                    "endpoint", endpoint, "method", method, "code", code)).inc();
            durations.computeIfAbsent(endpoint, k -> registry.histogram("cp_http_request_duration_seconds",
                    "Admin API request duration by endpoint.", Buckets.LATENCY, "endpoint", endpoint))
                    .observeNanos(System.nanoTime() - start);
        });
        next.handle(req);
    }

    /** {@code routes}, {@code changesets.publish}, {@code history.rollback}, or {@code other}. */
    static String endpoint(String path) {
        String prefix = "/admin/v1/";
        if (path == null || !path.startsWith(prefix)) {
            return path != null && path.startsWith("/ready") ? "ready" : "other";
        }
        String[] parts = path.substring(prefix.length()).split("/", -1);
        String collection = parts[0];
        if (!COLLECTIONS.contains(collection)) {
            return "other";
        }
        String last = parts[parts.length - 1];
        return parts.length > 1 && ACTIONS.contains(last) ? collection + "." + last : collection;
    }

    /** Observes etcd calls: latency and errors by operation. */
    public static EtcdHttpClient.CallObserver etcdObserver(MetricRegistry registry) {
        Map<String, Histogram> latency = new ConcurrentHashMap<>();
        Map<String, Counter> errors = new ConcurrentHashMap<>();
        return (op, ok, nanos) -> {
            latency.computeIfAbsent(op, o -> registry.histogram("cp_etcd_request_seconds",
                    "etcd request latency by operation.", Buckets.CONTROL, "op", o)).observeNanos(nanos);
            if (!ok) {
                errors.computeIfAbsent(op, o -> registry.counter("cp_etcd_request_errors_total",
                        "Failed etcd requests by operation.", "op", o)).inc();
            }
        };
    }
}
