package apigw.app;

import apigw.app.telemetry.GatewayTelemetry;
import apigw.app.telemetry.TelemetryConfig;
import apigw.config.worker.ConfigMetrics;
import apigw.core.observe.RouteTelemetryFactory;
import apigw.observability.metrics.MetricRegistry;
import apigw.observability.metrics.ProcessMetrics;
import apigw.observability.tracing.OtlpHttpExporter;
import apigw.observability.tracing.Tracer;
import apigw.config.etcd.EtcdCheckCommand;
import apigw.config.etcd.EtcdEnv;
import apigw.config.file.FileConfigSource;
import apigw.config.worker.ConfigWorker;
import apigw.config.worker.WorkerStatus;
import apigw.core.pipeline.RequestIds;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import apigw.core.plugin.PluginServices;
import apigw.plugins.BuiltinPlugins;
import apigw.plugins.cache.ResponseCache;
import apigw.plugins.ratelimit.RateLimitStore;
import apigw.plugins.redis.RedisClient;
import apigw.plugins.jwt.OidcKeySources;
import apigw.observability.logging.JsonLogging;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway entry point and constructor wiring.
 *
 * <pre>
 *   gateway                  serve traffic (settings from GW_* environment variables)
 *   gateway check-etcd ...   verify etcd connectivity and the gateway role, then exit
 * </pre>
 */
public final class GatewayMain {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayMain.class);
    /** Reported in the node heartbeat. */
    static final String BUILD_VERSION = "0.8.0-m8";

    private GatewayMain() {
    }

    public static void main(String[] args) {
        long started = System.nanoTime();
        if (args.length > 0 && "check-etcd".equals(args[0])) {
            PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            System.exit(EtcdCheckCommand.run(Arrays.copyOfRange(args, 1, args.length), System.getenv(), out));
        }
        if (args.length > 0) {
            System.err.println("usage: gateway [check-etcd --help]");
            System.exit(2);
        }
        serve(System.getenv(), started);
    }

    private static void serve(Map<String, String> env, long startedNanos) {
        BootstrapConfig config;
        Optional<RedisClient.Settings> redisSettings;
        long cacheBytes;
        long rateLimitKeys;
        TelemetryConfig telemetryConfig;
        try {
            config = BootstrapConfig.fromEnvironment(env);
            telemetryConfig = TelemetryConfig.fromEnvironment(env, gatewayId(env), BUILD_VERSION);
            redisSettings = RedisEnv.settings(env);
            cacheBytes = RedisEnv.number(env, "GW_CACHE_MAX_BYTES", 64L << 20);
            rateLimitKeys = RedisEnv.number(env, "GW_RATELIMIT_MAX_KEYS", 200_000);
        } catch (IllegalArgumentException e) {
            LOG.error("invalid startup configuration: {}", e.getMessage());
            System.exit(2);
            return;
        }
        Vertx vertx = Vertx.vertx(new VertxOptions()
                .setEventLoopPoolSize(config.eventLoops())
                .setPreferNativeTransport(false));
        Readiness readiness = new Readiness();
        SnapshotHolder snapshots = new SnapshotHolder();
        // Long-lived plugin services: the OIDC/JWKS key-set cache fetches on its own context (ADR 0015);
        // rate-limit state and the response cache survive snapshot swaps; Redis is optional (ADR 0018, 0019).
        RedisClient redis = redisSettings.map(r -> new RedisClient(vertx, r)).orElse(null);
        if (redis == null) {
            LOG.info("GW_REDIS_URL not set: global rate limits use their fail mode; cache L2 disabled");
        }
        // Metrics, access log and tracing (ADR 0023). Route meters are compiled into each route.
        MetricRegistry metrics = new MetricRegistry();
        ProcessMetrics.register(metrics, "gateway", BUILD_VERSION);
        OtlpHttpExporter exporter = telemetryConfig.tracing().map(t -> new OtlpHttpExporter(t, metrics)).orElse(null);
        Tracer tracer = exporter == null ? null : new Tracer(telemetryConfig.tracing().orElseThrow(), exporter);
        telemetryConfig.tracing().ifPresentOrElse(
                t -> LOG.info("tracing on endpoint={} sampleRatio={} respectParent={}", t.tracesUri(), t.ratio(),
                        t.respectParent()),
                () -> LOG.info("tracing off (GW_TRACING_ENDPOINT not set); inbound trace context passes through"));
        GatewayTelemetry telemetry = new GatewayTelemetry(metrics, telemetryConfig.accessLog(), tracer);
        ResponseCache cache = new ResponseCache(cacheBytes, redis);
        PluginServices services = new PluginServices(Map.of(
                OidcKeySources.class, new OidcKeySources(vertx, Duration.ofSeconds(5)),
                RateLimitStore.class, new RateLimitStore(rateLimitKeys, redis),
                ResponseCache.class, cache,
                RouteTelemetryFactory.class, telemetry));
        RuntimeGauges.register(metrics, snapshots, readiness, cache, redis);
        SnapshotCompiler compiler = new SnapshotCompiler(BuiltinPlugins.registry(), services, Clock.systemUTC());
        // Process-wide upstream services: SSRF-checked DNS and active health probes (ADR 0015, 0017).
        Upstreams upstreams = Upstreams.create(vertx);
        GatewayServer server = new GatewayServer(vertx, config, readiness, snapshots, upstreams,
                new Observability(metrics, telemetry));
        // After every publish: plugin services and health probes follow the new snapshot.
        Consumer<Snapshot> published = s -> {
            compiler.services().published(s.resources());
            upstreams.health().sync(s);
        };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(vertx, server, config.shutdownGrace(),
                exporter), "gateway-shutdown"));
        server.start()
                .onSuccess(v -> LOG.info("gateway listening http={} https={} status={} eventLoops={} startupMs={}",
                        server.httpPort(), server.httpsPort(), server.statusPort(), config.eventLoops(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)))
                .onSuccess(v -> startConfig(vertx, env, compiler, published, snapshots, readiness, startedNanos,
                        metrics))
                .onFailure(e -> {
                    LOG.error("gateway failed to start: {}", e.toString());
                    vertx.close().onComplete(ignored -> System.exit(1));
                });
    }

    /**
     * Starts the configuration source. M2 supports a local file ({@code GW_CONFIG_FILE}); etcd lands
     * in M3. Without a valid source the gateway serves 503 and stays not-ready.
     */
    private static void startConfig(Vertx vertx, Map<String, String> env, SnapshotCompiler compiler,
            Consumer<Snapshot> published,
            SnapshotHolder snapshots, Readiness readiness, long startedNanos, MetricRegistry metrics) {
        String file = env.get("GW_CONFIG_FILE");
        boolean hasFile = file != null && !file.isBlank();
        if (hasFile && EtcdEnv.configured(env)) {
            readiness.markNotReady("both GW_CONFIG_FILE and GW_ETCD_ENDPOINTS are set");
            LOG.error("configure exactly one source: GW_CONFIG_FILE or GW_ETCD_ENDPOINTS");
            return;
        }
        if (EtcdEnv.configured(env)) {
            startEtcd(vertx, env, compiler, published, snapshots, readiness, startedNanos, metrics);
            return;
        }
        if (!hasFile) {
            readiness.markNotReady("no configuration source (set GW_ETCD_ENDPOINTS or GW_CONFIG_FILE)");
            LOG.error("no configuration source configured; set GW_ETCD_ENDPOINTS or GW_CONFIG_FILE");
            return;
        }
        Duration poll = Duration.ofMillis(pollMillis(env));
        FileConfigSource source = new FileConfigSource(vertx, Path.of(file), compiler, snapshots,
                s -> {
                    published.accept(s);
                    readiness.markReady();
                });
        source.load()
                .onSuccess(s -> LOG.info("ready revision={} readyMs={}", s.revision(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)))
                .onFailure(e -> {
                    readiness.markNotReady("configuration file invalid or unreadable");
                    LOG.error("initial configuration rejected: {}", e.getMessage());
                })
                .onComplete(ignored -> source.watch(poll));
    }

    private static void startEtcd(Vertx vertx, Map<String, String> env, SnapshotCompiler compiler,
            Consumer<Snapshot> published,
            SnapshotHolder snapshots, Readiness readiness, long startedNanos, MetricRegistry metrics) {
        ConfigWorker.Settings settings;
        try {
            long timeoutMs = Long.parseLong(env.getOrDefault("GW_ETCD_TIMEOUT_MS", "5000").trim());
            Optional<Path> lkgDir = Optional.ofNullable(env.get("GW_LKG_DIR")).filter(s -> !s.isBlank())
                    .map(Path::of);
            settings = ConfigWorker.Settings.defaults(EtcdEnv.clientConfig(env, Duration.ofMillis(timeoutMs)),
                    EtcdEnv.prefix(env), lkgDir, gatewayId(env), BUILD_VERSION);
            if (lkgDir.isEmpty()) {
                LOG.warn("GW_LKG_DIR not set: a restart while etcd is unreachable will not serve traffic");
            }
        } catch (IllegalArgumentException e) {
            readiness.markNotReady("invalid etcd settings");
            LOG.error("invalid etcd settings: {}", e.getMessage());
            return;
        }
        AtomicBoolean first = new AtomicBoolean(true);
        ConfigMetrics configMetrics = new ConfigMetrics(metrics);
        ConfigWorker worker = new ConfigWorker(settings, compiler, snapshots, s -> {
            published.accept(s);
            readiness.markReady();
            if (first.compareAndSet(true, false)) {
                LOG.info("ready revision={} readyMs={}", s.revision(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            }
        }, configMetrics);
        configMetrics.bind(worker::status);
        readiness.configStatus(() -> configJson(worker.status(), settings.gatewayId()));
        vertx.deployVerticle(worker, new DeploymentOptions().setThreadingModel(ThreadingModel.WORKER))
                .onFailure(e -> LOG.error("config worker failed to start: {}", e.toString()));
    }

    private static String configJson(WorkerStatus s, String gatewayId) {
        ObjectNode n = new ObjectMapper().createObjectNode()
                .put("source", "etcd")
                .put("gatewayId", gatewayId)
                .put("state", s.state().name())
                .put("appliedRevision", s.appliedRevision())
                .put("etcdRevision", s.etcdRevision())
                .put("snapshotSource", s.source())
                .put("updatedAt", s.updatedAt().toString());
        if (s.configError() != null) {
            n.put("configError", s.configError());
        }
        if (s.etcdError() != null) {
            n.put("etcdError", s.etcdError());
        }
        return n.toString();
    }

    /** {@code GW_GATEWAY_ID}, else the host name (the pod name on Kubernetes), else a random ID. */
    static String gatewayId(Map<String, String> env) {
        String id = env.get("GW_GATEWAY_ID");
        if (id == null || id.isBlank()) {
            id = env.get("HOSTNAME");
        }
        if (id == null || id.isBlank()) {
            id = "gw-" + RequestIds.next();
        }
        return id.trim();
    }

    private static long pollMillis(Map<String, String> env) {
        String v = env.getOrDefault("GW_CONFIG_POLL_MS", "1000");
        try {
            long ms = Long.parseLong(v.trim());
            if (ms >= 50 && ms <= 600_000) {
                return ms;
            }
        } catch (NumberFormatException e) {
            LOG.warn("GW_CONFIG_POLL_MS is not a number, using 1000");
            return 1000;
        }
        LOG.warn("GW_CONFIG_POLL_MS out of range 50..600000, using 1000");
        return 1000;
    }

    /** Runs on the JVM shutdown-hook thread (SIGTERM / SIGINT), never on an event loop. */
    private static void shutdown(Vertx vertx, GatewayServer server, Duration grace, OtlpHttpExporter exporter) {
        LOG.info("shutdown requested, draining for up to {}s", grace.toSeconds());
        long deadlineMs = grace.toMillis() + 5_000;
        try {
            server.stop().toCompletionStage().toCompletableFuture().get(deadlineMs, TimeUnit.MILLISECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            LOG.info("shutdown complete");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("shutdown interrupted");
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("shutdown did not complete cleanly: {}", e.toString());
        } finally {
            if (exporter != null) {
                exporter.close(Duration.ofSeconds(2));
            }
            JsonLogging.flush(Duration.ofSeconds(2));
        }
    }
}
