package apigw.plugins.cache;

import apigw.plugins.redis.RedisClient;
import apigw.plugins.redis.Reply;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide response cache (ADR 0019): a byte-bounded Caffeine L1 shared by all event loops, an
 * optional Redis L2, and the in-flight table used to coalesce concurrent misses. Long-lived across
 * snapshots; entries carry their route ID, so purges and config changes apply lazily on lookup.
 */
public final class ResponseCache {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseCache.class);
    private static final long WARN_INTERVAL_MS = 10_000;

    private final Cache<String, CachedResponse> l1;
    private final RedisClient redis;
    private final Map<String, Flight> flights = new ConcurrentHashMap<>();
    private final AtomicLong lastWarn = new AtomicLong();
    private final LongAdder[] lookups = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};

    /** Lookup results counted for metrics: index into {@link #lookups(int)}. */
    public static final int HIT = 0;
    public static final int STALE = 1;
    public static final int MISS = 2;
    public static final int BYPASS = 3;

    /**
     * @param maxBytes approximate L1 bound (entry weight is body + headers + key)
     * @param redis    null = no L2
     */
    public ResponseCache(long maxBytes, RedisClient redis) {
        this.l1 = Caffeine.newBuilder().maximumWeight(maxBytes)
                .weigher((String k, CachedResponse v) -> v.weight()).build();
        this.redis = redis;
    }

    void counted(int result) {
        lookups[result].increment();
    }

    /** Lookups since start with the given result ({@link #HIT}, {@link #STALE}, {@link #MISS}, {@link #BYPASS}). */
    public long lookups(int result) {
        return lookups[result].sum();
    }

    /** Bytes held in L1 (approximate weight). */
    public long l1Bytes() {
        return l1.policy().eviction().map(e -> e.weightedSize().orElse(0)).orElse(0L);
    }

    CachedResponse get(String key) {
        return l1.getIfPresent(key);
    }

    void put(CachedResponse r) {
        l1.put(r.key(), r);
    }

    void invalidate(String key) {
        l1.invalidate(key);
    }

    boolean l2Ready() {
        return redis != null && redis.ready();
    }

    /** L2 lookup; fails open (a failed or slow GET is a miss). Completes on the Redis context. */
    Future<CachedResponse> l2Get(String key, long timeoutMs) {
        return redis.sendTimed(timeoutMs, "GET", l2Key(key)).map(r -> {
            if (r instanceof Reply.Bulk bulk && bulk.value() != null) {
                CachedResponse c = CachedResponse.decode(bulk.value());
                return c != null && c.key().equals(key) ? c : null;
            }
            return null;
        }).otherwise(e -> {
            warn(e);
            return null;
        });
    }

    void l2Put(CachedResponse r, long ttlMs, long timeoutMs) {
        if (!l2Ready() || ttlMs <= 0) {
            return;
        }
        redis.sendTimed(timeoutMs, "SET", l2Key(r.key()), r.encode(), "PX", Long.toString(ttlMs))
                .onFailure(this::warn);
    }

    private void warn(Throwable e) {
        long now = System.currentTimeMillis();
        long last = lastWarn.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarn.compareAndSet(last, now)) {
            LOG.warn("cache L2 unavailable; serving from L1 and upstream cause={}", e.getMessage());
        }
    }

    static String l2Key(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "gwc:" + HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Joins the in-flight fetch for {@code key}, or starts one. Returns null when the caller is now the
     * leader (it must call {@link #complete}), otherwise the flight it joined. {@code waiter} gets the
     * entry, or null if the leader produced nothing cacheable.
     */
    Flight join(String key, Object leader, Consumer<CachedResponse> waiter, int maxWaiters) {
        Flight mine = new Flight(leader);
        Flight existing = flights.putIfAbsent(key, mine);
        if (existing == null) {
            return null;
        }
        return existing.add(waiter, maxWaiters) ? existing : Flight.FULL;
    }

    /** True if a fetch for {@code key} is in flight (another request is refreshing it). */
    boolean inFlight(String key) {
        return flights.containsKey(key);
    }

    /** Becomes the leader only if no fetch is in flight. */
    boolean lead(String key, Object leader) {
        return flights.putIfAbsent(key, new Flight(leader)) == null;
    }

    /** Ends {@code leader}'s flight (idempotent; ignores other leaders' flights). */
    void complete(String key, Object leader, CachedResponse result) {
        Flight f = flights.get(key);
        if (f == null || f.leader != leader || !flights.remove(key, f)) {
            return;
        }
        for (Consumer<CachedResponse> w : f.drain()) {
            w.accept(result);
        }
    }

    /** Concurrent misses for one key: the leader fetches, the rest wait for its result. */
    static final class Flight {
        static final Flight FULL = new Flight(null);

        final Object leader;
        private List<Consumer<CachedResponse>> waiters = new ArrayList<>(4);

        Flight(Object leader) {
            this.leader = leader;
        }

        synchronized boolean add(Consumer<CachedResponse> w, int max) {
            if (waiters == null || waiters.size() >= max) {
                return false;
            }
            waiters.add(w);
            return true;
        }

        synchronized List<Consumer<CachedResponse>> drain() {
            List<Consumer<CachedResponse>> out = waiters;
            waiters = null;
            return out;
        }
    }

    /** For tests and the L2 codec. */
    static Buffer copy(Buffer b) {
        return b.copy();
    }
}
