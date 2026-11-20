package apigw.proxy;

import apigw.core.pipeline.Headers;
import apigw.core.pipeline.Problems;
import apigw.core.pipeline.ProxyExchange;
import apigw.core.snapshot.CompiledUpstream;
import apigw.core.snapshot.ResolvedTimeouts;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One proxied exchange. Timeouts: connect (per request), first byte (from the end of the request
 * body until response headers), idle (no bytes either way, enforced by the client stream), total
 * (whole exchange, optional). Before response headers are sent a failure becomes a generic
 * 502/503/504; after, the client stream is reset so a truncated body is never mistaken for success.
 */
final class ProxyCall {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyCall.class);

    private final Vertx vertx;
    private final TargetResolver resolver;
    private final UpstreamState state;
    private final ProxyExchange exchange;
    private int target = -1;
    private HttpClientRequest upstream;
    private long firstByteTimer = -1;
    private long totalTimer = -1;
    private boolean released;
    private boolean headersReceived;

    ProxyCall(Vertx vertx, TargetResolver resolver, UpstreamState state, ProxyExchange exchange) {
        this.vertx = vertx;
        this.resolver = resolver;
        this.state = state;
        this.exchange = exchange;
    }

    void start() {
        HttpServerRequest req = exchange.request();
        ResolvedTimeouts to = exchange.route().timeouts();
        target = state.balancer.pick(exchange, state.inflight);
        state.inflight[target]++;
        CompiledUpstream.Target t = state.definition.targets().get(target);
        if (to.totalMs() > 0) {
            totalTimer = vertx.setTimer(to.totalMs(), id -> {
                totalTimer = -1;
                timeout("total");
            });
        }
        exchange.onAbort(this::clientGone);
        if (t.literal()) {
            // Literal targets were checked against the SSRF rules when the snapshot compiled.
            connect(req, to, t, null);
            return;
        }
        resolver.resolve(t.host(), exchange.snapshot().settings().upstreamAllow()).onComplete(ar -> {
            if (released) {
                return;
            }
            if (ar.failed()) {
                failed(ar.cause());
                return;
            }
            connect(req, to, t, ar.result());
        });
    }

    /** Connects to {@code address} (the checked IP) when given, keeping the name for Host and SNI. */
    private void connect(HttpServerRequest req, ResolvedTimeouts to, CompiledUpstream.Target t,
            java.net.InetAddress address) {
        RequestOptions options = new RequestOptions()
                .setMethod(req.method())
                .setHost(t.host())
                .setPort(t.port())
                .setURI(exchange.upstreamUri())
                .setHeaders(exchange.upstreamHeaders())
                .setConnectTimeout(to.connectMs())
                .setIdleTimeout(to.idleMs())
                .setFollowRedirects(false);
        if (address != null) {
            options.setServer(io.vertx.core.net.SocketAddress.inetSocketAddress(t.port(), address.getHostAddress()));
        }
        state.client.request(options).onComplete(ar -> {
            if (ar.failed()) {
                failed(ar.cause());
                return;
            }
            upstream = ar.result();
            if (released) {
                upstream.reset();
                return;
            }
            send(req, to);
        });
    }

    private void send(HttpServerRequest req, ResolvedTimeouts to) {
        if (exchange.route().preserveHost() && req.authority() != null) {
            upstream.authority(req.authority());
        }
        ReadStream<Buffer> body = req;
        if (!req.headers().contains("content-length")) {
            boolean mayHaveBody = req.version() == HttpVersion.HTTP_2 ? !req.isEnded()
                    : req.headers().contains("transfer-encoding");
            if (mayHaveBody) {
                if (upstream.version() != HttpVersion.HTTP_2) {
                    upstream.setChunked(true);
                }
                body = new LimitedBody(req, exchange.snapshot().settings().maxRequestBodyBytes(), this::tooLarge);
            }
        }
        upstream.response().onComplete(rr -> {
            cancelFirstByte();
            if (rr.failed()) {
                failed(rr.cause());
            } else {
                headersReceived = true;
                respond(rr.result());
            }
        });
        body.pipeTo(upstream).onComplete(pr -> {
            if (pr.failed()) {
                failed(pr.cause());
            } else if (!headersReceived && !released) {
                firstByteTimer = vertx.setTimer(to.firstByteMs(), id -> {
                    firstByteTimer = -1;
                    timeout("first-byte");
                });
            }
        });
    }

    private void respond(HttpClientResponse resp) {
        if (released) {
            return;
        }
        int status = resp.statusCode();
        MultiMap headers = Headers.forwardable(resp.headers(), Set.of());
        if (!exchange.beginResponse(status, headers)) {
            upstream.reset();
            release();
            return;
        }
        HttpServerRequest req = exchange.request();
        HttpServerResponse out = req.response();
        out.setStatusCode(status);
        out.headers().addAll(headers);
        boolean bodyless = req.method() == HttpMethod.HEAD || status == 204 || status == 304
                || (status >= 100 && status < 200);
        if (!bodyless && req.version() != HttpVersion.HTTP_2 && !headers.contains("content-length")) {
            out.setChunked(true);
        }
        // send() pipes with backpressure: upstream reads pause while the client socket is not writable.
        out.send(resp).onComplete(ar -> {
            release();
            if (ar.failed() && !exchange.finished()) {
                LOG.debug("response stream failed route={} requestId={} cause={}", exchange.route().id(),
                        exchange.requestId(), ar.cause().getClass().getSimpleName());
                exchange.respond(502, Problems.title(502));
            }
        });
    }

    private void failed(Throwable cause) {
        if (released) {
            return;
        }
        release();
        int code;
        if (cause instanceof TargetResolver.DeniedAddressException) {
            // SSRF rule: never connect. The client only sees a generic 502.
            LOG.warn("upstream address denied route={} requestId={} upstream={}", exchange.route().id(),
                    exchange.requestId(), state.definition.id());
            exchange.respond(502, Problems.title(502));
            return;
        } else if (cause instanceof ConnectionPoolTooBusyException) {
            code = 503;
        } else if (cause instanceof TimeoutException) {
            code = 504;
        } else {
            code = 502;
        }
        LOG.warn("upstream failed route={} requestId={} upstream={} status={} cause={}: {}", exchange.route().id(),
                exchange.requestId(), state.definition.id(), code, cause.getClass().getSimpleName(),
                cause.getMessage());
        if (upstream != null) {
            upstream.reset();
        }
        exchange.respond(code, Problems.title(code));
    }

    private void timeout(String which) {
        if (released) {
            return;
        }
        release();
        LOG.warn("upstream timeout route={} requestId={} upstream={} timeout={}", exchange.route().id(),
                exchange.requestId(), state.definition.id(), which);
        if (upstream != null) {
            upstream.reset();
        }
        exchange.respond(504, Problems.title(504));
    }

    private void tooLarge() {
        if (released) {
            return;
        }
        release();
        if (upstream != null) {
            upstream.reset();
        }
        exchange.respond(413, Problems.title(413));
    }

    private void clientGone() {
        if (released) {
            return;
        }
        release();
        if (upstream != null) {
            upstream.reset();
        }
    }

    private void cancelFirstByte() {
        if (firstByteTimer >= 0) {
            vertx.cancelTimer(firstByteTimer);
            firstByteTimer = -1;
        }
    }

    private void release() {
        if (released) {
            return;
        }
        released = true;
        state.inflight[target]--;
        cancelFirstByte();
        if (totalTimer >= 0) {
            vertx.cancelTimer(totalTimer);
            totalTimer = -1;
        }
    }
}
