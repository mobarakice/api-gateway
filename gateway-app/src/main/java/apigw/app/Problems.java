package apigw.app;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;

/**
 * Generic public error bodies (RFC 9457 shape) carrying only a status, a fixed title and the
 * request ID. Never includes upstream hosts, config details or exception text.
 */
final class Problems {

    static final CharSequence CONTENT_TYPE = io.vertx.core.http.HttpHeaders.createOptimized("content-type");
    static final CharSequence PROBLEM_JSON = io.vertx.core.http.HttpHeaders.createOptimized(
            "application/problem+json");
    static final CharSequence REQUEST_ID = io.vertx.core.http.HttpHeaders.createOptimized("x-request-id");

    private Problems() {
    }

    static void send(HttpServerResponse response, int status, String title, String requestId) {
        // requestId is 16 hex chars and title is a compile-time constant, so no JSON escaping is needed.
        Buffer body = Buffer.buffer(96)
                .appendString("{\"type\":\"about:blank\",\"title\":\"")
                .appendString(title)
                .appendString("\",\"status\":")
                .appendString(Integer.toString(status))
                .appendString(",\"requestId\":\"")
                .appendString(requestId)
                .appendString("\"}");
        response.setStatusCode(status)
                .putHeader(CONTENT_TYPE, PROBLEM_JSON)
                .putHeader(REQUEST_ID, requestId)
                .end(body);
    }
}
