package apigw.app;

import apigw.core.snapshot.SnapshotHolder;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the listeners. Startup: status listener, then one traffic verticle per event loop. Shutdown: fail readiness,
 * stop accepting, drain in-flight requests up to the grace period, close.
 */
public final class GatewayServer {

    private final Vertx vertx;
    private final BootstrapConfig config;
    private final Readiness readiness;
    private final SnapshotHolder snapshots;
    private final ListenerPorts ports = new ListenerPorts();
    private final List<String> deployments = new ArrayList<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private HttpServer statusServer;
    private final Upstreams upstreams;
    private final Observability observability;
    private final Connections connections;

    GatewayServer(Vertx vertx, BootstrapConfig config, Readiness readiness, SnapshotHolder snapshots) {
        this(vertx, config, readiness, snapshots, Upstreams.create(vertx));
    }

    GatewayServer(Vertx vertx, BootstrapConfig config, Readiness readiness, SnapshotHolder snapshots,
            Upstreams upstreams) {
        this(vertx, config, readiness, snapshots, upstreams, Observability.none());
    }

    GatewayServer(Vertx vertx, BootstrapConfig config, Readiness readiness, SnapshotHolder snapshots,
            Upstreams upstreams, Observability observability) {
        this.observability = Objects.requireNonNull(observability);
        this.connections = new Connections(observability.metrics());
        UpstreamGauges.register(observability.metrics(), upstreams);
        this.upstreams = Objects.requireNonNull(upstreams);
        this.vertx = Objects.requireNonNull(vertx);
        this.config = Objects.requireNonNull(config);
        this.readiness = Objects.requireNonNull(readiness);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    /** Binds all listeners. Completes when every event loop is accepting. */
    public Future<Void> start() {
        statusServer = vertx.createHttpServer(new HttpServerOptions()
                .setHost(config.bindHost())
                .setPort(config.statusPort()))
                .requestHandler(new StatusHandler(readiness, observability.metrics(), vertx));
        return statusServer.listen()
                .onSuccess(s -> ports.status(s.actualPort()))
                .compose(s -> {
                    // Vert.x shares one listening socket between instances that request the same
                    // concrete host:port. It does not share port 0, so resolve it first (tests/dev only).
                    BootstrapConfig resolved = new BootstrapConfig(config.bindHost(),
                            concrete(config.httpPort()), concrete(config.httpsPort()), s.actualPort(),
                            config.eventLoops(), config.tls(), config.shutdownGrace());
                    return deploy(resolved, config.eventLoops());
                })
                .mapEmpty();
    }

    private int concrete(int port) {
        if (port != 0) {
            return port;
        }
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName(config.bindHost()))) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot resolve a free port", e);
        }
    }

    private Future<String> deploy(BootstrapConfig cfg, int instances) {
        DeploymentOptions options = new DeploymentOptions()
                .setInstances(instances)
                .setThreadingModel(ThreadingModel.EVENT_LOOP);
        return vertx.deployVerticle(() -> new TrafficVerticle(cfg, ports, snapshots, upstreams, observability,
                connections), options)
                .onSuccess(id -> {
                    synchronized (deployments) {
                        deployments.add(id);
                    }
                });
    }

    /** Graceful stop; idempotent. The status listener closes last so probes see NOT_READY first. */
    public Future<Void> stop() {
        if (!stopping.compareAndSet(false, true)) {
            return Future.succeededFuture();
        }
        readiness.markShuttingDown();
        List<Future<Void>> undeploying = new ArrayList<>();
        synchronized (deployments) {
            for (String id : deployments) {
                undeploying.add(vertx.undeploy(id));
            }
        }
        return Future.join(undeploying)
                .transform(ignored -> statusServer == null ? Future.succeededFuture() : statusServer.close());
    }

    public int httpPort() {
        return ports.http();
    }

    /** Bound TLS port, or -1 when TLS is not configured. */
    public int httpsPort() {
        return ports.https();
    }

    public int statusPort() {
        return ports.status();
    }
}
