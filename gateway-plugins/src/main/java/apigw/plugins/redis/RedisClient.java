package apigw.plugins.redis;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemTrustOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One pipelined RESP2 connection to Redis (ADR 0018). All connection state lives on one Vert.x
 * context; {@link #send} may be called from any thread. Commands fail fast while disconnected, and
 * each has a timeout, so callers (rate limiting, L2 cache) can apply their fail-open/fail-closed mode
 * without waiting. Reconnects with exponential backoff and full jitter.
 *
 * <p>Futures complete on the client's context: callers hop back to their own event loop.
 */
public final class RedisClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RedisClient.class);
    private static final int MAX_PENDING = 10_000;
    private static final long MIN_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 5_000;

    /**
     * @param password null for no AUTH; never logged
     * @param caFile   PEM trust for {@code rediss://}; empty = JDK trust store
     */
    public record Settings(String host, int port, boolean tls, Optional<Path> caFile, String user, String password,
            int database, Duration connectTimeout, Duration commandTimeout) {

        @Override
        public String toString() {
            // Never print the password.
            return "Redis[" + (tls ? "rediss://" : "redis://") + host + ":" + port + "/" + database + "]";
        }
    }

    /** Thrown into a command's future when it could not be sent or answered. */
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unavailable(String message) {
            super(message, null, false, false);
        }
    }

    private static final class Pending {
        final Promise<Reply> promise;
        final long timer;
        boolean done;

        Pending(Promise<Reply> promise, long timer) {
            this.promise = promise;
            this.timer = timer;
        }
    }

    private final Vertx vertx;
    private final Context context;
    private final Settings settings;
    private final NetClient net;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private NetSocket socket;
    private volatile boolean ready;
    private long backoffMs = MIN_BACKOFF_MS;
    private long reconnectTimer = -1;

    public RedisClient(Vertx vertx, Settings settings) {
        this.vertx = vertx;
        this.context = vertx.getOrCreateContext();
        this.settings = settings;
        NetClientOptions o = new NetClientOptions()
                .setConnectTimeout((int) settings.connectTimeout().toMillis())
                .setTcpNoDelay(true)
                .setTcpKeepAlive(true);
        if (settings.tls()) {
            o.setSsl(true).setHostnameVerificationAlgorithm("HTTPS");
            settings.caFile().ifPresent(ca -> o.setTrustOptions(new PemTrustOptions().addCertPath(ca.toString())));
        }
        this.net = vertx.createNetClient(o);
        context.runOnContext(v -> connect());
    }

    /** True while a connection is up and authenticated (a hint; commands may still fail). */
    public boolean ready() {
        return ready;
    }

    public Duration commandTimeout() {
        return settings.commandTimeout();
    }

    /** Sends a command with the default timeout. Arguments are strings or {@link Buffer}s. */
    public Future<Reply> send(Object... args) {
        return sendTimed(settings.commandTimeout().toMillis(), args);
    }

    /** {@link #send} with an explicit timeout (the caller's latency budget). */
    public Future<Reply> sendTimed(long timeoutMs, Object... args) {
        Buffer frame = encode(args);
        Promise<Reply> p = Promise.promise();
        if (!ready) {
            p.fail(new Unavailable("redis not connected"));
            return p.future();
        }
        context.runOnContext(v -> write(frame, p, timeoutMs, false));
        return p.future();
    }

    private void write(Buffer frame, Promise<Reply> p, long timeoutMs, boolean handshake) {
        if (socket == null || (!ready && !handshake)) {
            p.tryFail(new Unavailable("redis not connected"));
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            p.tryFail(new Unavailable("redis queue full"));
            return;
        }
        Pending[] self = new Pending[1];
        long timer = vertx.setTimer(Math.max(1, timeoutMs), id -> {
            Pending me = self[0];
            if (!me.done) {
                me.done = true;
                // The slot stays queued: its reply (if it ever comes) is matched and dropped in order.
                me.promise.tryFail(new Unavailable("redis command timed out"));
            }
        });
        self[0] = new Pending(p, timer);
        pending.addLast(self[0]);
        socket.write(frame);
    }

    private void onReply(Reply r) {
        Pending p = pending.pollFirst();
        if (p == null) {
            LOG.warn("redis reply without a pending command; reconnecting");
            socket.close();
            return;
        }
        vertx.cancelTimer(p.timer);
        if (!p.done) {
            p.done = true;
            p.promise.tryComplete(r);
        }
    }

    private void connect() {
        reconnectTimer = -1;
        if (closed.get()) {
            return;
        }
        net.connect(settings.port(), settings.host()).onComplete(ar -> {
            if (ar.failed()) {
                LOG.warn("redis connect failed target={} cause={}", settings, ar.cause().getClass().getSimpleName());
                scheduleReconnect();
                return;
            }
            NetSocket s = ar.result();
            socket = s;
            RespParser parser = new RespParser(this::onReply);
            s.handler(chunk -> {
                try {
                    parser.feed(chunk);
                } catch (RespParser.ProtocolException e) {
                    LOG.warn("redis protocol error target={} detail={}", settings, e.getMessage());
                    s.close();
                }
            });
            s.closeHandler(v -> onClosed(s));
            s.exceptionHandler(e -> LOG.debug("redis socket error target={} cause={}", settings,
                    e.getClass().getSimpleName()));
            handshake(s);
        });
    }

    private void handshake(NetSocket s) {
        Future<Reply> chain = Future.succeededFuture(new Reply.Simple("OK"));
        if (settings.password() != null) {
            chain = chain.compose(r -> command(settings.user() == null || settings.user().isEmpty()
                    ? encode("AUTH", settings.password()) : encode("AUTH", settings.user(), settings.password())));
        }
        if (settings.database() != 0) {
            chain = chain.compose(r -> expectOk(r, "AUTH")).compose(r -> command(encode("SELECT",
                    Integer.toString(settings.database()))));
        }
        chain.compose(r -> expectOk(r, "handshake")).compose(r -> command(encode("PING"))).onComplete(ar -> {
            if (socket != s) {
                return;
            }
            if (ar.failed() || ar.result() instanceof Reply.Error) {
                // Never include the server's message: it may echo the user name.
                LOG.warn("redis handshake failed target={}", settings);
                s.close();
                return;
            }
            ready = true;
            backoffMs = MIN_BACKOFF_MS;
            LOG.info("redis connected target={}", settings);
        });
    }

    private Future<Reply> command(Buffer frame) {
        Promise<Reply> p = Promise.promise();
        write(frame, p, settings.commandTimeout().toMillis() * 4, true);
        return p.future();
    }

    private static Future<Reply> expectOk(Reply r, String step) {
        return r instanceof Reply.Error ? Future.failedFuture(new Unavailable(step + " rejected"))
                : Future.succeededFuture(r);
    }

    private void onClosed(NetSocket s) {
        if (socket != s) {
            return;
        }
        boolean wasReady = ready;
        ready = false;
        socket = null;
        Pending p;
        while ((p = pending.pollFirst()) != null) {
            vertx.cancelTimer(p.timer);
            if (!p.done) {
                p.done = true;
                p.promise.tryFail(new Unavailable("redis connection closed"));
            }
        }
        if (wasReady) {
            LOG.warn("redis connection lost target={}", settings);
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed.get() || reconnectTimer >= 0) {
            return;
        }
        long delay = 1 + ThreadLocalRandom.current().nextLong(backoffMs);
        backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
        reconnectTimer = vertx.setTimer(delay, id -> connect());
    }

    /** RESP array of bulk strings. */
    static Buffer encode(Object... args) {
        Buffer b = Buffer.buffer(16 + args.length * 16);
        b.appendString("*").appendString(Integer.toString(args.length)).appendString("\r\n");
        for (Object a : args) {
            Buffer v = a instanceof Buffer buf ? buf
                    : Buffer.buffer(String.valueOf(a).getBytes(StandardCharsets.UTF_8));
            b.appendString("$").appendString(Integer.toString(v.length())).appendString("\r\n").appendBuffer(v)
                    .appendString("\r\n");
        }
        return b;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            context.runOnContext(v -> {
                if (reconnectTimer >= 0) {
                    vertx.cancelTimer(reconnectTimer);
                }
                ready = false;
                if (socket != null) {
                    socket.close();
                }
                net.close();
            });
        }
    }
}
