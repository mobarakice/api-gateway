package apigw.core.observe;

import apigw.core.snapshot.CompiledRoute;
import io.vertx.core.MultiMap;

/**
 * The trace context of one request (W3C Trace Context). Exists only when tracing is on and the
 * request carries or was given a trace; null otherwise. Used only from the request's event loop.
 */
public interface Trace {

    /** 32 lower-case hex digits. */
    String traceId();

    /** True when spans of this request are recorded and exported. */
    boolean recording();

    /** The route matched; names the server span. */
    void route(CompiledRoute route);

    /**
     * An upstream attempt begins: opens a client span and, when recording, sets {@code traceparent}
     * on the headers sent upstream so the upstream's spans join this trace.
     */
    void attemptStart(MultiMap upstreamHeaders, String host, int port, int attempt);

    /** Result of the open attempt: an upstream status, or 0 with an error type. Idempotent per attempt. */
    void attemptResult(int status, AttemptOutcome failure);

    /** Closes the open attempt's span, if any. */
    void attemptEnd();
}
