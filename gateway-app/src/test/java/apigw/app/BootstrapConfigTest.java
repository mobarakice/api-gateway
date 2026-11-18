package apigw.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BootstrapConfigTest {

    @Test
    void appliesDefaults() {
        BootstrapConfig c = BootstrapConfig.fromEnvironment(Map.of());
        assertThat(c.httpPort()).isEqualTo(8080);
        assertThat(c.httpsPort()).isEqualTo(8443);
        assertThat(c.statusPort()).isEqualTo(9901);
        assertThat(c.tls()).isEmpty();
        assertThat(c.eventLoops()).isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(c.shutdownGrace().toSeconds()).isEqualTo(20);
    }

    @Test
    void rejectsHalfConfiguredTls() {
        assertThatThrownBy(() -> BootstrapConfig.fromEnvironment(Map.of("GW_TLS_CERT", "/x.pem")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set together");
    }

    @Test
    void rejectsInvalidPortsAndLoops() {
        assertThatThrownBy(() -> BootstrapConfig.fromEnvironment(Map.of("GW_HTTP_PORT", "70000")))
                .hasMessageContaining("GW_HTTP_PORT");
        assertThatThrownBy(() -> BootstrapConfig.fromEnvironment(Map.of("GW_EVENT_LOOPS", "0")))
                .hasMessageContaining("GW_EVENT_LOOPS");
        assertThatThrownBy(() -> BootstrapConfig.fromEnvironment(Map.of("GW_STATUS_PORT", "abc")))
                .hasMessageContaining("integer");
    }

    @Test
    void requestIdsAreSixteenHexChars() {
        for (int i = 0; i < 1000; i++) {
            assertThat(RequestIds.next()).matches("[0-9a-f]{16}");
        }
    }
}
