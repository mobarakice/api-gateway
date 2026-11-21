package apigw.core.plugin;

import apigw.core.snapshot.CompiledRoute;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;

/**
 * One client request as seen by plugins. Owned by a single event loop; never shared across threads.
 */
public interface Exchange {

    HttpServerRequest request();

    CompiledRoute route();

    /** Value of a template path parameter, or {@code null}. */
    String pathParam(String name);

    /** Headers that will be sent upstream (inbound headers minus hop-by-hop and stripped identity headers). */
    MultiMap upstreamHeaders();

    /** Path and query to send upstream; starts as the inbound request URI. */
    String upstreamUri();

    void upstreamUri(String uri);

    /** Client address after applying trusted proxies, as a literal. */
    String clientIp();

    String requestId();

    /** Sends a generic problem response (no internal detail) and ends the chain. Returns {@link Outcome#STOP}. */
    Outcome respond(int status, String title);

    /** Continues the chain after a plugin returned {@link Outcome#PENDING}. Call on the exchange's event loop. */
    void resume();

    /** The snapshot captured when the request arrived (security index, settings). */
    apigw.core.snapshot.Snapshot snapshot();

    /** The authenticated principal, or null before (or without) authentication. */
    apigw.core.auth.Principal principal();

    /**
     * Records the authenticated principal. Enforces tenant isolation: on a tenant route a principal of
     * any other tenant (or none) gets 403. On success sets the gateway's identity headers upstream
     * ({@code x-consumer-id}, {@code x-consumer-username}, {@code x-tenant-id}, {@code x-authenticated-user},
     * {@code x-credential-identifier}) and switches the rest of the chain to the consumer's overrides.
     *
     * @return {@link Outcome#CONTINUE}, or {@link Outcome#STOP} after a 403
     */
    Outcome authenticate(apigw.core.auth.Principal principal);

    /** {@link #respond(int, String)} with one extra response header, e.g. {@code WWW-Authenticate}. */
    Outcome respond(int status, String title, String headerName, String headerValue);

    /** {@link #respond(int, String)} with extra response headers, e.g. {@code Retry-After} and {@code RateLimit}. */
    Outcome respond(int status, String title, MultiMap headers);

    /**
     * Sends a complete response made by a plugin (cache hit, CORS preflight). Header filters run on
     * {@code headers} first, as for an upstream response. Returns {@link Outcome#STOP}.
     *
     * @param body null or empty for no body
     */
    Outcome respondWith(int status, MultiMap headers, io.vertx.core.buffer.Buffer body);

    /**
     * Adds a header to whatever response this exchange sends (upstream, plugin-made or error), e.g.
     * rate-limit state. Applied before header filters run.
     */
    void addResponseHeader(CharSequence name, CharSequence value);

    /**
     * Lowers the request body cap for this exchange (never raises it above the global
     * {@code settings.maxRequestBodyBytes}). Bodies over the cap get 413, also when streamed chunked.
     */
    void limitRequestBody(long maxBytes);

    /** Observes the upstream response, if one is proxied. One tap per exchange; a second call replaces it. */
    void tapResponse(ResponseTap tap);
}
