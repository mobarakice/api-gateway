package apigw.app;

import apigw.proxy.HealthChecker;
import apigw.proxy.HttpForwarder;
import apigw.proxy.TargetResolver;
import io.vertx.core.Vertx;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Upstream services shared by every event loop: the SSRF-checked resolver, the active health checker,
 * and the live per-loop forwarders (for upstream gauges).
 */
record Upstreams(TargetResolver resolver, HealthChecker health, Set<HttpForwarder> forwarders) {

    static Upstreams create(Vertx vertx) {
        TargetResolver resolver = new TargetResolver(vertx);
        return new Upstreams(resolver, new HealthChecker(vertx, resolver), new CopyOnWriteArraySet<>());
    }
}
