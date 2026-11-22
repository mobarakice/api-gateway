package apigw.plugins.cache;

import apigw.core.auth.Principal;
import apigw.core.plugin.Exchange;
import apigw.core.plugin.LogPlugin;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.RequestPlugin;
import apigw.core.plugin.ResponseTap;
import apigw.model.ConfigError;
import apigw.plugins.support.Configs;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Context;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code cache} (ADR 0019). Config:
 * <pre>{"ttlSeconds": 0, "maxTtlSeconds": 3600, "staleWhileRevalidateSeconds": 0, "statuses": [200],
 *  "vary": ["accept", "accept-encoding"], "query": "all" | "ignore" | ["page", "sort"],
 *  "maxBodyBytes": 1048576, "cacheAuthenticated": false, "tagHeader": "cache-tag",
 *  "l2": false, "l2TimeoutMs": 20, "lockTimeoutMs": 5000, "statusHeader": true}</pre>
 *
 * <p>GET responses are stored (HEAD is answered from them). The key is tenant, route, method class, path,
 * the normalised query and the configured {@code vary} request headers; with {@code cacheAuthenticated}
 * also the principal. Freshness comes from {@code s-maxage}/{@code max-age} (minus {@code Age}), else
 * {@code ttlSeconds} (0 = only explicitly cacheable responses), capped by {@code maxTtlSeconds}.
 *
 * <p>Never stored: {@code Set-Cookie}; {@code no-store}, {@code no-cache}; {@code Vary: *} or a Vary header
 * outside {@code vary}; and, unless {@code cacheAuthenticated} (which isolates the principal in the key),
 * {@code private} responses and requests with a principal or an {@code Authorization} header.
 */
public final class CachePluginFactory implements PluginFactory {

    private static final Set<String> NOT_STORED = Set.of("age", "content-length", "set-cookie");

    @Override
    public String name() {
        return "cache";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<Phase> phases() {
        return Set.of(Phase.BEFORE_PROXY, Phase.LOG);
    }

    @Override
    public int priority() {
        return 500;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/cache.json";
    }

    @Override
    public Plugin create(JsonNode config, PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Configs.known(config, "", Set.of("ttlSeconds", "maxTtlSeconds", "staleWhileRevalidateSeconds", "statuses",
                "vary", "query", "maxBodyBytes", "cacheAuthenticated", "tagHeader", "l2", "l2TimeoutMs",
                "lockTimeoutMs", "statusHeader"), errors);
        int ttl = Configs.integer(config, "", "ttlSeconds", 0, 0, 31_536_000, errors);
        int maxTtl = Configs.integer(config, "", "maxTtlSeconds", 3600, 1, 31_536_000, errors);
        int swr = Configs.integer(config, "", "staleWhileRevalidateSeconds", 0, 0, 86_400, errors);
        int[] statuses = {200};
        JsonNode st = config.get("statuses");
        if (st != null) {
            if (!st.isArray() || st.isEmpty()) {
                errors.add(new ConfigError("statuses", "must be a non-empty array"));
            } else {
                statuses = new int[st.size()];
                for (int i = 0; i < st.size(); i++) {
                    int s = st.get(i).asInt(-1);
                    if (!st.get(i).isInt() || !Set.of(200, 203, 204, 300, 301, 308, 404, 405, 410, 414, 501)
                            .contains(s)) {
                        errors.add(new ConfigError("statuses[" + i + "]", "not a heuristically cacheable status"));
                    }
                    statuses[i] = s;
                }
            }
        }
        List<String> vary = Configs.headerNames(config, "", "vary", errors);
        String[] queryInclude = null;
        boolean queryIgnore = false;
        JsonNode q = config.get("query");
        if (q != null && q.isTextual()) {
            if (q.textValue().equals("ignore")) {
                queryIgnore = true;
            } else if (!q.textValue().equals("all")) {
                errors.add(new ConfigError("query", "must be all, ignore or an array of parameter names"));
            }
        } else if (q != null) {
            queryInclude = Configs.strings(config, "", "query", errors).stream().sorted().toArray(String[]::new);
        }
        int maxBody = Configs.integer(config, "", "maxBodyBytes", 1 << 20, 0, 64 << 20, errors);
        boolean auth = Configs.bool(config, "", "cacheAuthenticated", false, errors);
        String tagHeader = Configs.string(config, "", "tagHeader", "cache-tag", errors).toLowerCase(Locale.ROOT);
        if (!Configs.token(tagHeader)) {
            errors.add(new ConfigError("tagHeader", "not a valid header name"));
        }
        boolean l2 = Configs.bool(config, "", "l2", false, errors);
        int l2Timeout = Configs.integer(config, "", "l2TimeoutMs", 20, 1, 5000, errors);
        int lockTimeout = Configs.integer(config, "", "lockTimeoutMs", 5000, 10, 60_000, errors);
        boolean statusHeader = Configs.bool(config, "", "statusHeader", true, errors);
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        ResponseCache cache = context.services().find(ResponseCache.class).orElse(null);
        if (cache == null) {
            throw new PluginConfigException("", "response cache not available in this process");
        }
        Arrays.sort(statuses);
        return new CachePlugin(cache, ttl * 1000L, maxTtl * 1000L, swr * 1000L, statuses,
                vary.stream().sorted().toArray(String[]::new), Set.copyOf(vary), queryIgnore, queryInclude,
                maxBody, auth, tagHeader, l2, l2Timeout, lockTimeout, statusHeader);
    }

    /** The compiled cache policy. Immutable; entries live in {@link ResponseCache}. */
    record CachePlugin(ResponseCache cache, long ttlMs, long maxTtlMs, long swrMs, int[] statuses, String[] vary,
            Set<String> varySet, boolean queryIgnore, String[] queryInclude, int maxBody, boolean cacheAuthenticated,
            String tagHeader, boolean l2, int l2TimeoutMs, int lockTimeoutMs, boolean statusHeader)
            implements RequestPlugin, LogPlugin {

        @Override
        public Outcome onRequest(Exchange exchange) {
            var req = exchange.request();
            HttpMethod m = req.method();
            if (m != HttpMethod.GET && m != HttpMethod.HEAD) {
                return Outcome.CONTINUE;
            }
            String reqCc = req.getHeader("cache-control");
            if (reqCc != null && containsDirective(reqCc, "no-store")) {
                return bypass(exchange);
            }
            Principal principal = exchange.principal();
            boolean authenticated = principal != null || req.headers().contains("authorization");
            if (authenticated && (!cacheAuthenticated || principal == null)) {
                return bypass(exchange);
            }
            String key = key(exchange, principal);
            long now = System.currentTimeMillis();
            boolean revalidate = reqCc != null && (containsDirective(reqCc, "no-cache")
                    || containsDirective(reqCc, "max-age=0"));
            CachedResponse hit = revalidate ? null : cache.get(key);
            if (hit != null && exchange.snapshot().cachePurges().purged(exchange.route().id(), hit.tags(),
                    hit.storedAtMs())) {
                cache.invalidate(key);
                hit = null;
            }
            if (hit != null) {
                if (now < hit.freshUntilMs()) {
                    return serve(exchange, hit, now, "HIT");
                }
                if (now < hit.staleUntilMs() && (m == HttpMethod.HEAD || !cache.lead(key, exchange))) {
                    // Another request is refreshing it (or HEAD cannot refresh): serve the stale copy now.
                    return serve(exchange, hit, now, "STALE");
                }
                if (now < hit.staleUntilMs()) {
                    // This request leads the refresh (in-band revalidation, ADR 0019).
                    exchange.tapResponse(new Store(this, exchange, key));
                    marker(exchange, "EXPIRED");
                    return Outcome.CONTINUE;
                }
            }
            if (m == HttpMethod.HEAD) {
                return bypass(exchange);
            }
            Context ctx = Vertx.currentContext();
            // One-shot: the leader's result and the lock timeout race; both run on this loop.
            AtomicBoolean once = new AtomicBoolean();
            long[] timer = {-1};
            ResponseCache.Flight joined = cache.join(key, exchange, entry -> ctx.runOnContext(v -> {
                if (timer[0] >= 0) {
                    ctx.owner().cancelTimer(timer[0]);
                }
                wake(exchange, entry, once);
            }), 10_000);
            if (joined == ResponseCache.Flight.FULL) {
                return bypass(exchange);
            }
            if (joined != null) {
                // Coalesced: wait for the leader. A stuck leader cannot hold us longer than the lock timeout.
                timer[0] = ctx.owner().setTimer(lockTimeoutMs, id -> wake(exchange, null, once));
                return Outcome.PENDING;
            }
            exchange.tapResponse(new Store(this, exchange, key));
            if (l2 && cache.l2Ready()) {
                cache.l2Get(key, l2TimeoutMs).onComplete(ar -> ctx.runOnContext(v -> {
                    CachedResponse found = ar.succeeded() ? ar.result() : null;
                    long t = System.currentTimeMillis();
                    if (found != null && t < found.freshUntilMs() && !exchange.snapshot().cachePurges()
                            .purged(exchange.route().id(), found.tags(), found.storedAtMs())) {
                        cache.put(found);
                        cache.complete(key, exchange, found);
                        exchange.tapResponse(null);
                        serve(exchange, found, t, "HIT");
                    } else {
                        marker(exchange, "MISS");
                        exchange.resume();
                    }
                }));
                return Outcome.PENDING;
            }
            marker(exchange, "MISS");
            return Outcome.CONTINUE;
        }

        /** A coalesced request's turn: serve the leader's entry, or go upstream itself. Runs once. */
        private void wake(Exchange exchange, CachedResponse entry, AtomicBoolean once) {
            if (!once.compareAndSet(false, true) || exchange.request().response().headWritten()
                    || exchange.request().response().closed()) {
                return;
            }
            if (entry != null) {
                serve(exchange, entry, System.currentTimeMillis(), "HIT");
            } else {
                marker(exchange, "MISS");
                exchange.resume();
            }
        }

        @Override
        public void onComplete(Exchange exchange, int status, long durationNanos) {
            var m = exchange.request().method();
            if (m != HttpMethod.GET) {
                return;
            }
            // A leader that never produced a stored response (error, denied later, client gone) releases its
            // waiters; complete() ignores flights it does not lead.
            Principal p = exchange.principal();
            if (p != null && !cacheAuthenticated) {
                return;
            }
            cache.complete(key(exchange, p), exchange, null);
        }

        private Outcome serve(Exchange exchange, CachedResponse r, long now, String state) {
            cache.counted("HIT".equals(state) ? ResponseCache.HIT : ResponseCache.STALE);
            MultiMap h = r.headers();
            h.set("age", Long.toString(Math.max(0, (now - r.storedAtMs()) / 1000)));
            if (statusHeader) {
                h.set("x-cache", state);
            }
            boolean head = exchange.request().method() == HttpMethod.HEAD;
            if (head) {
                h.set("content-length", Integer.toString(r.body().length()));
            }
            return exchange.respondWith(r.status(), h, head ? null : r.body().slice());
        }

        private Outcome bypass(Exchange exchange) {
            marker(exchange, "BYPASS");
            return Outcome.CONTINUE;
        }

        private void marker(Exchange exchange, String state) {
            cache.counted("BYPASS".equals(state) ? ResponseCache.BYPASS : ResponseCache.MISS);
            if (statusHeader) {
                exchange.addResponseHeader("x-cache", state);
            }
        }

        String key(Exchange exchange, Principal principal) {
            var req = exchange.request();
            String tenant = exchange.route().tenantId();
            StringBuilder b = new StringBuilder(128)
                    .append(tenant == null ? "" : tenant).append('\n')
                    .append(exchange.route().id()).append('\n')
                    .append(req.path()).append('\n');
            if (!queryIgnore) {
                appendQuery(b, req.query());
            }
            for (String v : vary) {
                b.append('\n').append(v).append(':');
                String hv = req.getHeader(v);
                if (hv != null) {
                    b.append(hv.length() > 512 ? hv.substring(0, 512) : hv);
                }
            }
            if (cacheAuthenticated && principal != null) {
                b.append("\nprincipal:").append(principal.tenantId()).append('/')
                        .append(principal.consumer() == null ? "" : principal.consumer().id()).append('/')
                        .append(principal.subject());
            }
            return b.toString();
        }

        /** Sorted query parameters (or only the included ones), so a=1&b=2 and b=2&a=1 share an entry. */
        private void appendQuery(StringBuilder b, String query) {
            if (query == null || query.isEmpty()) {
                return;
            }
            String[] pairs = query.split("&");
            Arrays.sort(pairs);
            boolean first = true;
            for (String p : pairs) {
                if (p.isEmpty()) {
                    continue;
                }
                if (queryInclude != null) {
                    int eq = p.indexOf('=');
                    String k = eq < 0 ? p : p.substring(0, eq);
                    if (Arrays.binarySearch(queryInclude, k) < 0) {
                        continue;
                    }
                }
                if (!first) {
                    b.append('&');
                }
                b.append(p);
                first = false;
            }
        }

        boolean cacheableStatus(int status) {
            return Arrays.binarySearch(statuses, status) >= 0;
        }
    }

    static boolean containsDirective(String header, String directive) {
        String h = header.toLowerCase(Locale.ROOT);
        int i = h.indexOf(directive);
        while (i >= 0) {
            boolean start = i == 0 || h.charAt(i - 1) == ',' || h.charAt(i - 1) == ' ';
            int end = i + directive.length();
            boolean stop = end == h.length() || h.charAt(end) == ',' || h.charAt(end) == ' '
                    || h.charAt(end) == '=' && !directive.contains("=");
            if (start && stop) {
                return true;
            }
            i = h.indexOf(directive, i + 1);
        }
        return false;
    }

    /** {@code -1} if absent or malformed. */
    static long directiveSeconds(String header, String name) {
        String h = header.toLowerCase(Locale.ROOT);
        int i = h.indexOf(name + "=");
        while (i >= 0) {
            if (i == 0 || h.charAt(i - 1) == ',' || h.charAt(i - 1) == ' ') {
                int s = i + name.length() + 1;
                int e = s;
                while (e < h.length() && Character.isDigit(h.charAt(e))) {
                    e++;
                }
                if (e > s && e - s <= 10) {
                    return Long.parseLong(h, s, e, 10);
                }
                return -1;
            }
            i = h.indexOf(name + "=", i + 1);
        }
        return -1;
    }

    /** Buffers a leader's response and stores it when it completes, if it is cacheable. */
    static final class Store implements ResponseTap {

        private final CachePlugin policy;
        private final Exchange exchange;
        private final String key;
        private CachedResponse.Builder head;
        private Buffer body;
        private boolean active;

        Store(CachePlugin policy, Exchange exchange, String key) {
            this.policy = policy;
            this.exchange = exchange;
            this.key = key;
        }

        @Override
        public boolean onHead(int status, MultiMap h) {
            long now = System.currentTimeMillis();
            CachedResponse.Builder b = cacheable(status, h, now);
            if (b == null) {
                // Waiters go upstream themselves right away instead of waiting for this body.
                policy.cache().complete(key, exchange, null);
                return false;
            }
            head = b;
            body = Buffer.buffer(Math.min(policy.maxBody(), 16 * 1024));
            active = true;
            return true;
        }

        private CachedResponse.Builder cacheable(int status, MultiMap h, long now) {
            if (!policy.cacheableStatus(status) || h.contains("set-cookie")) {
                return null;
            }
            String cc = h.get("cache-control");
            if (cc != null && (containsDirective(cc, "no-store") || containsDirective(cc, "no-cache")
                    || containsDirective(cc, "private") && !(policy.cacheAuthenticated()
                            && exchange.principal() != null))) {
                return null;
            }
            for (String v : h.getAll("vary")) {
                for (String part : v.split(",")) {
                    String name = part.trim().toLowerCase(Locale.ROOT);
                    if (name.equals("*") || !name.isEmpty() && !policy.varySet().contains(name)) {
                        return null;
                    }
                }
            }
            long ttl = -1;
            if (cc != null) {
                ttl = directiveSeconds(cc, "s-maxage");
                if (ttl < 0) {
                    ttl = directiveSeconds(cc, "max-age");
                }
            }
            long ttlMs = ttl >= 0 ? ttl * 1000 : policy.ttlMs();
            String age = h.get("age");
            if (age != null) {
                try {
                    ttlMs -= Long.parseLong(age.trim()) * 1000;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            ttlMs = Math.min(ttlMs, policy.maxTtlMs());
            if (ttlMs <= 0) {
                return null;
            }
            long swr = cc == null ? -1 : directiveSeconds(cc, "stale-while-revalidate");
            long swrMs = swr >= 0 ? Math.min(swr * 1000, 86_400_000L) : policy.swrMs();
            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (var e : h.entries()) {
                String n = e.getKey().toLowerCase(Locale.ROOT);
                if (!NOT_STORED.contains(n) && !n.equals("x-cache")) {
                    names.add(n);
                    values.add(e.getValue());
                }
            }
            List<String> tags = new ArrayList<>();
            for (String v : h.getAll(policy.tagHeader())) {
                for (String t : v.split("[,\\s]+")) {
                    if (!t.isEmpty() && t.length() <= 256 && tags.size() < 32) {
                        tags.add(t);
                    }
                }
            }
            return new CachedResponse.Builder(key, status, names.toArray(String[]::new),
                    values.toArray(String[]::new), now, now + ttlMs, now + ttlMs + swrMs,
                    tags.toArray(String[]::new));
        }

        @Override
        public void onChunk(Buffer chunk) {
            if (!active) {
                return;
            }
            if (body.length() + chunk.length() > policy.maxBody()) {
                active = false;
                body = null;
                policy.cache().complete(key, exchange, null);
                return;
            }
            body.appendBuffer(chunk);
        }

        @Override
        public void onEnd() {
            if (!active) {
                return;
            }
            active = false;
            CachedResponse r = head.build(body);
            policy.cache().put(r);
            if (policy.l2()) {
                policy.cache().l2Put(r, r.staleUntilMs() - r.storedAtMs(), policy.l2TimeoutMs());
            }
            policy.cache().complete(key, exchange, r);
        }

        @Override
        public void onAbort() {
            active = false;
            body = null;
            policy.cache().complete(key, exchange, null);
        }
    }
}
