package apigw.app;

import apigw.config.etcd.EtcdCheckCommand;
import apigw.config.file.FileConfigSource;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import apigw.plugins.BuiltinPlugins;
import apigw.observability.logging.JsonLogging;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        try {
            config = BootstrapConfig.fromEnvironment(env);
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
        SnapshotCompiler compiler = new SnapshotCompiler(BuiltinPlugins.registry());
        GatewayServer server = new GatewayServer(vertx, config, readiness, snapshots);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(vertx, server, config.shutdownGrace()),
                "gateway-shutdown"));
        server.start()
                .onSuccess(v -> LOG.info("gateway listening http={} https={} status={} eventLoops={} startupMs={}",
                        server.httpPort(), server.httpsPort(), server.statusPort(), config.eventLoops(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)))
                .onSuccess(v -> startConfig(vertx, env, compiler, snapshots, readiness, startedNanos))
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
            SnapshotHolder snapshots, Readiness readiness, long startedNanos) {
        String file = env.get("GW_CONFIG_FILE");
        if (file == null || file.isBlank()) {
            readiness.markNotReady("no configuration source (set GW_CONFIG_FILE)");
            LOG.error("no configuration source configured; set GW_CONFIG_FILE");
            return;
        }
        Duration poll = Duration.ofMillis(pollMillis(env));
        FileConfigSource source = new FileConfigSource(vertx, Path.of(file), compiler, snapshots,
                s -> readiness.markReady());
        source.load()
                .onSuccess(s -> LOG.info("ready revision={} readyMs={}", s.revision(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)))
                .onFailure(e -> {
                    readiness.markNotReady("configuration file invalid or unreadable");
                    LOG.error("initial configuration rejected: {}", e.getMessage());
                })
                .onComplete(ignored -> source.watch(poll));
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
    private static void shutdown(Vertx vertx, GatewayServer server, Duration grace) {
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
            JsonLogging.flush(Duration.ofSeconds(2));
        }
    }
}
