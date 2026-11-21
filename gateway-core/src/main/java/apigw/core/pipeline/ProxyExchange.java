package apigw.core.pipeline;

import apigw.core.plugin.Exchange;
import apigw.core.snapshot.Snapshot;
import io.vertx.core.MultiMap;

/** The exchange as the forwarder sees it. */
public interface ProxyExchange extends Exchange {

    /** The snapshot captured when the request arrived. */
    Snapshot snapshot();

    /**
     * Runs header-filter plugins on the response headers about to be sent to the client.
     *
     * @return false if the exchange already ended (timed out, client gone): do not write the head
     */
    boolean beginResponse(int status, MultiMap headers);

    /** Registers the single action to run if the client goes away before the response completes. */
    void onAbort(Runnable action);

    /** True once a response was fully sent, failed, or the client went away. */
    boolean finished();

    /** The request body cap for this exchange (global setting, or lower if a plugin limited it). */
    long requestBodyLimit();

    /** The tap a plugin registered, or null. */
    apigw.core.plugin.ResponseTap responseTap();
}
