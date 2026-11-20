package apigw.config.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import apigw.config.etcd.KeyValue;
import apigw.core.plugin.PluginRegistry;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.model.ConfigError;
import apigw.model.ConfigException;
import apigw.model.GatewayConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EtcdSnapshotBuilderTest {

    private static final String P = "/gw/t/";

    static KeyValue kv(String rel, String value) {
        return new KeyValue(P + rel, value.getBytes(StandardCharsets.UTF_8), 1, 1, 1, 0);
    }

    static String env(String kind, String id, String spec) {
        return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"schemaVersion\":1,\"updatedAt\":\"x\","
                + "\"updatedBy\":\"t\",\"spec\":" + spec + "}";
    }

    static List<KeyValue> valid() {
        return new ArrayList<>(List.of(
                kv("meta/schema-version", "1"),
                kv("meta/settings", env("Settings", "settings", "{\"viaName\":\"edge\"}")),
                kv("upstreams/u", env("Upstream", "u", "{\"targets\":[{\"host\":\"127.0.0.1\",\"port\":9}]}")),
                kv("services/s", env("Service", "s", "{\"upstreamId\":\"u\"}")),
                kv("routes/r", env("Route", "r", "{\"path\":\"/r\",\"serviceId\":\"s\"}")),
                kv("global-plugins/headers", env("GlobalPlugin", "headers", "{\"config\":{}}")),
                kv("nodes/gw-1", "not an envelope; ignored"),
                kv("changesets/c1", "ignored")));
    }

    static List<String> paths(ConfigException e) {
        return e.errors().stream().map(ConfigError::path).toList();
    }

    @Test
    void buildsFromEnvelopesAndIgnoresNonConfigKeys() throws Exception {
        GatewayConfig c = EtcdSnapshotBuilder.build(P, valid(), 42);
        assertThat(c.revision()).isEqualTo(42);
        assertThat(c.schemaVersion()).isEqualTo(1);
        assertThat(c.settings().viaName()).isEqualTo("edge");
        assertThat(c.routes()).singleElement().satisfies(r -> assertThat(r.id()).isEqualTo("r"));
        assertThat(c.services()).hasSize(1);
        assertThat(c.upstreams()).hasSize(1);
        assertThat(c.globalPlugins()).singleElement().satisfies(p -> assertThat(p.name()).isEqualTo("headers"));
    }

    @Test
    void requiresSchemaVersion() {
        List<KeyValue> kvs = valid();
        kvs.removeIf(k -> k.key().endsWith("schema-version"));
        assertThatThrownBy(() -> EtcdSnapshotBuilder.build(P, kvs, 1)).isInstanceOfSatisfying(ConfigException.class,
                e -> assertThat(paths(e)).containsExactly("meta/schema-version"));
    }

    @Test
    void rejectsEnvelopeMismatchesWithKeyPaths() {
        List<KeyValue> kvs = valid();
        kvs.add(kv("routes/x", env("Service", "y", "{\"path\":\"/x\",\"serviceId\":\"s\"}")));
        kvs.add(kv("routes/dup", env("Route", "dup", "{\"id\":\"dup\",\"path\":\"/d\",\"serviceId\":\"s\"}")));
        kvs.add(kv("services/bad", "{nope"));
        kvs.add(kv("upstreams/v2", "{\"id\":\"v2\",\"kind\":\"Upstream\",\"schemaVersion\":9,\"spec\":{}}"));
        kvs.add(kv("services/extra", "{\"id\":\"extra\",\"kind\":\"Service\",\"schemaVersion\":1,\"spec\":{},"
                + "\"surprise\":1}"));
        kvs.add(kv("consumers/c", "[]"));
        assertThatThrownBy(() -> EtcdSnapshotBuilder.build(P, kvs, 1)).isInstanceOfSatisfying(ConfigException.class,
                e -> assertThat(paths(e)).contains("routes/x.kind", "routes/x.id", "routes/dup.spec.id",
                        "services/bad", "upstreams/v2.schemaVersion", "services/extra.surprise", "consumers/c"));
    }

    @Test
    void compileErrorsPointAtEtcdKeys() throws Exception {
        List<KeyValue> kvs = valid();
        kvs.add(kv("routes/orphan", env("Route", "orphan", "{\"path\":\"/o\",\"serviceId\":\"missing\"}")));
        GatewayConfig c = EtcdSnapshotBuilder.build(P, kvs, 1);
        SnapshotCompiler compiler = new SnapshotCompiler(new PluginRegistry(List.of()));
        assertThatThrownBy(() -> compiler.compile(c)).isInstanceOfSatisfying(ConfigException.class, e -> {
            List<ConfigError> mapped = EtcdSnapshotBuilder.toKeyPaths(e.errors(), c);
            assertThat(mapped).extracting(ConfigError::path).anyMatch(p -> p.startsWith("routes/orphan."));
        });
    }

    static String tenantEnv(String kind, String id, String tenant, String spec) {
        return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"schemaVersion\":1,\"tenantId\":\"" + tenant
                + "\",\"updatedAt\":\"x\",\"updatedBy\":\"t\",\"spec\":" + spec + "}";
    }

    @Test
    void buildsSecuritySubtreesWithTenantsAndKeyDerivedIds() throws Exception {
        List<KeyValue> kvs = valid();
        kvs.add(kv("consumers/c1", tenantEnv("Consumer", "c1", "a", "{\"username\":\"alice\",\"roles\":[\"r\"]}")));
        kvs.add(kv("credentials/c1/k1", tenantEnv("Credential", "k1", "a", "{\"type\":\"api-key\","
                + "\"keyId\":\"key_00001\",\"hash\":\"sha256$x$y\"}")));
        kvs.add(kv("plugin-configs/pc", env("PluginConfig", "pc", "{\"plugin\":\"headers\",\"config\":{}}")));
        kvs.add(kv("policies/authz/p", env("AuthzPolicy", "p", "{\"effect\":\"allow\"}")));
        kvs.add(kv("policies/revoked-tokens/t", env("RevokedToken", "t",
                "{\"jti\":\"j\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}")));
        GatewayConfig c = EtcdSnapshotBuilder.build(P, kvs, 7);
        assertThat(c.consumers()).singleElement().satisfies(k -> {
            assertThat(k.id()).isEqualTo("c1");
            assertThat(k.tenantId()).isEqualTo("a");
        });
        assertThat(c.credentials()).singleElement().satisfies(k -> {
            assertThat(k.consumerId()).isEqualTo("c1");
            assertThat(k.id()).isEqualTo("k1");
            assertThat(k.tenantId()).isEqualTo("a");
        });
        assertThat(c.pluginConfigs()).hasSize(1);
        assertThat(c.authzPolicies()).hasSize(1);
        assertThat(c.revokedTokens()).singleElement().satisfies(t -> assertThat(t.jti()).isEqualTo("j"));
    }

    @Test
    void rejectsUnknownPolicyTypesAndTenantInSpec() {
        List<KeyValue> kvs = valid();
        kvs.add(kv("policies/rate/x", env("RatePolicy", "x", "{}")));
        kvs.add(kv("consumers/c2", tenantEnv("Consumer", "c2", "a", "{\"tenantId\":\"b\"}")));
        assertThatThrownBy(() -> EtcdSnapshotBuilder.build(P, kvs, 1)).isInstanceOfSatisfying(ConfigException.class,
                e -> assertThat(paths(e)).contains("policies/rate/x", "consumers/c2.spec.tenantId"));
    }

    @Test
    void configKeyFilter() {
        assertThat(EtcdSnapshotBuilder.isConfigKey(P, P + "routes/a")).isTrue();
        assertThat(EtcdSnapshotBuilder.isConfigKey(P, P + "meta/schema-version")).isTrue();
        assertThat(EtcdSnapshotBuilder.isConfigKey(P, P + "nodes/gw")).isFalse();
        assertThat(EtcdSnapshotBuilder.isConfigKey(P, P + "history/1")).isFalse();
        assertThat(EtcdSnapshotBuilder.isConfigKey(P, "/gw/other/routes/a")).isFalse();
    }
}
