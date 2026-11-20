package apigw.config.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void growsExponentiallyWithEqualJitterAndCaps() {
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(10));
        for (int i = 0; i < 1000; i++) {
            assertThat(b.delayMillis(0)).isBetween(50L, 100L);
            assertThat(b.delayMillis(3)).isBetween(400L, 800L);
            assertThat(b.delayMillis(10)).isBetween(5_000L, 10_000L);
            assertThat(b.delayMillis(Integer.MAX_VALUE)).isBetween(5_000L, 10_000L);
        }
    }
}
