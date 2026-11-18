package apigw.core.pipeline;

import apigw.core.routing.RouteMatch;
import apigw.core.routing.Router;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotHolder;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.HostAndPort;

/**
 * Public request entry point, one instance per event loop (it owns a reusable {@link RouteMatch}).
 * Reads the snapshot once per request; never blocks; never touches etcd.
 */
public final class Pipeline implements Handler<HttpServerRequest> {

    private final SnapshotHolder snapshots;
    private final Forwarder forwarder;
    private final RouteMatch match = new RouteMatch();

    public Pipeline(SnapshotHolder snapshots, Forwarder forwarder) {
        this.snapshots = snapshots;
        this.forwarder = forwarder;
    }

    @Override
    public void handle(HttpServerRequest request) {
        long start = System.nanoTime();
        String requestId = RequestIds.next();
        Snapshot snapshot = snapshots.current();
        if (snapshot == null) {
            sendProblem(request, 503, "Service Unavailable", requestId);
            return;
        }
        int invalid = RequestValidator.check(request, snapshot.settings().maxRequestBodyBytes());
        if (invalid != 0) {
            sendProblem(request, invalid, Problems.title(invalid), requestId);
            return;
        }
        String host = host(request);
        if (host == null) {
            sendProblem(request, 400, "Bad Request", requestId);
            return;
        }
        if (!snapshot.router().match(host, request.method().name(), request.path(), request.query(),
                request.headers(), match)) {
            sendProblem(request, 404, "Not Found", requestId);
            return;
        }
        // The body is read by the forwarder (or a buffering plugin) once the chain admits the request.
        request.pause();
        int[] params = match.paramCount() == 0 ? NO_PARAMS : match.paramOffsets();
        new ExchangeImpl(request, snapshot, match.route(), params, forwarder, requestId, host, start).run();
    }

    private static final int[] NO_PARAMS = new int[0];

    /** Lower-case host without port; "" when the request has none (HTTP/1.0); null when malformed. */
    static String host(HttpServerRequest request) {
        HostAndPort authority = request.authority();
        if (authority == null) {
            return request.version() == HttpVersion.HTTP_1_0 ? "" : null;
        }
        String h = authority.host();
        if (h == null || h.isEmpty()) {
            return null;
        }
        if (h.indexOf(':') >= 0 && h.charAt(0) != '[') {
            h = "[" + h + "]";
        }
        return Router.normalizeHost(h);
    }

    /**
     * Sends a problem response. If the request body has not been read, an HTTP/1.x connection is
     * closed after the response instead of draining an unknown amount of data.
     */
    static void sendProblem(HttpServerRequest request, int status, String title, String requestId) {
        HttpServerResponse response = request.response();
        if (!request.isEnded() && request.version() != HttpVersion.HTTP_2) {
            response.putHeader("connection", "close");
            response.endHandler(v -> request.connection().close());
        }
        Problems.send(response, status, title, requestId);
    }
}
