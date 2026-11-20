package apigw.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    private static List<String> errors(String json) {
        try {
            ConfigValidator.validate(ConfigDecoderTest.decode(json));
            return List.of();
        } catch (ConfigException e) {
            return e.errors().stream().map(Object::toString).toList();
        }
    }

    private static String doc(String routes, String services, String upstreams) {
        return "{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\",\"::1/128\"]},"
                + "\"routes\":[" + routes + "],\"services\":[" + services + "],\"upstreams\":["
                + upstreams + "]}";
    }

    private static final String U = "{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":80}]}";
    private static final String S = "{\"id\":\"s\",\"upstreamId\":\"u\"}";

    @Test
    void checksReferences() {
        assertThat(errors(doc("{\"id\":\"r\",\"path\":\"/\",\"serviceId\":\"nope\"}",
                "{\"id\":\"s\",\"upstreamId\":\"missing\"}", U)))
                .contains("routes[0].serviceId: references unknown service 'nope'",
                        "services[0].upstreamId: references unknown upstream 'missing'");
    }

    @Test
    void checksPathSyntax() {
        assertThat(errors(doc("{\"id\":\"a\",\"path\":\"x\",\"serviceId\":\"s\"},"
                + "{\"id\":\"b\",\"path\":\"/a/../b\",\"serviceId\":\"s\"},"
                + "{\"id\":\"c\",\"path\":\"/a/{id}\",\"serviceId\":\"s\"},"
                + "{\"id\":\"d\",\"path\":\"/a/x{id}\",\"match\":\"template\",\"serviceId\":\"s\"},"
                + "{\"id\":\"e\",\"path\":\"/a/{id}/{id}\",\"match\":\"template\",\"serviceId\":\"s\"}", S, U)))
                .contains("routes[0].path: must start with '/'",
                        "routes[1].path: must not contain '.' or '..' segments",
                        "routes[2].path: '{param}' segments need match: template",
                        "routes[3].path: template segment 'x{id}' must be exactly {name}",
                        "routes[4].path: duplicate template parameter 'id'");
    }

    @Test
    void checksHostsMethodsAndDuplicates() {
        assertThat(errors(doc("{\"id\":\"a\",\"path\":\"/\",\"serviceId\":\"s\",\"hosts\":[\"Api.X\",\"a.*.com\"],"
                + "\"methods\":[\"get\"]},"
                + "{\"id\":\"a\",\"path\":\"/p/{x}\",\"match\":\"template\",\"serviceId\":\"s\"},"
                + "{\"id\":\"b\",\"path\":\"/p/{y}\",\"match\":\"template\",\"serviceId\":\"s\"}", S, U)))
                .contains("routes[0].hosts[0]: must be lower case",
                        "routes[0].hosts[1]: invalid host 'a.*.com' (wildcards are only allowed as a leading '*.')",
                        "routes[0].methods[0]: unknown method 'get' (use upper case)",
                        "routes[1].id: duplicate id 'a'",
                        "routes[2]: matches exactly the same requests as routes[1] with the same priority");
    }

    @Test
    void checksUpstreams() {
        assertThat(errors(doc("", "", "{\"id\":\"u\",\"targets\":[{\"host\":\"\",\"port\":0,\"weight\":0}],"
                + "\"balancer\":\"consistent-hash\"},{\"id\":\"v\",\"targets\":[]}")))
                .contains("upstreams[0].targets[0].host: must be a host name or IP address",
                        "upstreams[0].targets[0].port: must be between 1 and 65535",
                        "upstreams[0].targets[0].weight: must be between 1 and 1000",
                        "upstreams[0].hashKey: consistent-hash needs hashKey 'ip' or 'header:<name>'",
                        "upstreams[1].targets: at least one target is required");
    }

    @Test
    void rejectsUnsupportedSchemaVersionAndBadCidr() {
        assertThat(errors("{\"schemaVersion\":2,\"settings\":{\"trustedProxies\":[\"10.0.0.0/33\",\"proxy.local\"]}}"))
                .contains("schemaVersion: unsupported schema version 2 (this build supports 1..1)",
                        "settings.trustedProxies[0]: prefix length out of range in 10.0.0.0/33",
                        "settings.trustedProxies[1]: not an IP literal: proxy.local");
    }

    @Test
    void acceptsTheReferenceDocument() {
        assertThat(errors(ConfigDecoderTest.VALID)).isEmpty();
    }

    @Test
    void cidrMatching() {
        Cidr v4 = Cidr.parse("10.1.0.0/16");
        assertThat(v4.contains(Cidr.literal("10.1.200.3"))).isTrue();
        assertThat(v4.contains(Cidr.literal("10.2.0.1"))).isFalse();
        assertThat(v4.contains(Cidr.literal("::1"))).isFalse();
        assertThat(Cidr.parse("2001:db8::/32").contains(Cidr.literal("2001:db8:ffff::1"))).isTrue();
        assertThat(Cidr.parse("192.168.1.7").contains(Cidr.literal("192.168.1.7"))).isTrue();
        assertThat(Cidr.parse("0.0.0.0/0").contains(Cidr.literal("8.8.8.8"))).isTrue();
        assertThatThrownBy(() -> Cidr.literal("1.2.3")).isInstanceOf(IllegalArgumentException.class);
    }
}
