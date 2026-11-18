package apigw.proxy;

import apigw.core.pipeline.ProxyExchange;
import apigw.core.snapshot.CompiledUpstream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Target selection. Instances are per event loop and per upstream definition, so state needs no
 * synchronisation. {@code inflight[i]} is the number of requests this loop has open to target i.
 */
sealed interface Balancer {

    int pick(ProxyExchange exchange, int[] inflight);

    static Balancer of(CompiledUpstream u) {
        int n = u.targets().size();
        if (n == 1) {
            return new Single();
        }
        return switch (u.balancer()) {
            case ROUND_ROBIN -> new RoundRobin(n);
            case WEIGHTED_ROUND_ROBIN -> new SmoothWeighted(u.targets().stream()
                    .mapToInt(CompiledUpstream.Target::weight).toArray());
            case LEAST_REQUESTS -> new LeastRequests(u.targets().stream()
                    .mapToInt(CompiledUpstream.Target::weight).toArray());
            case CONSISTENT_HASH -> new ConsistentHash(u);
        };
    }

    /** One target. */
    record Single() implements Balancer {
        @Override
        public int pick(ProxyExchange exchange, int[] inflight) {
            return 0;
        }
    }

    /** Plain rotation. */
    final class RoundRobin implements Balancer {
        private final int n;
        private int next;

        RoundRobin(int n) {
            this.n = n;
            this.next = ThreadLocalRandom.current().nextInt(n);
        }

        @Override
        public int pick(ProxyExchange exchange, int[] inflight) {
            int i = next;
            next = i + 1 == n ? 0 : i + 1;
            return i;
        }
    }

    /** Smooth weighted round robin (nginx): spreads heavy targets out instead of bursting them. */
    final class SmoothWeighted implements Balancer {
        private final int[] weights;
        private final int[] current;
        private final int total;

        SmoothWeighted(int[] weights) {
            this.weights = weights.clone();
            this.current = new int[weights.length];
            this.total = Arrays.stream(weights).sum();
        }

        @Override
        public int pick(ProxyExchange exchange, int[] inflight) {
            int best = 0;
            for (int i = 0; i < weights.length; i++) {
                current[i] += weights[i];
                if (current[i] > current[best]) {
                    best = i;
                }
            }
            current[best] -= total;
            return best;
        }
    }

    /** Power of two choices on in-flight requests per weight. */
    final class LeastRequests implements Balancer {
        private final int[] weights;

        LeastRequests(int[] weights) {
            this.weights = weights.clone();
        }

        @Override
        public int pick(ProxyExchange exchange, int[] inflight) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            int n = weights.length;
            int a = rnd.nextInt(n);
            int b = rnd.nextInt(n - 1);
            if (b >= a) {
                b++;
            }
            // Compare inflight[a]/w[a] with inflight[b]/w[b] without division.
            long la = (long) inflight[a] * weights[b];
            long lb = (long) inflight[b] * weights[a];
            return la <= lb ? a : b;
        }
    }

    /** Hash ring with 160 virtual nodes per weight unit; a target change moves only its share of keys. */
    final class ConsistentHash implements Balancer {
        static final int VNODES_PER_WEIGHT = 160;
        private final long[] points;
        private final int[] owners;
        private final String header;

        ConsistentHash(CompiledUpstream u) {
            this.header = u.hashHeader();
            List<long[]> ring = new ArrayList<>();
            for (CompiledUpstream.Target t : u.targets()) {
                for (int v = 0; v < t.weight() * VNODES_PER_WEIGHT; v++) {
                    ring.add(new long[] {hash(t.authority() + "#" + v), t.index()});
                }
            }
            ring.sort((x, y) -> Long.compareUnsigned(x[0], y[0]));
            points = new long[ring.size()];
            owners = new int[ring.size()];
            for (int i = 0; i < ring.size(); i++) {
                points[i] = ring.get(i)[0];
                owners[i] = (int) ring.get(i)[1];
            }
        }

        @Override
        public int pick(ProxyExchange exchange, int[] inflight) {
            String key = header == null ? exchange.clientIp() : exchange.request().headers().get(header);
            if (key == null) {
                key = exchange.clientIp();
            }
            return ownerOf(hash(key));
        }

        int ownerOf(long h) {
            int lo = 0;
            int hi = points.length - 1;
            if (Long.compareUnsigned(h, points[hi]) > 0) {
                return owners[0];
            }
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (Long.compareUnsigned(points[mid], h) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return owners[lo];
        }

        /** FNV-1a 64 over UTF-16 units, then the SplitMix64 finaliser for avalanche. Not cryptographic. */
        static long hash(String s) {
            long h = 0xcbf29ce484222325L;
            for (int i = 0; i < s.length(); i++) {
                h ^= s.charAt(i);
                h *= 0x100000001b3L;
            }
            h ^= h >>> 30;
            h *= 0xbf58476d1ce4e5b9L;
            h ^= h >>> 27;
            h *= 0x94d049bb133111ebL;
            return h ^ (h >>> 31);
        }
    }
}
