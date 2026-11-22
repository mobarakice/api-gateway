package apigw.observability.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LogLineTest {

    @Test
    void addsTypedTopLevelFields() throws Exception {
        String text = LogLine.info("access", "request").field("requestId", "abc").field("traceId", null)
                .field("routeId", "r\"1").field("status", 200).millis("durationMs", 12_345_678).text();
        JsonNode n = new ObjectMapper().readTree(text);
        assertThat(n.path("logger").asText()).isEqualTo("access");
        assertThat(n.path("level").asText()).isEqualTo("INFO");
        assertThat(n.path("msg").asText()).isEqualTo("request");
        assertThat(n.path("requestId").asText()).isEqualTo("abc");
        assertThat(n.has("traceId")).isFalse();
        assertThat(n.path("routeId").asText()).isEqualTo("r\"1");
        assertThat(n.path("status").isInt()).isTrue();
        assertThat(n.path("durationMs").decimalValue()).isEqualByComparingTo("12.345");
        assertThat(LogLine.info("a", "b").millis("d", 5_000).text()).contains("\"d\":0.005");
    }
}
