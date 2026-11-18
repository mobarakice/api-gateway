package apigw.app;

import apigw.config.etcd.EtcdCheckCommand;
import apigw.observability.logging.JsonLogging;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        GatewayServer server = new GatewayServer(vertx, config, readiness);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(vertx, server, config.shutdownGrace()),
                "gateway-shutdown"));
        server.start()
                .onSuccess(v -> LOG.info("gateway listening http={} https={} status={} eventLoops={} startupMs={}",
                        server.httpPort(), server.httpsPort(), server.statusPort(), config.eventLoops(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)))
                .onFailure(e -> {
                    LOG.error("gateway failed to start: {}", e.toString());
                    vertx.close().onComplete(ignored -> System.exit(1));
                });
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
