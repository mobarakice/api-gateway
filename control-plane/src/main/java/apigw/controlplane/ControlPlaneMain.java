package apigw.controlplane;

import apigw.config.etcd.EtcdHttpClient;
import apigw.controlplane.api.AdminApi;
import apigw.controlplane.audit.AuditLog;
import apigw.controlplane.audit.AuditSink;
import apigw.controlplane.audit.FileAuditSink;
import apigw.controlplane.auth.AdminAuth;
import apigw.controlplane.changes.ChangeSets;
import apigw.controlplane.changes.DryRun;
import apigw.controlplane.changes.Publisher;
import apigw.controlplane.store.EtcdKvStore;
import apigw.controlplane.store.KvStore;
import apigw.observability.logging.JsonLogging;
import apigw.plugins.BuiltinPlugins;
import apigw.plugins.jwt.Jwks;
import apigw.plugins.jwt.KeySet;
import apigw.plugins.jwt.OidcKeySources;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.PemKeyCertOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Control-plane entry point and constructor wiring (settings: {@code CP_*} environment variables). */
public final class ControlPlaneMain {

    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneMain.class);
    private static final long AUDIT_TRIM_MS = 60_000;

    private ControlPlaneMain() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            System.err.println("usage: control-plane   (configured through CP_* environment variables)");
            System.exit(2);
        }
        CpConfig config;
        try {
            config = CpConfig.fromEnvironment(System.getenv());
        } catch (IllegalArgumentException e) {
            LOG.error("invalid startup configuration: {}", e.getMessage());
            JsonLogging.flush(Duration.ofSeconds(2));
            System.exit(2);
            return;
        }
        long started = System.nanoTime();
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2).setPreferNativeTransport(false));
        Clock clock = Clock.systemUTC();
        EtcdHttpClient etcd = EtcdHttpClient.create(vertx, config.etcd());
        KvStore kv = new EtcdKvStore(etcd);
        Future<Optional<AuditSink>> sink = config.auditFile().isEmpty() ? Future.succeededFuture(Optional.empty())
                : FileAuditSink.open(vertx, config.auditFile().get()).map(Optional::of);
        AtomicReference<HttpServer> server = new AtomicReference<>();
        sink.compose(s -> {
            AuditLog audit = new AuditLog(config.prefix(), kv, clock, s, config.auditWindow());
            ChangeSets changeSets = new ChangeSets(config.prefix(), kv, audit, clock, config.limits());
            Publisher publisher = new Publisher(config.prefix(), kv, changeSets, audit, new DryRun(vertx, clock), clock,
                    config.limits());
            AdminAuth auth = new AdminAuth(config.oidc(), keys(vertx, config), clock);
            AtomicBoolean migrated = new AtomicBoolean();
            migrate(vertx, config.prefix(), kv, audit, migrated, 200);
            AdminApi api = new AdminApi(config.prefix(), kv, auth, changeSets, publisher, audit,
                    BuiltinPlugins.registry(), clock, config.trustedProxies(),
                    () -> migrated.get() ? kv.get(config.prefix() + "meta/schema-version").map(Optional::isPresent)
                            : Future.succeededFuture(false));
            vertx.setPeriodic(AUDIT_TRIM_MS, id -> audit.trim());
            HttpServerOptions o = new HttpServerOptions().setHost(config.bindHost()).setPort(config.port())
                    .setMaxHeaderSize(16 * 1024).setIdleTimeout(60).setIdleTimeoutUnit(TimeUnit.SECONDS);
            if (config.tlsCert().isPresent()) {
                o.setSsl(true).setUseAlpn(true).setAlpnVersions(List.of(HttpVersion.HTTP_2, HttpVersion.HTTP_1_1))
                        .setKeyCertOptions(new PemKeyCertOptions().setCertPath(config.tlsCert().get().toString())
                                .setKeyPath(config.tlsKey().get().toString()));
            } else {
                LOG.warn("CP_TLS_CERT not set: the Admin API is plaintext; terminate TLS in front of it");
            }
            io.vertx.core.Handler<io.vertx.core.http.HttpServerRequest> handler = config.corsOrigins().isEmpty() ? api
                    : new apigw.controlplane.api.Cors(config.corsOrigins(), api);
            if (!config.corsOrigins().isEmpty()) {
                LOG.info("CORS origins {}", config.corsOrigins());
            }
            return vertx.createHttpServer(o).requestHandler(handler).listen();
        }).onSuccess(s -> {
            server.set(s);
            LOG.info("control plane listening port={} tls={} prefix={} startupMs={}", s.actualPort(),
                    config.tlsCert().isPresent(), config.prefix(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }).onFailure(e -> {
            LOG.error("control plane failed to start: {}", e.toString());
            exit(1);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(vertx, server.get()), "control-plane-shutdown"));
    }

    /**
     * Exits from a fresh thread: {@code System.exit} runs the shutdown hook, which closes Vert.x and
     * waits for its event loops, so calling it on an event loop would deadlock.
     */
    private static void exit(int status) {
        Thread t = new Thread(() -> {
            JsonLogging.flush(Duration.ofSeconds(2));
            System.exit(status);
        }, "control-plane-exit");
        t.setDaemon(false);
        t.start();
    }

    private static void shutdown(Vertx vertx, HttpServer server) {
        LOG.info("shutdown requested");
        try {
            if (server != null) {
                // In-flight requests finish (each is at most one etcd transaction); idle keep-alives close.
                server.shutdown(Duration.ofSeconds(5)).toCompletionStage().toCompletableFuture()
                        .get(7, TimeUnit.SECONDS);
            }
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            LOG.info("control plane stopped");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("shutdown interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            LOG.warn("shutdown did not complete cleanly: {}", e.toString());
        } finally {
            JsonLogging.flush(Duration.ofSeconds(2));
        }
    }

    /** Initialises/migrates the keyspace, retrying with jittered backoff while etcd is unreachable. */
    private static void migrate(Vertx vertx, String prefix, KvStore kv, AuditLog audit, AtomicBoolean done,
            long delayMs) {
        Migrations.run(prefix, kv, audit).onSuccess(v -> {
            done.set(true);
            LOG.info("keyspace ready schemaVersion={}", v);
        }).onFailure(e -> {
            if (e instanceof IllegalStateException) {
                LOG.error("keyspace not usable: {}", e.getMessage());
                exit(1);
                return;
            }
            long next = Math.min(10_000, delayMs * 2);
            LOG.warn("keyspace check failed, retrying cause={}", e.getMessage());
            vertx.setTimer(1 + ThreadLocalRandom.current().nextLong(next),
                    id -> migrate(vertx, prefix, kv, audit, done, next));
        });
    }

    private static AdminAuth.Keys keys(Vertx vertx, CpConfig config) {
        if (config.jwksFile().isPresent()) {
            return new FileKeys(config.jwksFile().get());
        }
        OidcKeySources.Source src = new OidcKeySources(vertx, Duration.ofSeconds(5)).source(new OidcKeySources.Spec(
                config.discovery() ? config.oidc().issuer() : null, config.jwksUri().orElse(null), config.oidcCa(),
                Duration.ofMinutes(10), Duration.ofSeconds(30)));
        return new AdminAuth.Keys() {
            @Override
            public KeySet get() {
                return src.keys();
            }

            @Override
            public Future<Boolean> refresh() {
                return src.refreshForUnknownKey();
            }
        };
    }

    /** A JWKS file, re-read (at most every 10 s) when a token names an unknown key: rotation without restart. */
    static final class FileKeys implements AdminAuth.Keys {
        private final Path file;
        private final AtomicReference<KeySet> keys = new AtomicReference<>(KeySet.EMPTY);
        private final AtomicLong lastLoad = new AtomicLong();

        FileKeys(Path file) {
            this.file = file;
            load();
            if (keys.get().all().isEmpty()) {
                throw new IllegalArgumentException("CP_OIDC_JWKS_FILE has no usable keys");
            }
        }

        private boolean load() {
            lastLoad.set(System.nanoTime());
            try {
                Jwks.Parsed p = Jwks.parse(Json.parse(Files.readAllBytes(file)));
                if (p.keys().all().isEmpty()) {
                    return false;
                }
                keys.set(p.keys());
                return true;
            } catch (java.io.IOException | IllegalArgumentException e) {
                LOG.warn("cannot load CP_OIDC_JWKS_FILE: {}", e.getClass().getSimpleName());
                return false;
            }
        }

        @Override
        public KeySet get() {
            return keys.get();
        }

        @Override
        public Future<Boolean> refresh() {
            if (System.nanoTime() - lastLoad.get() < TimeUnit.SECONDS.toNanos(10)) {
                return Future.succeededFuture(false);
            }
            return Future.succeededFuture(load());
        }
    }
}
