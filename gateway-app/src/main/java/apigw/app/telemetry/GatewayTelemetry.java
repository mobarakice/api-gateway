package apigw.app.telemetry;

import apigw.core.auth.Principal;
import apigw.core.observe.RequestTelemetry;
import apigw.core.observe.RouteTelemetry;
import apigw.core.observe.RouteTelemetryFactory;
import apigw.core.observe.Trace;
import apigw.core.plugin.Exchange;
import apigw.core.plugin.PluginServices;
import apigw.core.snapshot.CompiledRoute;
import apigw.observability.logging.LogLine;
import apigw.observability.metrics.Counter;
import apigw.observability.metrics.MetricRegistry;
import apigw.observability.tracing.Span;
import apigw.observability.tracing.TraceParent;
import apigw.observability.tracing.Tracer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gateway's request telemetry: route and upstream meters, the access log and request traces.
 * Route meters are created when a snapshot compiles and dropped when a published snapshot no longer
 * has the route, so metric cardinality follows the live configuration.
 */
public final class GatewayTelemetry implements RequestTelemetry, RouteTelemetryFactory,
        PluginServices.SnapshotAware {

    static final String ACCESS_LOGGER = "access";

    private final MetricRegistry registry;
    private final TelemetryConfig.AccessLogMode accessLog;
    private final Tracer tracer;
    private final Map<String, RouteMeters> routes = new ConcurrentHashMap<>();
    private final Map<String, UpstreamMeters> upstreams = new ConcurrentHashMap<>();
    private final Counter[] unrouted = new Counter[RouteMeters.CODES.length];

    /**
     * @param tracer null = tracing off: inbound trace context passes through untouched
     */
    public GatewayTelemetry(MetricRegistry registry, TelemetryConfig.AccessLogMode accessLog, Tracer tracer) {
        this.registry = registry;
        this.accessLog = accessLog;
        this.tracer = tracer;
    }

    // ---- route meters ---------------------------------------------------------------------------

    @Override
    public RouteTelemetry forRoute(String routeId, String upstreamId) {
        return routes.computeIfAbsent(routeId + '\u0000' + upstreamId, k -> new RouteMeters(registry, routeId,
                upstreams.computeIfAbsent(upstreamId, u -> new UpstreamMeters(registry, u))));
    }

    @Override
    public void retainOnly(Set<Object> inUse) {
        Set<String> liveRoutes = new HashSet<>();
        Set<String> liveUpstreams = new HashSet<>();
        for (Object o : inUse) {
            if (o instanceof RouteMeters m) {
                liveRoutes.add(m.routeId);
                liveUpstreams.add(m.upstream.id);
            }
        }
        routes.values().removeIf(m -> !inUse.contains(m));
        upstreams.keySet().retainAll(liveUpstreams);
        for (String family : new String[] {RouteMeters.REQUESTS, RouteMeters.LOCAL, RouteMeters.DURATION}) {
            registry.removeIf(family, "route", id -> !liveRoutes.contains(id));
        }
        for (String family : new String[] {UpstreamMeters.ATTEMPTS, UpstreamMeters.LATENCY, UpstreamMeters.RETRIES}) {
            registry.removeIf(family, "upstream", id -> !liveUpstreams.contains(id));
        }
    }

    // ---- request hooks --------------------------------------------------------------------------

    @Override
    public Trace begin(HttpServerRequest request, long startNanos) {
        if (tracer == null) {
            return null;
        }
        TraceParent inbound = TraceParent.parse(request.getHeader("traceparent"));
        String method = request.method().name();
        if (!tracer.sample(inbound)) {
            // Not recorded here: keep the trace ID for the access log; the header passes through as-is.
            return inbound == null ? null : new RequestTrace(tracer, inbound.traceId(), method, startNanos, null);
        }
        Span server = tracer.server(inbound, method, startNanos)
                .attr("http.request.method", method)
                .attr("url.scheme", request.isSSL() ? "https" : "http")
                .attr("network.protocol.version", protocol(request.version()));
        return new RequestTrace(tracer, server.traceId(), method, startNanos, server);
    }

    @Override
    public void rejected(HttpServerRequest request, int status, String requestId, Trace trace, long startNanos) {
        int slot = RouteMeters.slot(status);
        Counter c = unrouted[slot];
        if (c == null) {
            c = registry.counter("gw_unrouted_requests_total",
                    "Requests answered before a route matched (no config, malformed, no route).",
                    "code", RouteMeters.CODES[slot]);
            unrouted[slot] = c;
        }
        c.inc();
        long end = System.nanoTime();
        if (trace instanceof RequestTrace t) {
            t.end(status, true, requestId, null, end);
        }
        if (accessLog != TelemetryConfig.AccessLogMode.OFF && LogLine.enabled(ACCESS_LOGGER)) {
            LogLine.info(ACCESS_LOGGER, "request")
                    .field("requestId", requestId)
                    .field("traceId", trace == null ? null : trace.traceId())
                    .field("method", request.method().name())
                    .field("protocol", protocol(request.version()))
                    .field("status", status)
                    .field("source", "gateway")
                    .millis("durationMs", end - startNanos)
                    .emit();
        }
    }

    @Override
    public void completed(Exchange exchange, int status, boolean local, long durationNanos, Trace trace) {
        CompiledRoute route = exchange.route();
        route.telemetry().completed(status, local, durationNanos);
        String upstreamId = route.upstream() == null ? null : route.upstream().id();
        if (trace instanceof RequestTrace t) {
            t.end(status, local, exchange.requestId(), upstreamId, t.startNanos() + durationNanos);
        }
        if (accessLog == TelemetryConfig.AccessLogMode.OFF
                || accessLog == TelemetryConfig.AccessLogMode.ERRORS && status < 500 && !(local && status >= 400)
                || !LogLine.enabled(ACCESS_LOGGER)) {
            return;
        }
        HttpServerRequest request = exchange.request();
        Principal p = exchange.principal();
        LogLine.info(ACCESS_LOGGER, "request")
                .field("requestId", exchange.requestId())
                .field("traceId", trace == null ? null : trace.traceId())
                .field("routeId", route.id())
                .field("serviceId", route.serviceId())
                .field("upstreamId", upstreamId)
                .field("consumerId", p == null || p.consumer() == null ? null : p.consumer().id())
                .field("tenantId", p == null ? null : p.tenantId())
                .field("method", request.method().name())
                .field("protocol", protocol(request.version()))
                .field("status", status)
                .field("source", local ? "gateway" : "upstream")
                .field("bytesSent", request.response().bytesWritten())
                .millis("durationMs", durationNanos)
                .emit();
    }

    private static String protocol(HttpVersion v) {
        return switch (v) {
            case HTTP_2 -> "2";
            case HTTP_1_0 -> "1.0";
            default -> "1.1";
        };
    }
}
