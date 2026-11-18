package apigw.app;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;

/**
 * Public request entry point. Until routing lands (M2) no route can match, so every request
 * gets a generic 404 problem with a request ID. Runs on an event loop and never blocks.
 */
final class TrafficHandler implements Handler<HttpServerRequest> {

    @Override
    public void handle(HttpServerRequest request) {
        Problems.send(request.response(), 404, "Not Found", RequestIds.next());
    }
}
