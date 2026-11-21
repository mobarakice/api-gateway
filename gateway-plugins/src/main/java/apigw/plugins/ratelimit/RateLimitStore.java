package apigw.plugins.ratelimit;

import apigw.plugins.redis.RedisClient;
import apigw.plugins.redis.Reply;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vertx.core.Future;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide rate-limit state, long-lived across snapshots (ADR 0018): per-key local state, and for
 * global limits a per-key reserve of quota pre-fetched from Redis. Bounded: least recently used keys
 * are evicted (an evicted key starts fresh, which can only admit more, never less).
 */
public final class RateLimitStore {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitStore.class);
    private static final long LOG_INTERVAL_MS = 10_000;

    private final Cache<String, Algorithm.State> local;
    private final Cache<String, Reserve> reserves;
    private final RedisClient redis;
    private final AtomicLong lastRedisWarn = new AtomicLong();

    /**
     * @param maxKeys bound on tracked keys (local and reserves each)
     * @param redis   null when no Redis is configured: global limits then always use their fail mode
     */
    public RateLimitStore(long maxKeys, RedisClient redis) {
        // Size-bounded only (no expiry): idle keys age out through eviction, and the plain maximumSize variant
        // is the one the vendored Caffeine native metadata covers (no hand-written reflection entries).
        this.local = Caffeine.newBuilder().maximumSize(maxKeys).build();
        this.reserves = Caffeine.newBuilder().maximumSize(maxKeys).build();
        this.redis = redis;
    }

    public Optional<RedisClient> redis() {
        return Optional.ofNullable(redis);
    }

    /** Cheap check (a volatile read) so a disconnected Redis fails fast without queueing. */
    boolean redisReady() {
        return redis != null && redis.ready();
    }

    long takeLocal(String key, Algorithm algorithm, Algorithm.Limits limits, long nowMs) {
        Algorithm.State s = local.get(key, k -> algorithm.newState(limits, nowMs));
        return s.take(nowMs);
    }

    long resetLocal(String key, Algorithm algorithm, Algorithm.Limits limits, long nowMs) {
        Algorithm.State s = local.get(key, k -> algorithm.newState(limits, nowMs));
        return s.resetMs(nowMs);
    }

    Reserve reserve(String key) {
        return reserves.get(key, k -> new Reserve());
    }

    /** Quota held locally for one global key. Guarded by its monitor; no I/O under it. */
    static final class Reserve {
        long tokens;
        long validUntilMs;
        long deniedUntilMs;
        long remainingHint;
        long resetMs;
        long degradedUntilMs;
        boolean fetching;
        /** Waiters for the in-flight fetch (their continuations). */
        java.util.ArrayList<Runnable> waiters;
    }

    /** {@code {granted, remaining, retryAfterMs, resetMs}} from Redis, or a failed future. */
    Future<long[]> fetch(String redisKey, Algorithm a, Algorithm.Limits l, long requested, long timeoutMs) {
        if (redis == null) {
            return Future.failedFuture(new RedisClient.Unavailable("no redis configured"));
        }
        String script = RedisScripts.script(a);
        String sha = Scripts.SHA.get(a);
        Object[] argv = a == Algorithm.TOKEN_BUCKET
                ? new Object[] {Long.toString(l.burst()), Double.toString(l.ratePerMs()), Long.toString(requested),
                        Long.toString(Math.max(1000, (long) Math.ceil(l.burst() / l.ratePerMs()) * 2))}
                : new Object[] {Long.toString(l.limit()), Long.toString(l.windowMs()), Long.toString(requested)};
        return eval(sha, script, redisKey, argv, timeoutMs).compose(r -> {
            if (r instanceof Reply.Error e && e.message().startsWith("NOSCRIPT")) {
                return eval(null, script, redisKey, argv, timeoutMs);
            }
            return Future.succeededFuture(r);
        }).compose(r -> {
            if (r instanceof Reply.Array arr && arr.items() != null && arr.items().size() == 4
                    && arr.items().stream().allMatch(i -> i instanceof Reply.Int)) {
                List<Reply> it = arr.items();
                return Future.succeededFuture(new long[] {((Reply.Int) it.get(0)).value(),
                        ((Reply.Int) it.get(1)).value(), ((Reply.Int) it.get(2)).value(),
                        ((Reply.Int) it.get(3)).value()});
            }
            return Future.failedFuture(new RedisClient.Unavailable("unexpected rate-limit script reply"));
        });
    }

    private Future<Reply> eval(String sha, String script, String key, Object[] argv, long timeoutMs) {
        Object[] args = new Object[4 + argv.length];
        args[0] = sha == null ? "EVAL" : "EVALSHA";
        args[1] = sha == null ? script : sha;
        args[2] = "1";
        args[3] = key;
        System.arraycopy(argv, 0, args, 4, argv.length);
        return redis.sendTimed(timeoutMs, args);
    }

    void warnRedis(String limiter, Throwable cause, String mode) {
        long now = System.currentTimeMillis();
        long last = lastRedisWarn.get();
        if (now - last >= LOG_INTERVAL_MS && lastRedisWarn.compareAndSet(last, now)) {
            LOG.warn("global rate limit degraded limiter={} failMode={} cause={}", limiter, mode,
                    cause.getMessage());
        }
    }

    /** Precomputed EVALSHA digests. */
    private static final class Scripts {
        static final java.util.Map<Algorithm, String> SHA = java.util.Map.of(
                Algorithm.TOKEN_BUCKET, RedisScripts.sha1(RedisScripts.TOKEN_BUCKET),
                Algorithm.FIXED_WINDOW, RedisScripts.sha1(RedisScripts.FIXED_WINDOW),
                Algorithm.SLIDING_WINDOW, RedisScripts.sha1(RedisScripts.SLIDING_WINDOW));
    }
}
