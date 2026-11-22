package apigw.proxy;

import apigw.core.observe.AttemptOutcome;
import apigw.core.observe.RouteTelemetry;
import apigw.core.observe.Trace;
import apigw.core.pipeline.Headers;
import apigw.core.pipeline.Problems;
import apigw.core.pipeline.ProxyExchange;
import apigw.core.snapshot.CompiledUpstream;
import apigw.core.snapshot.ResolvedTimeouts;
import apigw.core.snapshot.RetryPolicy;
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
import io.vertx.core.net.SocketAddress;
import io.vertx.core.streams.ReadStream;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One proxied exchange, made of one or more attempts (ADR 0017).
 *
 * <p>Timeouts: connect and first byte per attempt (first byte runs from the end of the request body to
 * the response head), idle per attempt (enforced by the client stream), total over all attempts.
 * Before response headers are sent a failure becomes a generic 502/503/504; after, the client stream is
 * reset so a truncated body is never mistaken for success.
 *
 * <p>Each attempt asks the circuit breaker for a permit, picks an available target (health gate,
 * excluding the previous target when possible), and reports its outcome to the breaker and the gate.
 * A failed attempt is retried only if the route's policy allows the method and the failure kind, the
 * retry budget has room, nothing was sent to the client yet, and — unless it was a connect failure,
 * where the request body is still unread — the request has no body.
 */
final class ProxyCall {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyCall.class);

    /** Why an attempt failed. */
    private enum Kind { CONNECT, RESET, TIMEOUT, STATUS, OVERFLOW, DENIED }

    private final Vertx vertx;
    private final TargetResolver resolver;
    private final UpstreamState state;
    private final ProxyExchange exchange;
    private final RetryBudget budget;
    private final RetryPolicy retries;
    private final boolean hasBody;
    private int attempt;
    /** Bumped when an attempt starts and when it ends: callbacks of a finished attempt see a stale epoch. */
    private int epoch;
    private int target = -1;
    private int permit;
    private long attemptStartMs;
    private long attemptStartNanos;
    private final RouteTelemetry meters;
    private final Trace trace;
    private boolean attemptOpen;
    private boolean bodyStarted;
    private HttpClientRequest upstream;
    private long firstByteTimer = -1;
    private long totalTimer = -1;
    private long backoffTimer = -1;
    private boolean released;
    private boolean headersReceived;
    private TapStream tapped;

    ProxyCall(Vertx vertx, TargetResolver resolver, UpstreamState state, ProxyExchange exchange, RetryBudget budget) {
        this.vertx = vertx;
        this.resolver = resolver;
        this.state = state;
        this.exchange = exchange;
        this.budget = budget;
        this.retries = exchange.route().retries();
        this.meters = exchange.route().telemetry();
        this.trace = exchange.trace();
        HttpServerRequest req = exchange.request();
        String cl = req.getHeader("content-length");
        this.hasBody = cl != null ? !"0".equals(cl.trim())
                : req.version() == HttpVersion.HTTP_2 ? !req.isEnded() : req.headers().contains("transfer-encoding");
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    void start() {
        ResolvedTimeouts to = exchange.route().timeouts();
        if (to.totalMs() > 0) {
            totalTimer = vertx.setTimer(to.totalMs(), id -> {
                totalTimer = -1;
                timeout("total");
            });
        }
        exchange.onAbort(this::clientGone);
        if (budget != null) {
            budget.onRequest(nowMs());
        }
        attemptStart();
    }

    private void attemptStart() {
        backoffTimer = -1;
        if (released) {
            return;
        }
        long now = nowMs();
        if (state.breaker != null) {
            permit = state.breaker.tryAcquire(now);
            if (permit == CircuitBreaker.REJECTED) {
                LOG.debug("circuit open route={} requestId={} upstream={}", exchange.route().id(),
                        exchange.requestId(), state.definition.id());
                meters.attempt(AttemptOutcome.CIRCUIT_OPEN, 0, 0, attempt);
                finish(503);
                return;
            }
        }
        target = state.pick(exchange, attempt == 0 ? -1 : target, now);
        state.inflight[target]++;
        epoch++;
        attemptOpen = true;
        attemptStartMs = now;
        attemptStartNanos = System.nanoTime();
        headersReceived = false;
        upstream = null;
        CompiledUpstream.Target t = state.definition.targets().get(target);
        if (t.literal()) {
            // Literal targets were checked against the SSRF rules when the snapshot compiled.
            connect(t, null);
            return;
        }
        int thisAttempt = epoch;
        resolver.resolve(t.host(), exchange.snapshot().settings().upstreamAllow()).onComplete(ar -> {
            if (released || epoch != thisAttempt) {
                return;
            }
            if (ar.failed()) {
                attemptFailed(ar.cause() instanceof TargetResolver.DeniedAddressException ? Kind.DENIED
                        : Kind.CONNECT, ar.cause());
                return;
            }
            connect(t, ar.result());
        });
    }

    /** Connects to {@code address} (the checked IP) when given, keeping the name for Host and SNI. */
    private void connect(CompiledUpstream.Target t, InetAddress address) {
        HttpServerRequest req = exchange.request();
        ResolvedTimeouts to = exchange.route().timeouts();
        if (trace != null) {
            trace.attemptStart(exchange.upstreamHeaders(), t.host(), t.port(), attempt);
        }
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
            options.setServer(SocketAddress.inetSocketAddress(t.port(), address.getHostAddress()));
        }
        int thisAttempt = epoch;
        state.client.request(options).onComplete(ar -> {
            if (epoch != thisAttempt) {
                if (ar.succeeded()) {
                    ar.result().reset();
                }
                return;
            }
            if (ar.failed()) {
                attemptFailed(ar.cause() instanceof ConnectionPoolTooBusyException ? Kind.OVERFLOW : Kind.CONNECT,
                        ar.cause());
                return;
            }
            upstream = ar.result();
            if (released) {
                upstream.reset();
                return;
            }
            send(req, to, thisAttempt);
        });
    }

    private void send(HttpServerRequest req, ResolvedTimeouts to, int thisAttempt) {
        if (exchange.route().preserveHost() && req.authority() != null) {
            upstream.authority(req.authority());
        }
        HttpClientRequest current = upstream;
        current.response().onComplete(rr -> {
            if (epoch != thisAttempt || released) {
                if (rr.succeeded()) {
                    current.reset();
                }
                return;
            }
            cancelFirstByte();
            if (rr.failed()) {
                attemptFailed(rr.cause() instanceof TimeoutException ? Kind.TIMEOUT : Kind.RESET, rr.cause());
            } else {
                headersReceived = true;
                onResponse(rr.result());
            }
        });
        if (!hasBody) {
            // Nothing to stream: end the upstream request now (also what makes a later retry possible),
            // and let the paused server request deliver its end so keep-alive proceeds.
            req.resume();
            current.end().onComplete(er -> afterBody(er.succeeded(), er.cause(), to, thisAttempt));
            return;
        }
        ReadStream<Buffer> body = req;
        if (!req.headers().contains("content-length")) {
            if (current.version() != HttpVersion.HTTP_2) {
                current.setChunked(true);
            }
            body = new LimitedBody(req, exchange.requestBodyLimit(), this::tooLarge);
        }
        bodyStarted = true;
        body.pipeTo(current).onComplete(pr -> afterBody(pr.succeeded(), pr.cause(), to, thisAttempt));
    }

    private void afterBody(boolean ok, Throwable cause, ResolvedTimeouts to, int thisAttempt) {
        if (epoch != thisAttempt || released) {
            return;
        }
        if (!ok) {
            attemptFailed(Kind.RESET, cause);
        } else if (!headersReceived) {
            firstByteTimer = vertx.setTimer(to.firstByteMs(), id -> {
                firstByteTimer = -1;
                if (epoch == thisAttempt) {
                    attemptFailed(Kind.TIMEOUT, new TimeoutException("first-byte"));
                }
            });
        }
    }

    private void onResponse(HttpClientResponse resp) {
        int status = resp.statusCode();
        long elapsed = nowMs() - attemptStartMs;
        meters.attempt(AttemptOutcome.RESPONSE, status, System.nanoTime() - attemptStartNanos, attempt);
        if (trace != null) {
            trace.attemptResult(status, null);
        }
        boolean breakerFailure = state.breaker != null && state.breaker.isFailureStatus(status);
        boolean targetFailure = status == 502 || status == 503 || status == 504;
        record(breakerFailure, targetFailure, elapsed);
        if (retries.onStatus(status) && mayRetry(Kind.STATUS)) {
            LOG.debug("retrying route={} requestId={} upstream={} attempt={} status={}", exchange.route().id(),
                    exchange.requestId(), state.definition.id(), attempt + 1, status);
            endAttempt();
            resp.request().reset();
            scheduleRetry();
            return;
        }
        respond(resp, status);
    }

    private void respond(HttpClientResponse resp, int status) {
        MultiMap headers = Headers.forwardable(resp.headers(), Set.of());
        apigw.core.plugin.ResponseTap tap = exchange.responseTap();
        boolean observe = tap != null && tap.onHead(status, headers);
        if (!exchange.beginResponse(status, headers)) {
            if (observe) {
                tap.onAbort();
            }
            release();
            upstream.reset();
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
        ReadStream<Buffer> body = resp;
        if (observe) {
            tapped = new TapStream(resp, tap);
            body = tapped;
        }
        // send() pipes with backpressure: upstream reads pause while the client socket is not writable.
        out.send(body).onComplete(ar -> {
            if (tapped != null) {
                if (ar.succeeded()) {
                    tapped.completed();
                } else {
                    tapped.abort();
                }
            }
            complete(ar.succeeded());
            if (ar.failed() && !exchange.finished()) {
                LOG.debug("response stream failed route={} requestId={} cause={}", exchange.route().id(),
                        exchange.requestId(), ar.cause().getClass().getSimpleName());
                exchange.respond(502, Problems.title(502));
            }
        });
    }

    /** Reports an attempt's result to the breaker and the target gate (once per attempt). */
    private void record(boolean breakerFailure, boolean targetFailure, long elapsedMs) {
        long now = nowMs();
        if (state.breaker != null && permit != CircuitBreaker.REJECTED) {
            state.breaker.onResult(permit, breakerFailure, state.breaker.isSlow(elapsedMs), now);
            permit = CircuitBreaker.REJECTED;
        }
        if (state.gate != null) {
            if (targetFailure) {
                state.gate.onFailure(target, now);
            } else {
                state.gate.onSuccess(target);
            }
        }
    }

    private void attemptFailed(Kind kind, Throwable cause) {
        if (released) {
            return;
        }
        AttemptOutcome outcome = switch (kind) {
            case CONNECT -> AttemptOutcome.CONNECT_ERROR;
            case RESET, STATUS -> AttemptOutcome.RESET;
            case TIMEOUT -> AttemptOutcome.TIMEOUT;
            case OVERFLOW -> AttemptOutcome.POOL_FULL;
            case DENIED -> AttemptOutcome.DENIED;
        };
        meters.attempt(outcome, 0, attemptOpen ? System.nanoTime() - attemptStartNanos : 0, attempt);
        if (trace != null) {
            trace.attemptResult(0, outcome);
        }
        if (kind == Kind.OVERFLOW || kind == Kind.DENIED) {
            // Local pool overflow and SSRF denials say nothing about the target's health.
            if (state.breaker != null) {
                state.breaker.release(permit);
                permit = CircuitBreaker.REJECTED;
            }
        } else {
            record(true, true, nowMs() - attemptStartMs);
        }
        endAttempt();
        if (upstream != null) {
            upstream.reset();
        }
        if (kind != Kind.DENIED && kind != Kind.OVERFLOW && mayRetry(kind)) {
            LOG.debug("retrying route={} requestId={} upstream={} attempt={} kind={}", exchange.route().id(),
                    exchange.requestId(), state.definition.id(), attempt + 1, kind);
            scheduleRetry();
            return;
        }
        if (kind == Kind.DENIED) {
            // SSRF rule: never connect. The client only sees a generic 502.
            LOG.warn("upstream address denied route={} requestId={} upstream={}", exchange.route().id(),
                    exchange.requestId(), state.definition.id());
            finish(502);
            return;
        }
        int code = switch (kind) {
            case OVERFLOW -> 503;
            case TIMEOUT -> 504;
            default -> 502;
        };
        LOG.warn("upstream failed route={} requestId={} upstream={} status={} attempts={} cause={}: {}",
                exchange.route().id(), exchange.requestId(), state.definition.id(), code, attempt + 1,
                cause.getClass().getSimpleName(), cause.getMessage());
        finish(code);
    }

    private boolean mayRetry(Kind kind) {
        if (attempt >= retries.attempts() || exchange.finished()
                || !retries.allows(exchange.request().method().name())) {
            return false;
        }
        boolean bodySafe = !hasBody || !bodyStarted;
        boolean allowed = switch (kind) {
            case CONNECT -> retries.onConnectFailure();
            case RESET -> retries.onReset() && bodySafe;
            case TIMEOUT -> retries.onTimeout() && bodySafe;
            case STATUS -> bodySafe;
            default -> false;
        };
        return allowed && budget != null
                && budget.tryRetry(nowMs(), retries.budgetPercent(), retries.minRetriesPerSecond());
    }

    private void scheduleRetry() {
        attempt++;
        int cap = (int) Math.min(retries.maxBackoffMs(), (long) retries.backoffMs() << Math.min(attempt - 1, 20));
        int delay = cap <= 0 ? 0 : ThreadLocalRandom.current().nextInt(cap + 1);
        if (delay == 0) {
            vertx.runOnContext(v -> attemptStart());
        } else {
            backoffTimer = vertx.setTimer(delay, id -> attemptStart());
        }
    }

    private void timeout(String which) {
        if (released) {
            return;
        }
        LOG.warn("upstream timeout route={} requestId={} upstream={} timeout={}", exchange.route().id(),
                exchange.requestId(), state.definition.id(), which);
        if (attemptOpen) {
            record(true, true, nowMs() - attemptStartMs);
            if (!headersReceived) {
                meters.attempt(AttemptOutcome.TIMEOUT, 0, System.nanoTime() - attemptStartNanos, attempt);
            }
            if (trace != null) {
                trace.attemptResult(0, AttemptOutcome.TIMEOUT);
            }
        }
        finish(504);
    }

    private void tooLarge() {
        if (released) {
            return;
        }
        abandonPermit();
        finish(413);
    }

    private void clientGone() {
        if (released) {
            return;
        }
        abandonPermit();
        release();
    }

    private void abandonPermit() {
        if (state.breaker != null && permit != CircuitBreaker.REJECTED) {
            state.breaker.release(permit);
            permit = CircuitBreaker.REJECTED;
        }
    }

    /** Ends the exchange with a generic error (resetting any open upstream stream first). */
    private void finish(int code) {
        release();
        exchange.respond(code, Problems.title(code));
    }

    private void cancelFirstByte() {
        if (firstByteTimer >= 0) {
            vertx.cancelTimer(firstByteTimer);
            firstByteTimer = -1;
        }
    }

    private void endAttempt() {
        if (!attemptOpen) {
            return;
        }
        attemptOpen = false;
        epoch++;
        state.inflight[target]--;
        cancelFirstByte();
        if (trace != null) {
            trace.attemptEnd();
        }
    }

    /** The response stream ended (normally, or failed on its own): nothing to reset. */
    private void complete(boolean ok) {
        if (!ok && !released && upstream != null) {
            HttpClientRequest open = upstream;
            stop();
            open.reset();
            return;
        }
        stop();
    }

    /** Abandons the exchange (timeout, size cap, client gone, error): resets an open upstream stream. */
    private void release() {
        if (released) {
            return;
        }
        HttpClientRequest open = attemptOpen ? upstream : null;
        stop();
        if (open != null) {
            open.reset();
        }
    }

    private void stop() {
        if (released) {
            return;
        }
        released = true;
        if (tapped != null) {
            // No-op after completed(); otherwise the body never fully reached the client.
            tapped.abort();
        }
        endAttempt();
        if (totalTimer >= 0) {
            vertx.cancelTimer(totalTimer);
            totalTimer = -1;
        }
        if (backoffTimer >= 0) {
            vertx.cancelTimer(backoffTimer);
            backoffTimer = -1;
        }
    }
}
