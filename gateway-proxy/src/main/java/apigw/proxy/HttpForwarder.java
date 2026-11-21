package apigw.proxy;

import apigw.core.pipeline.Forwarder;
import apigw.core.pipeline.ProxyExchange;
import apigw.core.snapshot.CompiledUpstream;
import apigw.core.snapshot.Snapshot;
import io.vertx.core.Vertx;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Streams admitted requests to upstream targets. One instance per event loop, used only from that
 * loop: pools and balancer state are loop-local, so the request path takes no locks.
 *
 * <p>Pools are keyed by upstream ID and survive snapshot swaps while the upstream's connection
 * definition is unchanged. When a newer snapshot changes or removes an upstream, its old pool is
 * retired gracefully (in-flight requests complete).
 */
public final class HttpForwarder implements Forwarder {

    private final Vertx vertx;
    private final TargetResolver resolver;
    private final HealthChecker health;
    private final Map<String, RetryBudget> budgets = new HashMap<>();
    private final Map<String, UpstreamState> states = new HashMap<>();
    private long generation;

    public HttpForwarder(Vertx vertx) {
        this(vertx, new TargetResolver(vertx));
    }

    public HttpForwarder(Vertx vertx, TargetResolver resolver) {
        this(vertx, resolver, null);
    }

    /**
     * @param health process-wide active health checker shared by all event loops; null = active checks
     *               are not run (passive ejection and breakers still work)
     */
    public HttpForwarder(Vertx vertx, TargetResolver resolver, HealthChecker health) {
        this.vertx = vertx;
        this.resolver = resolver;
        this.health = health;
    }

    @Override
    public void forward(ProxyExchange exchange) {
        Snapshot snapshot = exchange.snapshot();
        if (snapshot.generation() > generation) {
            reconcile(snapshot);
        }
        CompiledUpstream def = exchange.route().upstream();
        UpstreamState state = states.get(def.id());
        if (state == null || (state.definition != def
                && !state.definition.definitionKey().equals(def.definitionKey())
                && snapshot.generation() == generation)) {
            if (state != null) {
                state.retire();
            }
            state = new UpstreamState(vertx, def, health);
            states.put(def.id(), state);
        }
        RetryBudget budget = exchange.route().retries().attempts() == 0 ? null
                : budgets.computeIfAbsent(exchange.route().id(), k -> new RetryBudget());
        new ProxyCall(vertx, resolver, state, exchange, budget).start();
    }

    private void reconcile(Snapshot snapshot) {
        generation = snapshot.generation();
        // Budgets are short-lived windows; dropping them on a config change bounds the map to live routes.
        budgets.clear();
        Iterator<Map.Entry<String, UpstreamState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, UpstreamState> e = it.next();
            CompiledUpstream now = snapshot.upstreams().get(e.getKey());
            if (now == null || !now.definitionKey().equals(e.getValue().definition.definitionKey())) {
                e.getValue().retire();
                it.remove();
            }
        }
    }

    /** Retires every pool (verticle stop). */
    public void close() {
        states.values().forEach(UpstreamState::retire);
        states.clear();
    }
}
