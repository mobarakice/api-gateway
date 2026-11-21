package apigw.app;

import apigw.proxy.HealthChecker;
import apigw.proxy.TargetResolver;
import io.vertx.core.Vertx;

/** Upstream services shared by every event loop: the SSRF-checked resolver and the active health checker. */
record Upstreams(TargetResolver resolver, HealthChecker health) {

    static Upstreams create(Vertx vertx) {
        TargetResolver resolver = new TargetResolver(vertx);
        return new Upstreams(resolver, new HealthChecker(vertx, resolver));
    }
}
