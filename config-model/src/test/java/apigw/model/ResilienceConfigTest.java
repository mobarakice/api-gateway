package apigw.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Retries, health checks, circuit breaker and upstream client certs in the config model (M5). */
class ResilienceConfigTest {

    private static String doc(String upstreamExtra, String routeExtra) {
        return "{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]},"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":80}]" + upstreamExtra
                + "}],\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\",\"retries\":{\"attempts\":1}}],"
                + "\"routes\":[{\"id\":\"r\",\"path\":\"/\",\"serviceId\":\"s\"" + routeExtra + "}]}";
    }

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

    @Test
    void decodesWithDefaults() throws Exception {
        GatewayConfig c = decode(doc(",\"healthCheck\":{\"active\":{},\"passive\":{}},\"circuitBreaker\":{}",
                ",\"retries\":{\"attempts\":2,\"on\":[\"timeout\",\"503\"]}"));
        Upstream u = c.upstreams().getFirst();
        assertThat(u.healthCheck().active().path()).isEqualTo("/");
        assertThat(u.healthCheck().active().intervalMs()).isEqualTo(5000);
        assertThat(u.healthCheck().passive().consecutiveFailures()).isEqualTo(5);
        assertThat(u.circuitBreaker().failureStatuses()).containsExactly(502, 503, 504);
        Retries r = c.routes().getFirst().retries();
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.on()).containsExactlyInAnyOrder(Retries.RetryOn.TIMEOUT, Retries.RetryOn.STATUS_503);
        assertThat(r.methods()).isEqualTo(Retries.IDEMPOTENT);
        assertThat(c.services().getFirst().retries().on()).isEqualTo(Retries.DEFAULT_ON);
        assertThat(errors(doc(",\"healthCheck\":{\"active\":{},\"passive\":{}},\"circuitBreaker\":{}", "")))
                .isEmpty();
    }

    @Test
    void rejectsOutOfRangeAndUnknownValues() {
        assertThat(errors(doc("", ",\"retries\":{\"attempts\":9,\"budgetPercent\":150,\"methods\":[\"BREW\"],"
                + "\"backoffMs\":100,\"maxBackoffMs\":50}")))
                .anyMatch(e -> e.startsWith("routes[0].retries.attempts"))
                .anyMatch(e -> e.startsWith("routes[0].retries.budgetPercent"))
                .anyMatch(e -> e.startsWith("routes[0].retries.methods"))
                .anyMatch(e -> e.startsWith("routes[0].retries.maxBackoffMs"));
        assertThat(errors(doc("", ",\"retries\":{\"attempts\":1,\"on\":[\"418\"]}")))
                .anyMatch(e -> e.startsWith("routes[0].retries.on"));
        assertThat(errors(doc(",\"healthCheck\":{\"active\":{\"path\":\"no-slash\",\"intervalMs\":1000,"
                + "\"timeoutMs\":2000}}", "")))
                .anyMatch(e -> e.startsWith("upstreams[0].healthCheck.active.path"))
                .anyMatch(e -> e.startsWith("upstreams[0].healthCheck.active.timeoutMs"));
        assertThat(errors(doc(",\"healthCheck\":{\"passive\":{\"maxEjectionPercent\":101}}", "")))
                .anyMatch(e -> e.startsWith("upstreams[0].healthCheck.passive.maxEjectionPercent"));
        assertThat(errors(doc(",\"circuitBreaker\":{\"failureRatePercent\":0,\"halfOpenCalls\":0}", "")))
                .anyMatch(e -> e.startsWith("upstreams[0].circuitBreaker.failureRatePercent"))
                .anyMatch(e -> e.startsWith("upstreams[0].circuitBreaker.halfOpenCalls"));
    }

    @Test
    void clientCertNeedsHttpsAndBothFiles() {
        assertThat(errors(doc(",\"clientCert\":{\"certFile\":\"/c.pem\",\"keyFile\":\"/k.pem\"}", "")))
                .anyMatch(e -> e.startsWith("upstreams[0].clientCert"));
        assertThat(errors(doc(",\"scheme\":\"https\",\"clientCert\":{\"certFile\":\"/c.pem\"}", "")))
                .anyMatch(e -> e.startsWith("upstreams[0].clientCert"));
    }
}
