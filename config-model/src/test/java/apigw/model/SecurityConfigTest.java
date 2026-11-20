package apigw.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Consumers, credentials, policies, tenants and SSRF rules in the config model (M4). */
class SecurityConfigTest {

    private static final String HASH = ApiKeyHash.hash(ApiKeyHash.newSecret());

    private static GatewayConfig decode(String json) throws ConfigException {
        return ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> errors(String json) {
        try {
            ConfigValidator.validate(decode(json));
            return List.of();
        } catch (ConfigException e) {
            return e.errors().stream().map(Object::toString).toList();
        }
    }

    private static String doc(String body) {
        return "{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]},"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":80}]},"
                + "{\"id\":\"ua\",\"tenantId\":\"a\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":80}]},"
                + "{\"id\":\"ub\",\"tenantId\":\"b\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":80}]}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"},"
                + "{\"id\":\"sa\",\"tenantId\":\"a\",\"upstreamId\":\"ua\"}],"
                + "\"routes\":[{\"id\":\"r\",\"path\":\"/\",\"serviceId\":\"s\"}]" + body + "}";
    }

    @Test
    void decodesSecurityObjects() throws Exception {
        GatewayConfig c = decode(doc("""
                ,                "consumers": [{"id": "c1", "tenantId": "a", "username": "alice", "roles": ["admin"],
                               "attributes": {"plan": "gold"}}],
                "credentials": [{"id": "k1", "consumerId": "c1", "tenantId": "a", "type": "api-key",
                                 "keyId": "key_00001", "hash": "%s"}],
                "pluginConfigs": [{"id": "pc", "plugin": "headers", "config": {"request": {"set": {"a": "1"}}}}],
                "authzPolicies": [{"id": "p", "effect": "deny", "routes": ["r"], "methods": ["DELETE"],
                                   "subjects": {"roles": ["guest"]},
                                   "condition": {"any": [{"op": "in", "attr": "request.header.x-env",
                                                          "values": ["prod", "stage"]},
                                                         {"not": {"op": "eq", "attr": "principal.tenant",
                                                                  "ref": "route.tenant"}}]}}],
                "revokedTokens": [{"id": "t", "jti": "j-1", "issuer": "https://idp", "expiresAt": "2099-01-01T00:00:00Z"}]
                """.replace('\n', ' ').formatted(HASH)));
        assertThat(c.consumers().getFirst().roles()).containsExactly("admin");
        assertThat(c.credentials().getFirst().type()).isEqualTo(Credential.Type.API_KEY);
        AuthzPolicy p = c.authzPolicies().getFirst();
        assertThat(p.effect()).isEqualTo(AuthzPolicy.Effect.DENY);
        assertThat(p.condition()).isInstanceOf(Condition.Any.class);
        assertThat(c.revokedTokens().getFirst().jti()).isEqualTo("j-1");
    }

    @Test
    void crossTenantReferencesAreRejected() {
        assertThat(errors(doc("""
                ,                "consumers": [{"id": "cb", "tenantId": "b"}],
                "credentials": [{"id": "k", "consumerId": "cb", "tenantId": "a", "type": "api-key",
                                 "keyId": "key_00001", "hash": "%s"}]""".replace('\n', ' ').formatted(HASH))))
                .contains("credentials[0].tenantId: must equal the consumer's tenant");
        // Tenant b service on tenant a's upstream; a shared service on a tenant's upstream.
        String crossService = doc("").replace("{\"id\":\"sa\",\"tenantId\":\"a\",\"upstreamId\":\"ua\"}",
                "{\"id\":\"sa\",\"tenantId\":\"b\",\"upstreamId\":\"ua\"},{\"id\":\"sx\",\"upstreamId\":\"ub\"}");
        assertThat(errors(crossService)).contains("services[1].upstreamId: references a upstream of another tenant",
                "services[2].upstreamId: references a upstream of another tenant");
        String crossRoute = doc("").replace("{\"id\":\"r\",\"path\":\"/\",\"serviceId\":\"s\"}",
                "{\"id\":\"r\",\"path\":\"/\",\"tenantId\":\"b\",\"serviceId\":\"sa\"}");
        assertThat(errors(crossRoute)).contains("routes[0].serviceId: references a service of another tenant");
    }

    @Test
    void credentialRulesAndReferences() {
        assertThat(errors(doc("""
                ,                "consumers": [{"id": "c1"}, {"id": "c2"}],
                "credentials": [{"id": "k", "consumerId": "c1", "type": "api-key",
                                 "keyId": "key_00001", "hash": "%1$s"},
                                {"id": "k", "consumerId": "c1", "type": "api-key",
                                 "keyId": "key_00002", "hash": "%1$s"},
                                {"id": "k", "consumerId": "c2", "type": "api-key",
                                 "keyId": "key_00001", "hash": "%1$s"},
                                {"id": "x", "consumerId": "ghost", "type": "api-key",
                                 "keyId": "short", "hash": "plain"}],
                "authzPolicies": [{"id": "p", "effect": "allow", "routes": ["nope"], "methods": ["BREW"],
                                   "subjects": {"consumers": ["ghost"]}}]""".replace('\n', ' ').formatted(HASH))))
                .contains("credentials[1].id: duplicate credential 'k' for consumer 'c1'",
                        "credentials[2].keyId: duplicate keyId (key IDs are global)",
                        "credentials[3].consumerId: references unknown consumer 'ghost'",
                        "credentials[3].keyId: must match [A-Za-z0-9_-]{8,64}",
                        "authzPolicies[0].routes[0]: references unknown route 'nope'",
                        "authzPolicies[0].methods[0]: unknown method 'BREW'",
                        "authzPolicies[0].subjects.consumers[0]: references unknown consumer 'ghost'")
                .anyMatch(e -> e.startsWith("credentials[3].hash:"));
    }

    @Test
    void pluginConfigRefsMustExistAndMatchThePlugin() {
        String body = """
                ,"pluginConfigs": [{"id": "pc", "plugin": "headers", "config": {}}],
                 "globalPlugins": [{"name": "jwt", "configRef": "pc"}, {"name": "headers", "configRef": "missing"}]""";
        assertThat(errors(doc(body))).contains(
                "globalPlugins[0].configRef: plugin config 'pc' is for plugin 'headers', not 'jwt'",
                "globalPlugins[1].configRef: references unknown plugin config 'missing'");
        assertThat(errors(doc(",\"globalPlugins\": [{\"name\": \"headers\", \"configRef\": \"pc\", \"config\": {}}],"
                + "\"pluginConfigs\": [{\"id\": \"pc\", \"plugin\": \"headers\", \"config\": {}}]")))
                .anyMatch(e -> e.contains("configRef"));
    }

    @Test
    void conditionsAreBoundedAndAttributesChecked() {
        String deep = "{\"op\":\"exists\",\"attr\":\"principal.subject\"}";
        for (int i = 0; i < 20; i++) {
            deep = "{\"not\":" + deep + "}";
        }
        assertThat(errors(doc(",\"authzPolicies\":[{\"id\":\"p\",\"effect\":\"allow\",\"condition\":" + deep + "}]")))
                .anyMatch(e -> e.contains("nested"));
        assertThat(errors(doc(",\"authzPolicies\":[{\"id\":\"p\",\"effect\":\"allow\",\"condition\":"
                + "{\"op\":\"eq\",\"attr\":\"request.body\",\"value\":\"x\"}}]"))).anyMatch(e -> e.contains("attr"));
    }

    @Test
    void literalUpstreamsInDeniedRangesNeedAnExplicitAllow() {
        String metadata = doc("").replace("\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]", "\"upstreamAllowCidrs\":[]");
        assertThat(errors(metadata)).anyMatch(e -> e.startsWith("upstreams[0].targets[0].host: address in a denied"));
        String imds = doc("").replace("\"host\":\"127.0.0.1\",\"port\":80}]},", "\"host\":\"169.254.169.254\","
                + "\"port\":80}]},");
        assertThat(errors(imds)).contains("upstreams[0].targets[0].host: address in a denied range (loopback, "
                + "private, link-local, metadata, ...); allow it explicitly with settings.upstreamAllowCidrs");
        assertThat(errors(doc("").replace("[\"127.0.0.0/8\"]", "[\"127.0.0.0/33\"]")))
                .anyMatch(e -> e.startsWith("settings.upstreamAllowCidrs[0]"));
    }

    @Test
    void addressPolicyCoversInternalRangesAndMappedForms() throws Exception {
        for (String denied : new String[] {"127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fc00::1",
                "ff02::1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "192.0.2.1"}) {
            assertThat(AddressPolicy.permitted(InetAddress.getByName(denied), List.of())).as(denied).isFalse();
        }
        for (String ok : new String[] {"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"}) {
            assertThat(AddressPolicy.permitted(InetAddress.getByName(ok), List.of())).as(ok).isTrue();
        }
        assertThat(AddressPolicy.permitted(InetAddress.getByName("10.1.2.3"), List.of(Cidr.parse("10.1.0.0/16"))))
                .isTrue();
        assertThat(AddressPolicy.permitted(InetAddress.getByName("::ffff:10.1.2.3"),
                List.of(Cidr.parse("10.1.0.0/16")))).isTrue();
    }

    @Test
    void apiKeyHashesAreSaltedAndVerified() {
        String secret = ApiKeyHash.newSecret();
        String h1 = ApiKeyHash.hash(secret);
        assertThat(h1).startsWith("sha256$").doesNotContain(secret).isNotEqualTo(ApiKeyHash.hash(secret));
        assertThat(ApiKeyHash.matches(ApiKeyHash.parse(h1), secret)).isTrue();
        assertThat(ApiKeyHash.matches(ApiKeyHash.parse(h1), secret + "x")).isFalse();
        assertThat(secret.length()).isGreaterThanOrEqualTo(ApiKeyHash.MIN_SECRET_LENGTH);
        assertThatThrownBy(() -> ApiKeyHash.parse("md5$a$b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyHash.parse("sha256$AAAA$AAAA")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyHash.parse("sha256$!!$!!")).isInstanceOf(IllegalArgumentException.class);
    }
}
