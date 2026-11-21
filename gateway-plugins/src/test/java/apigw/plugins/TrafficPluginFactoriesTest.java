package apigw.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.PluginServices;
import apigw.model.ConfigError;
import apigw.plugins.cache.CachePluginFactory;
import apigw.plugins.cache.ResponseCache;
import apigw.plugins.cors.CorsPluginFactory;
import apigw.plugins.ip.IpRestrictionPluginFactory;
import apigw.plugins.ratelimit.RateLimitPluginFactory;
import apigw.plugins.ratelimit.RateLimitStore;
import apigw.plugins.size.RequestSizeLimitPluginFactory;
import apigw.plugins.transform.RequestTransformPluginFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Config validation of the M5 traffic plugins (paths relative to the plugin config). */
class TrafficPluginFactoriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PluginContext CTX = new PluginContext(Map.of(), new PluginServices(Map.of(
            RateLimitStore.class, new RateLimitStore(100, null),
            ResponseCache.class, new ResponseCache(1 << 20, null))),
            Clock.systemUTC());

    private static List<String> errors(PluginFactory f, String config) {
        try {
            f.create(JSON.readTree(config), CTX);
            return List.of();
        } catch (PluginConfigException e) {
            return e.errors().stream().map(ConfigError::toString).toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyBuiltinShipsItsSchema() {
        for (PluginFactory f : BuiltinPlugins.registry().all()) {
            assertThat(getClass().getClassLoader().getResource(f.schemaResource())).as(f.name()).isNotNull();
        }
    }

    @Test
    void ipRestriction() {
        assertThat(errors(new IpRestrictionPluginFactory(), "{\"allow\":[\"10.0.0.0/8\",\"::1\"]}")).isEmpty();
        assertThat(errors(new IpRestrictionPluginFactory(), "{}")).containsExactly(": configure allow and/or deny");
        assertThat(errors(new IpRestrictionPluginFactory(), "{\"deny\":[\"10.0.0.0/33\",\"nope\"],\"x\":1}"))
                .contains("deny[0]: not an IP address or CIDR", "deny[1]: not an IP address or CIDR",
                        "x: unknown field");
    }

    @Test
    void cors() {
        assertThat(errors(new CorsPluginFactory(), "{\"allowOrigins\":[\"*\"],\"allowCredentials\":true}"))
                .anyMatch(e -> e.startsWith("allowCredentials"));
        assertThat(errors(new CorsPluginFactory(), "{\"allowOrigins\":[\"https://A.test\",\"ftp://x\","
                + "\"https://x.test/path\"]}")).hasSize(3);
        assertThat(errors(new CorsPluginFactory(), "{\"allowOrigins\":[\"https://*.a.test:8443\"],"
                + "\"allowMethods\":[\"BREW\"]}")).containsExactly("allowMethods[0]: unknown method");
        assertThat(errors(new CorsPluginFactory(), "{}")).anyMatch(e -> e.startsWith("allowOrigins"));
    }

    @Test
    void sizeAndTransform() {
        assertThat(errors(new RequestSizeLimitPluginFactory(), "{}")).containsExactly("maxBytes: is required");
        assertThat(errors(new RequestTransformPluginFactory(), "{}")).hasSize(1);
        assertThat(errors(new RequestTransformPluginFactory(), "{\"path\":{\"template\":\"/a/{id\"}}"))
                .anyMatch(e -> e.contains("unclosed"));
        assertThat(errors(new RequestTransformPluginFactory(),
                "{\"path\":{\"template\":\"/a\",\"stripPrefix\":\"/b\"}}"))
                .anyMatch(e -> e.contains("either template"));
        assertThat(errors(new RequestTransformPluginFactory(), "{\"path\":{\"addPrefix\":\"/a/../etc\"}}")).hasSize(1);
        assertThat(errors(new RequestTransformPluginFactory(), "{\"headers\":{\"rename\":{\"x-a\":\"x-tenant-id\"}}}"))
                .hasSize(1);
        assertThat(errors(new RequestTransformPluginFactory(), "{\"query\":{\"set\":{\"a b\":\"x\"}}}")).hasSize(1);
    }

    @Test
    void rateLimitAndCache() {
        assertThat(errors(new RateLimitPluginFactory(), "{\"limit\":5}")).isEmpty();
        assertThat(errors(new RateLimitPluginFactory(), "{}")).contains("limit: is required");
        assertThat(errors(new RateLimitPluginFactory(), "{\"limit\":5,\"algorithm\":\"leaky\",\"key\":[\"cookie\"],"
                + "\"mode\":\"local\",\"global\":{}}")).hasSize(3);
        assertThat(errors(new RateLimitPluginFactory(), "{\"limit\":5,\"algorithm\":\"fixed-window\",\"burst\":9}"))
                .containsExactly("burst: applies to token-bucket only");
        assertThat(errors(new RateLimitPluginFactory(), "{\"limit\":5,\"mode\":\"global\",\"global\":{\"prefetch\":9,"
                + "\"failMode\":\"maybe\"}}")).hasSize(2);
        assertThat(errors(new CachePluginFactory(), "{\"statuses\":[500],\"query\":\"some\"}")).hasSize(2);
        assertThat(errors(new CachePluginFactory(), "{\"vary\":[\"accept\"],\"query\":[\"page\"],\"l2\":true}"))
                .isEmpty();
        // Without the long-lived services the factories refuse rather than build a stateless limiter.
        try {
            new RateLimitPluginFactory().create(JSON.readTree("{\"limit\":1}"), PluginContext.empty());
            throw new AssertionError("expected PluginConfigException");
        } catch (PluginConfigException expected) {
            assertThat(expected.errors().getFirst().message()).contains("store");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
