package apigw.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.core.snapshot.CompiledUpstream;
import apigw.model.Upstream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BalancerTest {

    private static CompiledUpstream upstream(Upstream.Balancer b, int... weights) {
        List<CompiledUpstream.Target> t = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            t.add(new CompiledUpstream.Target(i, "10.0.0." + i, 80, weights[i], "10.0.0." + i + ":80"));
        }
        return new CompiledUpstream("u", Upstream.Scheme.HTTP, Optional.empty(), t, b, null, Upstream.Pool.DEFAULTS,
                false, Upstream.HealthCheck.NONE, null, null, "k");
    }

    @Test
    void smoothWeightedInterleaves() {
        Balancer b = Balancer.of(upstream(Upstream.Balancer.WEIGHTED_ROUND_ROBIN, 5, 1, 1));
        StringBuilder seq = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            seq.append(b.pick(null, new int[3], Balancer.Availability.ALL));
        }
        // nginx smooth WRR for {5,1,1}: a a b a c a a — heavy target spread, not bursted.
        assertThat(seq.toString()).isEqualTo("0010200");
    }

    @Test
    void roundRobinCyclesAllTargets() {
        Balancer b = Balancer.of(upstream(Upstream.Balancer.ROUND_ROBIN, 1, 1, 1));
        Map<Integer, Integer> seen = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            seen.merge(b.pick(null, new int[3], Balancer.Availability.ALL), 1, Integer::sum);
        }
        assertThat(seen).containsOnlyKeys(0, 1, 2).allSatisfy((k, v) -> assertThat(v).isEqualTo(100));
    }

    @Test
    void leastRequestsAvoidsTheBusyTarget() {
        Balancer b = Balancer.of(upstream(Upstream.Balancer.LEAST_REQUESTS, 1, 1));
        int[] inflight = {50, 0};
        for (int i = 0; i < 100; i++) {
            assertThat(b.pick(null, inflight, Balancer.Availability.ALL)).isEqualTo(1);
        }
    }

    @Test
    void consistentHashIsStableAndMovesFewKeysWhenATargetIsAdded() {
        Balancer.ConsistentHash three = new Balancer.ConsistentHash(
                upstream(Upstream.Balancer.CONSISTENT_HASH, 1, 1, 1));
        Balancer.ConsistentHash four = new Balancer.ConsistentHash(
                upstream(Upstream.Balancer.CONSISTENT_HASH, 1, 1, 1, 1));
        int moved = 0;
        int[] perTarget = new int[3];
        int keys = 20_000;
        for (int i = 0; i < keys; i++) {
            long h = Balancer.ConsistentHash.hash("client-" + i);
            int before = three.ownerOf(h);
            perTarget[before]++;
            assertThat(three.ownerOf(h)).isEqualTo(before);
            int after = four.ownerOf(h);
            if (after != before) {
                moved++;
                assertThat(after).as("keys only move to the new target").isEqualTo(3);
            }
        }
        // Ideal is 1/4 of keys; allow vnode variance.
        assertThat(moved).isBetween(keys / 5, keys * 3 / 10);
        for (int c : perTarget) {
            assertThat(c).isBetween(keys / 4, keys * 5 / 12);
        }
    }

    @Test
    void everyBalancerSkipsUnavailableTargets() {
        for (Upstream.Balancer kind : Upstream.Balancer.values()) {
            Balancer b = Balancer.of(upstream(kind, 1, 2, 3));
            Balancer.Availability notOne = i -> i != 1;
            for (int n = 0; n < 200; n++) {
                assertThat(b.pick(client("10.9.8." + (n % 50)), new int[3], notOne)).as(kind.name())
                        .isNotEqualTo(1);
            }
            assertThat(b.pick(client("10.0.0.1"), new int[3], i -> false)).isEqualTo(-1);
        }
    }

    /** An exchange that only answers {@code clientIp()} (all the hash balancer reads without a header). */
    private static apigw.core.pipeline.ProxyExchange client(String ip) {
        return (apigw.core.pipeline.ProxyExchange) java.lang.reflect.Proxy.newProxyInstance(
                BalancerTest.class.getClassLoader(), new Class<?>[] {apigw.core.pipeline.ProxyExchange.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("clientIp")) {
                        return ip;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }
}
