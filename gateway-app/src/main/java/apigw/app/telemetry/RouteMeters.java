package apigw.app.telemetry;

import apigw.core.observe.AttemptOutcome;
import apigw.core.observe.RouteTelemetry;
import apigw.observability.metrics.Buckets;
import apigw.observability.metrics.Counter;
import apigw.observability.metrics.Histogram;
import apigw.observability.metrics.MetricRegistry;

/**
 * Meters of one route, compiled into it. The {@code code} label is a status class ({@code 2xx}) or one
 * of a fixed set of exact codes that mean something operationally (401/403 auth, 429 rate limit,
 * 499 client gone, 502/503/504 upstream trouble), so cardinality is bounded: at most 15 per route.
 */
final class RouteMeters implements RouteTelemetry {

    static final String REQUESTS = "gw_requests_total";
    static final String LOCAL = "gw_local_responses_total";
    static final String DURATION = "gw_request_duration_seconds";
    static final String[] CODES = {"1xx", "2xx", "3xx", "4xx", "5xx", "401", "403", "404", "408", "413", "429",
        "499", "502", "503", "504"};

    final String routeId;
    final UpstreamMeters upstream;
    private final MetricRegistry registry;
    private final Counter[] requests = new Counter[CODES.length];
    private final Counter[] local = new Counter[CODES.length];
    private final Histogram duration;

    RouteMeters(MetricRegistry registry, String routeId, UpstreamMeters upstream) {
        this.registry = registry;
        this.routeId = routeId;
        this.upstream = upstream;
        this.duration = registry.histogram(DURATION, "Request duration from arrival to the last response byte.",
                Buckets.LATENCY, "route", routeId);
    }

    static int slot(int status) {
        return switch (status) {
            case 401 -> 5;
            case 403 -> 6;
            case 404 -> 7;
            case 408 -> 8;
            case 413 -> 9;
            case 429 -> 10;
            case 499 -> 11;
            case 502 -> 12;
            case 503 -> 13;
            case 504 -> 14;
            default -> {
                int c = status / 100;
                yield c >= 1 && c <= 5 ? c - 1 : 4;
            }
        };
    }

    @Override
    public void completed(int status, boolean gateway, long durationNanos) {
        int slot = slot(status);
        Counter c = requests[slot];
        if (c == null) {
            c = registry.counter(REQUESTS, "Requests by route and status (class, or an exact code of interest).",
                    "route", routeId, "code", CODES[slot]);
            requests[slot] = c;
        }
        c.inc();
        if (gateway) {
            Counter l = local[slot];
            if (l == null) {
                l = registry.counter(LOCAL, "Responses the gateway produced itself (auth, rate limit, cache hit, "
                        + "proxy error) by route and status.", "route", routeId, "code", CODES[slot]);
                local[slot] = l;
            }
            l.inc();
        }
        duration.observeNanos(durationNanos);
    }

    @Override
    public void attempt(AttemptOutcome outcome, int status, long durationNanos, int attempt) {
        upstream.attempt(outcome, status, durationNanos, attempt);
    }
}
