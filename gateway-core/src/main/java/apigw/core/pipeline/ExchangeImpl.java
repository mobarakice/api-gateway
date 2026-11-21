package apigw.core.pipeline;

import apigw.core.auth.Principal;
import apigw.core.plugin.LogPlugin;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.RequestPlugin;
import apigw.core.plugin.ResponseHeadersPlugin;
import apigw.core.snapshot.CompiledRoute;
import apigw.core.snapshot.CompiledSettings;
import apigw.core.snapshot.Snapshot;
import apigw.model.Cidr;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.SocketAddress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One request on one event loop. Not thread-safe by design: only its event loop touches it. */
final class ExchangeImpl implements ProxyExchange {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeImpl.class);

    private final HttpServerRequest request;
    private final Snapshot snapshot;
    private final CompiledRoute route;
    private final Forwarder forwarder;
    private final String requestId;
    private final int[] params;
    private final String clientIp;
    private final MultiMap upstreamHeaders;
    private final long startNanos;
    private String upstreamUri;
    private int next;
    private boolean responded;
    private boolean finished;
    private int status;
    private Runnable abortAction;
    private boolean closeAfterResponse;
    private Principal principal;
    /** The consumer's plugin overrides by ordinal, or null. */
    private Plugin[] overrides;
    private apigw.core.plugin.ResponseTap tap;
    private long bodyLimit = -1;
    /** Headers plugins asked to add to the response; null until the first one. */
    private MultiMap extraResponseHeaders;

    ExchangeImpl(HttpServerRequest request, Snapshot snapshot, CompiledRoute route, int[] params,
            Forwarder forwarder, String requestId, String host, long startNanos) {
        this.request = request;
        this.snapshot = snapshot;
        this.route = route;
        this.params = params;
        this.forwarder = forwarder;
        this.requestId = requestId;
        this.startNanos = startNanos;
        String uri = request.uri();
        if (uri.isEmpty() || uri.charAt(0) != '/') {
            String q = request.query();
            uri = q == null ? request.path() : request.path() + "?" + q;
        }
        this.upstreamUri = uri;
        CompiledSettings settings = snapshot.settings();
        SocketAddress remote = request.remoteAddress();
        String peer = remote == null || remote.hostAddress() == null ? "" : remote.hostAddress();
        boolean peerTrusted = !settings.trustedProxies().isEmpty() && trusted(settings, peer);
        MultiMap in = request.headers();
        String xff = peerTrusted ? in.get("x-forwarded-for") : null;
        this.clientIp = peerTrusted && xff != null ? clientFromChain(settings, xff, peer) : peer;
        this.upstreamHeaders = Headers.forwardable(in, settings.dropInbound());
        String proto = request.isSSL() ? "https" : "http";
        String inProto = peerTrusted ? in.get("x-forwarded-proto") : null;
        String inHost = peerTrusted ? in.get("x-forwarded-host") : null;
        String fwdHost = inHost != null ? inHost : host;
        upstreamHeaders.set("x-forwarded-for", xff == null ? peer : xff + ", " + peer);
        upstreamHeaders.set("x-forwarded-proto", inProto != null ? inProto : proto);
        if (!fwdHost.isEmpty()) {
            upstreamHeaders.set("x-forwarded-host", fwdHost);
        }
        String element = forwardedElement(peer, proto, host);
        String inForwarded = peerTrusted ? in.get("forwarded") : null;
        upstreamHeaders.set("forwarded", inForwarded == null ? element : inForwarded + ", " + element);
        upstreamHeaders.set(Problems.REQUEST_ID, requestId);
        Headers.appendVia(upstreamHeaders, settings.via(request.version()));
        HttpServerResponse response = request.response();
        response.endHandler(v -> complete());
        response.closeHandler(v -> clientGone());
    }

    private static boolean trusted(CompiledSettings settings, String ip) {
        try {
            return settings.trusted(Cidr.literal(ip));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Rightmost address in the chain that is not a trusted proxy; leftmost if all are trusted. */
    static String clientFromChain(CompiledSettings settings, String xff, String peer) {
        String candidate = peer;
        int end = xff.length();
        while (end > 0) {
            int comma = xff.lastIndexOf(',', end - 1);
            String hop = xff.substring(comma + 1, end).trim();
            if (!hop.isEmpty()) {
                candidate = hop;
                if (!trusted(settings, hop)) {
                    return hop;
                }
            }
            end = comma < 0 ? 0 : comma;
        }
        return candidate;
    }

    private static String forwardedElement(String peer, String proto, String host) {
        String forNode = peer.indexOf(':') >= 0 ? "\"[" + peer + "]\"" : peer.isEmpty() ? "unknown" : peer;
        StringBuilder b = new StringBuilder(64).append("for=").append(forNode).append(";proto=").append(proto);
        if (!host.isEmpty()) {
            b.append(";host=\"").append(host).append('"');
        }
        return b.toString();
    }

    /** Runs the request chain from the current position, then forwards. */
    void run() {
        RequestPlugin[] chain = route.requestChain();
        int[] slots = route.requestSlots();
        while (next < chain.length) {
            int i = next++;
            RequestPlugin plugin = chain[i];
            Plugin[] o = overrides;
            if (o != null) {
                Plugin replacement = o[slots[i]];
                if (replacement != null) {
                    if (!(replacement instanceof RequestPlugin rp)) {
                        // CompiledConsumer.DISABLED, or an override without a request phase.
                        continue;
                    }
                    plugin = rp;
                }
            }
            Outcome outcome = invoke(plugin);
            if (outcome == null) {
                respond(500, "Internal Server Error");
                return;
            }
            if (outcome != Outcome.CONTINUE || responded) {
                return;
            }
        }
        if (responded) {
            return;
        }
        if (route.tenantId() != null && principal == null) {
            // Tenant routes never serve anonymous requests, even when every auth plugin is optional.
            respond(401, "Unauthorized");
            return;
        }
        forwarder.forward(this);
    }

    @Override
    public Principal principal() {
        return principal;
    }

    @Override
    public Outcome authenticate(Principal p) {
        String routeTenant = route.tenantId();
        if (routeTenant != null && !routeTenant.equals(p.tenantId())) {
            LOG.warn("cross-tenant request denied route={} requestId={} method={} credential={}", route.id(),
                    requestId, p.method(), p.credentialId());
            return respond(403, "Forbidden");
        }
        principal = p;
        overrides = p.consumer() == null ? null : p.consumer().overrides();
        if (p.consumer() != null) {
            upstreamHeaders.set("x-consumer-id", p.consumer().id());
            if (!p.consumer().username().isEmpty() && safeHeaderValue(p.consumer().username())) {
                upstreamHeaders.set("x-consumer-username", p.consumer().username());
            }
        }
        if (p.tenantId() != null) {
            upstreamHeaders.set("x-tenant-id", p.tenantId());
        }
        if (p.subject() != null && safeHeaderValue(p.subject())) {
            upstreamHeaders.set("x-authenticated-user", p.subject());
        }
        if (p.credentialId() != null && safeHeaderValue(p.credentialId())) {
            upstreamHeaders.set("x-credential-identifier", p.credentialId());
        }
        return Outcome.CONTINUE;
    }

    /** Token claims are attacker-influenced: only visible ASCII (no CR/LF, no controls) goes into a header. */
    private static boolean safeHeaderValue(String v) {
        if (v.length() > 1024) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Outcome respond(int code, String title, String headerName, String headerValue) {
        if (!finished && !responded && !request.response().headWritten()) {
            request.response().putHeader(headerName, headerValue);
        }
        return respond(code, title);
    }

    @Override
    public Outcome respond(int code, String title, MultiMap headers) {
        if (!finished && !responded && !request.response().headWritten()) {
            request.response().headers().addAll(headers);
        }
        return respond(code, title);
    }

    @Override
    public Outcome respondWith(int code, MultiMap headers, io.vertx.core.buffer.Buffer body) {
        if (!beginResponse(code, headers)) {
            return Outcome.STOP;
        }
        HttpServerResponse response = request.response();
        if (!request.isEnded() && request.version() != HttpVersion.HTTP_2) {
            closeAfterResponse = true;
            headers.set("connection", "close");
        }
        response.setStatusCode(code);
        response.headers().addAll(headers);
        boolean bodyless = request.method() == io.vertx.core.http.HttpMethod.HEAD || code == 204 || code == 304;
        if (body == null || body.length() == 0 || bodyless) {
            if (!bodyless && !headers.contains("content-length")) {
                response.putHeader("content-length", "0");
            }
            response.end();
        } else {
            response.putHeader("content-length", Integer.toString(body.length()));
            response.end(body);
        }
        return Outcome.STOP;
    }

    @Override
    public void addResponseHeader(CharSequence name, CharSequence value) {
        if (extraResponseHeaders == null) {
            extraResponseHeaders = MultiMap.caseInsensitiveMultiMap();
        }
        extraResponseHeaders.add(name, value);
    }

    @Override
    public void limitRequestBody(long maxBytes) {
        long global = snapshot.settings().maxRequestBodyBytes();
        long current = bodyLimit < 0 ? global : bodyLimit;
        bodyLimit = Math.min(current, Math.max(0, maxBytes));
    }

    @Override
    public long requestBodyLimit() {
        return bodyLimit < 0 ? snapshot.settings().maxRequestBodyBytes() : bodyLimit;
    }

    @Override
    public void tapResponse(apigw.core.plugin.ResponseTap t) {
        this.tap = t;
    }

    @Override
    public apigw.core.plugin.ResponseTap responseTap() {
        return tap;
    }

    @Override
    public void resume() {
        if (!responded && !finished) {
            run();
        }
    }

    @Override
    public HttpServerRequest request() {
        return request;
    }

    @Override
    public CompiledRoute route() {
        return route;
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public String pathParam(String name) {
        List<String> names = route.paramNames();
        for (int i = 0; i < names.size() && 2 * i + 1 < params.length; i++) {
            if (names.get(i).equals(name)) {
                return request.path().substring(params[2 * i], params[2 * i + 1]);
            }
        }
        return null;
    }

    @Override
    public MultiMap upstreamHeaders() {
        return upstreamHeaders;
    }

    @Override
    public String upstreamUri() {
        return upstreamUri;
    }

    @Override
    public void upstreamUri(String uri) {
        this.upstreamUri = uri;
    }

    @Override
    public String clientIp() {
        return clientIp;
    }

    @Override
    public String requestId() {
        return requestId;
    }

    @Override
    public Outcome respond(int code, String title) {
        if (finished) {
            return Outcome.STOP;
        }
        responded = true;
        HttpServerResponse response = request.response();
        if (response.headWritten()) {
            // Too late for a clean error: the client sees a reset/closed stream instead of a truncated success.
            status = code;
            response.reset();
            finish();
            return Outcome.STOP;
        }
        status = code;
        if (!request.isEnded() && request.version() != HttpVersion.HTTP_2) {
            // Unread body of unknown size: close the connection after the response instead of draining it.
            closeAfterResponse = true;
            response.putHeader("connection", "close");
        }
        if (extraResponseHeaders != null) {
            response.headers().addAll(extraResponseHeaders);
        }
        if (route.headerFilter().length > 0) {
            // Gateway errors pass the header filters too, e.g. so a browser can read a CORS 401 or 429.
            runHeaderFilters(code, response.headers());
        }
        Problems.send(response, code, title, requestId);
        return Outcome.STOP;
    }

    @Override
    public boolean beginResponse(int code, MultiMap headers) {
        if (finished || responded) {
            return false;
        }
        responded = true;
        status = code;
        if (extraResponseHeaders != null) {
            headers.addAll(extraResponseHeaders);
        }
        runHeaderFilters(code, headers);
        headers.set(Problems.REQUEST_ID, requestId);
        Headers.appendVia(headers, snapshot.settings().via(HttpVersion.HTTP_1_1));
        return true;
    }

    private void runHeaderFilters(int code, MultiMap headers) {
        ResponseHeadersPlugin[] filters = route.headerFilter();
        int[] slots = route.headerFilterSlots();
        Plugin[] o = overrides;
        for (int i = 0; i < filters.length; i++) {
            ResponseHeadersPlugin f = filters[i];
            if (o != null && o[slots[i]] != null) {
                if (!(o[slots[i]] instanceof ResponseHeadersPlugin r)) {
                    continue;
                }
                f = r;
            }
            invoke(f, code, headers);
        }
    }

    @Override
    public void onAbort(Runnable action) {
        this.abortAction = action;
    }

    @Override
    public boolean finished() {
        return finished;
    }

    private void clientGone() {
        if (finished) {
            return;
        }
        Runnable a = abortAction;
        abortAction = null;
        if (a != null) {
            a.run();
        }
        if (status == 0) {
            status = 499;
        }
        finish();
    }

    private void complete() {
        if (closeAfterResponse) {
            request.connection().close();
        }
        if (!finished) {
            finish();
        }
    }

    private void finish() {
        finished = true;
        abortAction = null;
        LogPlugin[] logs = route.logPlugins();
        if (logs.length == 0) {
            return;
        }
        long duration = System.nanoTime() - startNanos;
        int s = status == 0 ? request.response().getStatusCode() : status;
        int[] slots = route.logSlots();
        Plugin[] o = overrides;
        for (int i = 0; i < logs.length; i++) {
            LogPlugin p = logs[i];
            if (o != null && o[slots[i]] != null) {
                if (!(o[slots[i]] instanceof LogPlugin l)) {
                    continue;
                }
                p = l;
            }
            invoke(p, s, duration);
        }
    }

    // Plugin isolation: a faulty plugin fails its own request (500) or is skipped in late phases, and is
    // logged with route and request ID. It must never take down the event loop or other requests.

    /** Returns null when the plugin threw. */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private Outcome invoke(RequestPlugin plugin) {
        try {
            return plugin.onRequest(this);
        } catch (RuntimeException e) {
            pluginFailed(plugin, e);
            return null;
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private void invoke(ResponseHeadersPlugin plugin, int code, MultiMap headers) {
        try {
            plugin.onResponseHeaders(this, code, headers);
        } catch (RuntimeException e) {
            pluginFailed(plugin, e);
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private void invoke(LogPlugin plugin, int code, long durationNanos) {
        try {
            plugin.onComplete(this, code, durationNanos);
        } catch (RuntimeException e) {
            pluginFailed(plugin, e);
        }
    }

    private void pluginFailed(Object plugin, RuntimeException e) {
        LOG.error("plugin failed route={} requestId={} plugin={}", route.id(), requestId,
                plugin.getClass().getSimpleName(), e);
    }
}
