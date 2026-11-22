package apigw.proxy;

import apigw.core.pipeline.ProxyExchange;
import apigw.core.snapshot.CompiledUpstream;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.PoolOptions;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One upstream definition on one event loop: its HTTP client (one pool per target), balancer,
 * counters, and, when configured, its circuit breaker and target gate (ADR 0017). Loop-confined.
 */
final class UpstreamState implements Balancer.Availability {

    private static final Logger LOG = LoggerFactory.getLogger(UpstreamState.class);
    private static final long PANIC_LOG_INTERVAL_MS = 10_000;

    final CompiledUpstream definition;
    final HttpClientAgent client;
    final Balancer balancer;
    final int[] inflight;
    /** Null when the upstream has no circuit breaker. */
    final CircuitBreaker breaker;
    /** The breaker is open or half-open; readable from the metrics scrape thread. */
    final BreakerFlag breakerOpen = new BreakerFlag();
    /** Null when the upstream has neither active nor passive health checks. */
    final TargetGate gate;
    private long pickNowMs;
    private int pickExclude = -1;
    private long lastPanicLogMs;

    UpstreamState(Vertx vertx, CompiledUpstream definition, HealthChecker health) {
        this.definition = definition;
        this.balancer = Balancer.of(definition);
        this.inflight = new int[definition.targets().size()];
        this.breaker = definition.circuitBreaker() == null ? null
                : new CircuitBreaker(definition.id(), definition.circuitBreaker(), breakerOpen::set);
        this.gate = definition.gated() ? new TargetGate(definition.id(), definition.targets().size(),
                health == null ? null : health.health(definition), definition.healthCheck().passive()) : null;
        PoolOptions pool = new PoolOptions()
                .setHttp1MaxSize(definition.pool().maxConnections())
                .setHttp2MaxSize(definition.pool().maxConnections())
                .setMaxWaitQueueSize(definition.pool().maxPending());
        this.client = vertx.createHttpClient(UpstreamClients.options(definition), pool);
    }

    /**
     * Chooses a target, avoiding unavailable ones and (on a retry) {@code exclude} when another option
     * exists. If no target is available the upstream is in panic: balance over all targets rather than
     * fail everything on a health signal that may itself be wrong.
     */
    int pick(ProxyExchange exchange, int exclude, long nowMs) {
        if (gate == null && exclude < 0) {
            return balancer.pick(exchange, inflight, Balancer.Availability.ALL);
        }
        pickNowMs = nowMs;
        pickExclude = exclude;
        int t = balancer.pick(exchange, inflight, this);
        if (t < 0 && exclude >= 0) {
            pickExclude = -1;
            t = balancer.pick(exchange, inflight, this);
        }
        pickExclude = -1;
        if (t < 0) {
            if (nowMs - lastPanicLogMs >= PANIC_LOG_INTERVAL_MS) {
                lastPanicLogMs = nowMs;
                LOG.warn("no available target, balancing over all (panic) upstream={}", definition.id());
            }
            t = balancer.pick(exchange, inflight, Balancer.Availability.ALL);
        }
        return t;
    }

    @Override
    public boolean ok(int target) {
        return target != pickExclude && (gate == null || gate.available(target, pickNowMs));
    }

    /** Lets in-flight requests on this pool finish (up to the grace), then closes its connections. */
    void retire() {
        client.shutdown(30, TimeUnit.SECONDS);
    }
}
