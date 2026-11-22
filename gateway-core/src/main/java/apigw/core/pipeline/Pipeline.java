package apigw.core.pipeline;

import apigw.core.observe.RequestTelemetry;
import apigw.core.observe.Trace;
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
    private final RequestTelemetry telemetry;
    private final RouteMatch match = new RouteMatch();

    public Pipeline(SnapshotHolder snapshots, Forwarder forwarder) {
        this(snapshots, forwarder, RequestTelemetry.NONE);
    }

    public Pipeline(SnapshotHolder snapshots, Forwarder forwarder, RequestTelemetry telemetry) {
        this.snapshots = snapshots;
        this.forwarder = forwarder;
        this.telemetry = telemetry;
    }

    @Override
    public void handle(HttpServerRequest request) {
        long start = System.nanoTime();
        String requestId = RequestIds.next();
        Trace trace = telemetry.begin(request, start);
        Snapshot snapshot = snapshots.current();
        if (snapshot == null) {
            reject(request, 503, requestId, trace, start);
            return;
        }
        int invalid = RequestValidator.check(request, snapshot.settings().maxRequestBodyBytes());
        if (invalid != 0) {
            reject(request, invalid, requestId, trace, start);
            return;
        }
        String host = host(request);
        if (host == null) {
            reject(request, 400, requestId, trace, start);
            return;
        }
        if (!snapshot.router().match(host, request.method().name(), request.path(), request.query(),
                request.headers(), match)) {
            reject(request, 404, requestId, trace, start);
            return;
        }
        if (trace != null) {
            trace.route(match.route());
        }
        // The body is read by the forwarder (or a buffering plugin) once the chain admits the request.
        request.pause();
        int[] params = match.paramCount() == 0 ? NO_PARAMS : match.paramOffsets();
        new ExchangeImpl(request, snapshot, match.route(), params, forwarder, requestId, host, start, telemetry,
                trace).run();
    }

    private void reject(HttpServerRequest request, int status, String requestId, Trace trace, long start) {
        sendProblem(request, status, Problems.title(status), requestId);
        telemetry.rejected(request, status, requestId, trace, start);
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
