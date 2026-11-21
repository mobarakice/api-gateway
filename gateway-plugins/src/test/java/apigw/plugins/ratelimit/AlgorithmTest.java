package apigw.plugins.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AlgorithmTest {

    @Test
    void tokenBucketAllowsBurstThenRefillsAtRate() {
        Algorithm.Limits l = new Algorithm.Limits(10, 1000, 5);
        Algorithm.State s = Algorithm.TOKEN_BUCKET.newState(l, 0);
        for (int i = 0; i < 5; i++) {
            assertThat(s.take(0)).isEqualTo(4 - i);
        }
        long denied = s.take(0);
        assertThat(denied).isNegative();
        assertThat(-denied).isEqualTo(100);
        assertThat(s.take(100)).isZero();
        assertThat(s.take(100)).isNegative();
        // Capped at the burst.
        assertThat(s.resetMs(10_000)).isZero();
        assertThat(s.take(10_000)).isEqualTo(4);
    }

    @Test
    void fixedWindowResetsAtTheBoundary() {
        Algorithm.State s = Algorithm.FIXED_WINDOW.newState(new Algorithm.Limits(2, 1000, 2), 0);
        assertThat(s.take(100)).isEqualTo(1);
        assertThat(s.take(200)).isZero();
        assertThat(s.take(300)).isEqualTo(-700);
        assertThat(s.take(1000)).isEqualTo(1);
    }

    @Test
    void slidingWindowWeighsThePreviousWindow() {
        Algorithm.State s = Algorithm.SLIDING_WINDOW.newState(new Algorithm.Limits(10, 1000, 10), 0);
        for (int i = 0; i < 10; i++) {
            assertThat(s.take(500)).isGreaterThanOrEqualTo(0);
        }
        assertThat(s.take(900)).isNegative();
        // At 1500 ms half of the previous window still counts: 5 of 10 used.
        for (int i = 0; i < 5; i++) {
            assertThat(s.take(1500)).as("take %d", i).isGreaterThanOrEqualTo(0);
        }
        assertThat(s.take(1500)).isNegative();
        // Two windows later nothing counts.
        assertThat(s.take(3100)).isEqualTo(9);
    }

    @Test
    void scriptsDigestLikeRedis() {
        // SHA-1 of the empty string is a fixed, well-known value.
        assertThat(RedisScripts.sha1("")).isEqualTo("da39a3ee5e6b4b0d3255bfef95601890afd80709");
    }
}
