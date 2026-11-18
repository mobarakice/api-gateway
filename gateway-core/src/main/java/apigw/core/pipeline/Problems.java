package apigw.core.pipeline;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;

/**
 * Generic public error bodies (RFC 9457 shape) carrying only a status, a fixed title and the
 * request ID. Never includes upstream hosts, config details or exception text.
 */
public final class Problems {

    public static final CharSequence CONTENT_TYPE = io.vertx.core.http.HttpHeaders.createOptimized("content-type");
    public static final CharSequence PROBLEM_JSON = io.vertx.core.http.HttpHeaders.createOptimized(
            "application/problem+json");
    public static final CharSequence REQUEST_ID = io.vertx.core.http.HttpHeaders.createOptimized("x-request-id");

    private Problems() {
    }

    public static void send(HttpServerResponse response, int status, String title, String requestId) {
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

    /** Fixed, generic title for a status. */
    public static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Content Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Error";
        };
    }
}
