package apigw.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConfigDecoderTest {

    static final String VALID = """
            {
              "schemaVersion": 1,
              "revision": 7,
              "settings": {"trustedProxies": ["10.0.0.0/8"], "viaName": "edge"},
              "upstreams": [{"id": "u1", "targets": [{"host": "127.0.0.1", "port": 9000, "weight": 2}],
                             "balancer": "weighted-round-robin", "pool": {"maxConnections": 8}}],
              "services": [{"id": "s1", "upstreamId": "u1", "timeouts": {"connectMs": 500}}],
              "routes": [{"id": "r1", "path": "/users/{id}", "match": "template", "methods": ["GET"],
                          "hosts": ["api.example.com"], "serviceId": "s1", "priority": 5,
                          "plugins": [{"name": "headers", "config": {"request": {"set": {"x-a": "1"}}}}]}],
              "globalPlugins": []
            }""";

    static GatewayConfig decode(String json) throws ConfigException {
        return ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void decodesAValidDocument() throws Exception {
        GatewayConfig c = decode(VALID);
        ConfigValidator.validate(c);
        assertThat(c.revision()).isEqualTo(7);
        assertThat(c.settings().viaName()).isEqualTo("edge");
        assertThat(c.settings().stripRequestHeaders()).isEqualTo(Settings.DEFAULT_STRIPPED);
        Route r = c.routes().getFirst();
        assertThat(r.match()).isEqualTo(PathMatch.TEMPLATE);
        assertThat(r.plugins().getFirst().config().path("request").path("set").path("x-a").asText()).isEqualTo("1");
        Upstream u = c.upstreams().getFirst();
        assertThat(u.balancer()).isEqualTo(Upstream.Balancer.WEIGHTED_ROUND_ROBIN);
        assertThat(u.pool().maxConnections()).isEqualTo(8);
        assertThat(u.pool().maxPending()).isEqualTo(Upstream.Pool.DEFAULTS.maxPending());
        assertThat(c.services().getFirst().timeouts().orElse(Timeouts.DEFAULTS))
                .isEqualTo(new Timeouts(500, 30_000, 60_000, 0));
    }

    @Test
    void reportsEveryStructuralErrorWithItsPath() {
        assertThatThrownBy(() -> decode("""
                {"schemaVersion": "one",
                 "routes": [{"id": "r1", "path": "/a", "serviceId": "s", "prority": 3, "match": "regex"}],
                 "upstreams": [{"id": "u", "targets": [{"host": "h", "port": 1.5}]}]}"""))
                .isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.errors()).extracting(Object::toString)
                        .contains("schemaVersion: must be a number",
                                "routes[0].prority: unknown field",
                                "routes[0].match: must be one of: exact, prefix, template",
                                "upstreams[0].targets[0].port: must be an integer"));
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> decode("{not json")).isInstanceOf(ConfigException.class)
                .hasMessageContaining("not valid JSON");
    }
}
