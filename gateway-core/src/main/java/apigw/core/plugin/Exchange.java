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
}
