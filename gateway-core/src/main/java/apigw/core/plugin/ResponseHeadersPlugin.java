package apigw.core.plugin;

import io.vertx.core.MultiMap;

/** Runs in {@link Phase#HEADER_FILTER}: may edit the headers sent to the client. Must not block. */
public interface ResponseHeadersPlugin extends Plugin {

    void onResponseHeaders(Exchange exchange, int status, MultiMap headers);
}
