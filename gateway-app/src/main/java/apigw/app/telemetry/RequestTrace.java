package apigw.app.telemetry;

import apigw.core.observe.AttemptOutcome;
import apigw.core.observe.Trace;
import apigw.core.snapshot.CompiledRoute;
import apigw.observability.tracing.Span;
import apigw.observability.tracing.TraceParent;
import apigw.observability.tracing.Tracer;
import io.vertx.core.MultiMap;

/**
 * Trace of one request: a server span and one client span per upstream attempt (semantic conventions
 * for HTTP). Not recording when the request is only being passed through (inbound context, not
 * sampled): nothing is created and the inbound {@code traceparent} reaches the upstream unchanged.
 * Used only from the request's event loop.
 */
final class RequestTrace implements Trace {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final String traceId;
    private final String method;
    private final long startNanos;
    /** Null when not recording. */
    private final Span server;
    private Span client;

    RequestTrace(Tracer tracer, String traceId, String method, long startNanos, Span server) {
        this.tracer = tracer;
        this.traceId = traceId;
        this.method = method;
        this.startNanos = startNanos;
        this.server = server;
    }

    @Override
    public String traceId() {
        return traceId;
    }

    @Override
    public boolean recording() {
        return server != null;
    }

    Span server() {
        return server;
    }

    long startNanos() {
        return startNanos;
    }

    @Override
    public void route(CompiledRoute route) {
        if (server != null) {
            server.name(method + " " + route.path()).attr("http.route", route.path())
                    .attr("apigw.route.id", route.id());
        }
    }

    @Override
    public void attemptStart(MultiMap upstreamHeaders, String host, int port, int attempt) {
        if (server == null) {
            return;
        }
        attemptEnd();
        client = tracer.client(server, method, System.nanoTime())
                .attr("http.request.method", method)
                .attr("server.address", host)
                .attr("server.port", port);
        if (attempt > 0) {
            client.attr("http.request.resend_count", attempt);
        }
        upstreamHeaders.set(TRACEPARENT, TraceParent.format(traceId, client.spanId(), true));
    }

    @Override
    public void attemptResult(int status, AttemptOutcome failure) {
        Span c = client;
        if (c == null) {
            return;
        }
        if (status > 0) {
            c.attr("http.response.status_code", status);
            if (status >= 400) {
                c.attr("error.type", Integer.toString(status)).error();
            }
        }
        if (failure != null) {
            c.attr("error.type", failure.label()).error();
        }
    }

    @Override
    public void attemptEnd() {
        Span c = client;
        if (c != null) {
            client = null;
            tracer.end(c, System.nanoTime());
        }
    }

    /** Ends the server span (once); closes a client span left open by an aborted exchange. */
    void end(int status, boolean local, String requestId, String upstreamId, long endNanos) {
        if (server == null) {
            return;
        }
        Span c = client;
        if (c != null) {
            // Still open (the response stream ends just after this): close it at the same instant.
            client = null;
            tracer.end(c, endNanos);
        }
        server.attr("http.response.status_code", status).attr("apigw.request.id", requestId)
                .attr("apigw.upstream.id", upstreamId);
        if (local) {
            server.attr("apigw.response.source", "gateway");
        }
        if (status >= 500) {
            server.attr("error.type", Integer.toString(status)).error();
        }
        tracer.end(server, endNanos);
    }
}
