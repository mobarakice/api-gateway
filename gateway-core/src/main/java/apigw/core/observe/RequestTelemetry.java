package apigw.core.observe;

import apigw.core.plugin.Exchange;
import io.vertx.core.http.HttpServerRequest;

/**
 * Process-wide request hooks, chosen at startup. Implementations never block and never throw.
 */
public interface RequestTelemetry {

    /** No metrics, no access log, no tracing. */
    RequestTelemetry NONE = new RequestTelemetry() {
        @Override
        public Trace begin(HttpServerRequest request, long startNanos) {
            return null;
        }

        @Override
        public void rejected(HttpServerRequest request, int status, String requestId, Trace trace, long startNanos) {
            // Intentionally empty.
        }

        @Override
        public void completed(Exchange exchange, int status, boolean local, long durationNanos, Trace trace) {
            // Intentionally empty.
        }
    };

    /**
     * Called first for every request. Returns the request's trace, or null when it is not traced
     * (an inbound {@code traceparent} is then forwarded unchanged).
     */
    Trace begin(HttpServerRequest request, long startNanos);

    /** The gateway answered before a route matched (no snapshot, malformed request, no route). */
    void rejected(HttpServerRequest request, int status, String requestId, Trace trace, long startNanos);

    /** A routed exchange finished (once). Records route meters, the access log line and the server span. */
    void completed(Exchange exchange, int status, boolean local, long durationNanos, Trace trace);
}
