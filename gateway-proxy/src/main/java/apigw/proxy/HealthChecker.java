package apigw.proxy;

import apigw.core.snapshot.CompiledUpstream;
import apigw.core.snapshot.Snapshot;
import apigw.model.Cidr;
import apigw.model.Upstream;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Active health probes, once per process (not per event loop), for every upstream with
 * {@code healthCheck.active} (ADR 0017). Probes are plain async requests on one context; named
 * targets go through the same SSRF resolver as traffic. Verdicts land in an {@link ActiveHealth} that
 * every event loop reads. Probers are keyed by the upstream's definition key, so an unchanged upstream
 * keeps its state across snapshots; {@link #sync} stops probers no snapshot uses any more.
 */
public final class HealthChecker {

    private static final Logger LOG = LoggerFactory.getLogger(HealthChecker.class);

    private final Vertx vertx;
    private final Context context;
    private final TargetResolver resolver;
    private final Map<String, Prober> probers = new ConcurrentHashMap<>();
    private volatile List<Cidr> allow = List.of();

    public HealthChecker(Vertx vertx, TargetResolver resolver) {
        this.vertx = vertx;
        this.context = vertx.getOrCreateContext();
        this.resolver = resolver;
    }

    /** The shared verdicts for {@code u}, starting its prober if needed; null when it has no active check. */
    public ActiveHealth health(CompiledUpstream u) {
        if (u.healthCheck().active() == null) {
            return null;
        }
        return probers.computeIfAbsent(u.definitionKey(), k -> {
            Prober p = new Prober(u);
            context.runOnContext(v -> p.start());
            return p;
        }).health;
    }

    /** After a publish: start probers for the snapshot's upstreams, stop the rest, adopt its SSRF allow list. */
    public void sync(Snapshot snapshot) {
        allow = snapshot.settings().upstreamAllow();
        snapshot.upstreams().values().forEach(this::health);
        Set<String> live = snapshot.upstreams().values().stream().map(CompiledUpstream::definitionKey)
                .collect(Collectors.toSet());
        probers.entrySet().removeIf(e -> {
            if (!live.contains(e.getKey())) {
                e.getValue().stop();
                return true;
            }
            return false;
        });
    }

    public void close() {
        probers.values().forEach(Prober::stop);
        probers.clear();
    }

    /** Probes all targets of one upstream definition. Runs only on {@link #context}. */
    private final class Prober {

        private final CompiledUpstream def;
        private final Upstream.Active spec;
        private final ActiveHealth health;
        private final HttpClientAgent client;
        private final int[] successes;
        private final int[] failures;
        private final long[] timers;
        private volatile boolean stopped;

        Prober(CompiledUpstream def) {
            this.def = def;
            this.spec = def.healthCheck().active();
            this.health = new ActiveHealth(def.targets().size());
            HttpClientOptions o = UpstreamClients.options(def).setKeepAlive(false);
            this.client = vertx.createHttpClient(o, new PoolOptions().setHttp1MaxSize(1).setHttp2MaxSize(1));
            this.successes = new int[def.targets().size()];
            this.failures = new int[def.targets().size()];
            this.timers = new long[def.targets().size()];
        }

        void start() {
            for (int i = 0; i < def.targets().size(); i++) {
                int target = i;
                // Spread the first probes over one interval so a large upstream does not probe in lockstep.
                long first = 1 + ThreadLocalRandom.current().nextLong(spec.intervalMs());
                timers[i] = vertx.setTimer(first, id -> probe(target));
            }
        }

        void stop() {
            stopped = true;
            context.runOnContext(v -> {
                for (long t : timers) {
                    vertx.cancelTimer(t);
                }
                client.close();
            });
        }

        private void probe(int i) {
            if (stopped) {
                return;
            }
            CompiledUpstream.Target t = def.targets().get(i);
            Future<String> address = t.literal() ? Future.succeededFuture(null)
                    : resolver.resolve(t.host(), allow).map(a -> a.getHostAddress());
            address.compose(ip -> {
                RequestOptions ro = new RequestOptions().setMethod(HttpMethod.GET).setHost(t.host())
                        .setPort(t.port()).setURI(spec.path()).setTimeout(spec.timeoutMs())
                        .setConnectTimeout(spec.timeoutMs()).setFollowRedirects(false)
                        .putHeader("user-agent", "apigw-health");
                if (!spec.host().isEmpty()) {
                    ro.putHeader("host", spec.host());
                }
                if (ip != null) {
                    ro.setServer(SocketAddress.inetSocketAddress(t.port(), ip));
                }
                return client.request(ro).compose(req -> req.send()).compose(resp -> {
                    int status = resp.statusCode();
                    resp.handler(b -> {
                    });
                    return resp.end().map(v -> status);
                });
            }).onComplete(ar -> {
                boolean ok = ar.succeeded() && healthyStatus(ar.result());
                record(i, ok, ar.succeeded() ? "status " + ar.result() : ar.cause().getClass().getSimpleName());
                if (!stopped) {
                    timers[i] = vertx.setTimer(spec.intervalMs(), id -> probe(i));
                }
            });
        }

        private boolean healthyStatus(int status) {
            return spec.statuses().isEmpty() ? status >= 200 && status < 300 : spec.statuses().contains(status);
        }

        private void record(int i, boolean ok, String detail) {
            if (ok) {
                failures[i] = 0;
                if (++successes[i] >= spec.healthyThreshold() && !health.healthy(i)) {
                    health.set(i, true);
                    LOG.info("upstream target healthy upstream={} target={}", def.id(), i);
                }
            } else {
                successes[i] = 0;
                if (++failures[i] >= spec.unhealthyThreshold() && health.healthy(i)) {
                    health.set(i, false);
                    LOG.warn("upstream target unhealthy upstream={} target={} after={} last={}", def.id(), i,
                            failures[i], detail);
                }
            }
        }
    }
}
