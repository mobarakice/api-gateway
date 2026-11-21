package apigw.plugins.ratelimit;

import apigw.core.auth.Principal;
import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.RequestPlugin;
import apigw.model.ConfigError;
import apigw.plugins.support.Configs;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Context;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code rate-limit} (ADR 0018). Config:
 * <pre>{"name": "default", "algorithm": "token-bucket" | "fixed-window" | "sliding-window",
 *  "limit": 100, "windowSeconds": 60, "burst": 150,
 *  "key": ["consumer"] | ["ip"] | ["credential"] | ["tenant"] | ["route"] | ["header:x-client"] (combinable),
 *  "mode": "local" | "global",
 *  "global": {"failMode": "open" | "closed", "timeoutMs": 25, "prefetch": 10, "reserveMs": 1000},
 *  "headers": true}</pre>
 * Over the limit: 429 with {@code Retry-After}. With {@code headers}, every response carries
 * {@code RateLimit-Policy} and {@code RateLimit} (draft-ietf-httpapi-ratelimit-headers).
 *
 * <p>A key part that is unknown for a request (no consumer yet, header absent) falls back to the client
 * IP, so anonymous traffic is still limited. One instance is shared by every route with the same config at
 * the same scope: include {@code route} in the key for per-route quotas.
 */
public final class RateLimitPluginFactory implements PluginFactory {

    @Override
    public String name() {
        return "rate-limit";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<Phase> phases() {
        return Set.of(Phase.ACCESS);
    }

    /** After authentication (2100/2050) and authorization (1000), so consumer keys are known. */
    @Override
    public int priority() {
        return 900;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/rate-limit.json";
    }

    @Override
    public Plugin create(JsonNode config, PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Configs.known(config, "", Set.of("name", "algorithm", "limit", "windowSeconds", "burst", "key", "mode",
                "global", "headers"), errors);
        String name = Configs.string(config, "", "name", "default", errors);
        if (!name.matches("^[a-z0-9_-]{1,32}$")) {
            errors.add(new ConfigError("name", "must match [a-z0-9_-]{1,32}"));
        }
        Algorithm algorithm = switch (Configs.string(config, "", "algorithm", "token-bucket", errors)) {
            case "token-bucket" -> Algorithm.TOKEN_BUCKET;
            case "fixed-window" -> Algorithm.FIXED_WINDOW;
            case "sliding-window" -> Algorithm.SLIDING_WINDOW;
            default -> {
                errors.add(new ConfigError("algorithm", "must be token-bucket, fixed-window or sliding-window"));
                yield Algorithm.TOKEN_BUCKET;
            }
        };
        int limit = Configs.integer(config, "", "limit", 0, 1, 100_000_000, errors);
        if (limit == 0 && !config.has("limit")) {
            errors.add(new ConfigError("limit", "is required"));
        }
        int window = Configs.integer(config, "", "windowSeconds", 1, 1, 86_400, errors);
        int burst = Configs.integer(config, "", "burst", Math.max(1, limit), 1, 100_000_000, errors);
        if (config.has("burst") && algorithm != Algorithm.TOKEN_BUCKET) {
            errors.add(new ConfigError("burst", "applies to token-bucket only"));
        }
        List<String> keyParts = Configs.strings(config, "", "key", errors);
        if (keyParts.isEmpty()) {
            keyParts = List.of("ip");
        }
        List<KeyPart> parts = new ArrayList<>();
        for (int i = 0; i < keyParts.size(); i++) {
            String k = keyParts.get(i);
            KeyPart part = switch (k) {
                case "ip" -> new KeyPart(KeyPart.IP, null);
                case "consumer" -> new KeyPart(KeyPart.CONSUMER, null);
                case "credential" -> new KeyPart(KeyPart.CREDENTIAL, null);
                case "tenant" -> new KeyPart(KeyPart.TENANT, null);
                case "route" -> new KeyPart(KeyPart.ROUTE, null);
                default -> {
                    String h = k.startsWith("header:") ? k.substring(7).toLowerCase(Locale.ROOT) : "";
                    if (h.isEmpty() || !Configs.token(h)) {
                        errors.add(new ConfigError("key[" + i + "]",
                                "must be ip, consumer, credential, tenant, route or header:<name>"));
                        yield null;
                    }
                    yield new KeyPart(KeyPart.HEADER, h);
                }
            };
            if (part != null) {
                parts.add(part);
            }
        }
        String mode = Configs.string(config, "", "mode", "local", errors);
        if (!mode.equals("local") && !mode.equals("global")) {
            errors.add(new ConfigError("mode", "must be local or global"));
        }
        JsonNode g = config.get("global");
        if (g != null && !mode.equals("global")) {
            errors.add(new ConfigError("global", "only with mode global"));
        }
        boolean failClosed = false;
        int timeoutMs = 25;
        int prefetch = 10;
        int reserveMs = 1000;
        if (g != null) {
            Configs.known(g, "global", Set.of("failMode", "timeoutMs", "prefetch", "reserveMs"), errors);
            String fm = Configs.string(g, "global", "failMode", "open", errors);
            if (!fm.equals("open") && !fm.equals("closed")) {
                errors.add(new ConfigError("global.failMode", "must be open or closed"));
            }
            failClosed = fm.equals("closed");
            timeoutMs = Configs.integer(g, "global", "timeoutMs", 25, 1, 5000, errors);
            prefetch = Configs.integer(g, "global", "prefetch", 10, 1, 10_000, errors);
            reserveMs = Configs.integer(g, "global", "reserveMs", 1000, 10, 60_000, errors);
        }
        if (mode.equals("global") && prefetch > Math.max(1, limit)) {
            errors.add(new ConfigError("global.prefetch", "must not exceed limit"));
        }
        boolean headers = Configs.bool(config, "", "headers", true, errors);
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        RateLimitStore store = context.services().find(RateLimitStore.class).orElse(null);
        if (store == null) {
            throw new PluginConfigException("", "rate-limit store not available in this process");
        }
        Algorithm.Limits limits = new Algorithm.Limits(limit, window * 1000L, algorithm == Algorithm.TOKEN_BUCKET
                ? burst : limit);
        String id = name + ":" + digest(config.toString());
        String policy = "\"" + name + "\";q=" + limit + ";w=" + window;
        return new RateLimit(id, name, algorithm, limits, parts.toArray(KeyPart[]::new), mode.equals("global"),
                failClosed, timeoutMs, prefetch, reserveMs, headers, policy, store);
    }

    private static String digest(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One element of the limit key. */
    record KeyPart(int kind, String header) {
        static final int IP = 0;
        static final int CONSUMER = 1;
        static final int CREDENTIAL = 2;
        static final int TENANT = 3;
        static final int ROUTE = 4;
        static final int HEADER = 5;
    }

    /** How long a key skips Redis after a failed fetch. */
    static final long DEGRADED_MS = 1000;

    /** Compiled limit. Immutable; per-key state lives in the {@link RateLimitStore}. */
    record RateLimit(String id, String name, Algorithm algorithm, Algorithm.Limits limits, KeyPart[] parts,
            boolean global, boolean failClosed, int timeoutMs, int prefetch, int reserveMs, boolean headers,
            String policy, RateLimitStore store) implements RequestPlugin {

        String key(Exchange exchange) {
            StringBuilder b = new StringBuilder(id.length() + 48).append(id);
            Principal p = exchange.principal();
            for (KeyPart part : parts) {
                b.append('|');
                String v = switch (part.kind()) {
                    case KeyPart.CONSUMER -> p == null || p.consumer() == null ? null : p.consumer().id();
                    case KeyPart.CREDENTIAL -> p == null ? null : p.credentialId();
                    case KeyPart.TENANT -> p == null || p.tenantId() == null ? exchange.route().tenantId()
                            : p.tenantId();
                    case KeyPart.ROUTE -> exchange.route().id();
                    case KeyPart.HEADER -> exchange.request().getHeader(part.header());
                    default -> exchange.clientIp();
                };
                if (v == null) {
                    b.append("ip=").append(exchange.clientIp());
                } else {
                    // Bound attacker-chosen header keys; '|' cannot split a key because each part is prefixed.
                    b.append(part.kind()).append('=').append(v.length() > 256 ? v.substring(0, 256) : v);
                }
            }
            return b.toString();
        }

        @Override
        public Outcome onRequest(Exchange exchange) {
            String key = key(exchange);
            long now = System.currentTimeMillis();
            if (!global) {
                return decideLocal(exchange, key, now);
            }
            return decideGlobal(exchange, key, now, true);
        }

        private Outcome decideLocal(Exchange exchange, String key, long now) {
            long r = store.takeLocal(key, algorithm, limits, now);
            if (r < 0) {
                return deny(exchange, -r, store.resetLocal(key, algorithm, limits, now));
            }
            if (headers) {
                addHeaders(exchange, r, store.resetLocal(key, algorithm, limits, now));
            }
            return Outcome.CONTINUE;
        }

        /**
         * Takes from the local reserve; when it is empty, fetches {@code prefetch} units from Redis (one
         * fetch per key at a time; concurrent requests wait for it).
         */
        private Outcome decideGlobal(Exchange exchange, String key, long now, boolean mayFetch) {
            RateLimitStore.Reserve res = store.reserve(key);
            long retryMs;
            long resetMs;
            boolean degraded;
            synchronized (res) {
                degraded = now < res.degradedUntilMs;
            }
            if (degraded || !store.redisReady()) {
                return degradedDecision(exchange, key, now);
            }
            synchronized (res) {
                if (res.tokens > 0 && now < res.validUntilMs) {
                    res.tokens--;
                    long remaining = Math.max(0, res.remainingHint + res.tokens);
                    if (headers) {
                        addHeaders(exchange, remaining, Math.max(0, res.resetMs - (now - (res.validUntilMs
                                - reserveMs))));
                    }
                    return Outcome.CONTINUE;
                }
                if (now < res.deniedUntilMs || !mayFetch) {
                    retryMs = Math.max(1, res.deniedUntilMs - now);
                    resetMs = res.resetMs;
                } else {
                    Context ctx = Vertx.currentContext();
                    Runnable again = () -> ctx.runOnContext(v -> {
                        if (decideGlobal(exchange, key, System.currentTimeMillis(), false) == Outcome.CONTINUE) {
                            exchange.resume();
                        }
                    });
                    if (res.fetching) {
                        if (res.waiters == null) {
                            res.waiters = new ArrayList<>();
                        }
                        if (res.waiters.size() >= 10_000) {
                            retryMs = 1000;
                            resetMs = 1000;
                        } else {
                            res.waiters.add(again);
                            return Outcome.PENDING;
                        }
                    } else {
                        res.fetching = true;
                        startFetch(exchange, key, res, again);
                        return Outcome.PENDING;
                    }
                }
            }
            return deny(exchange, retryMs, resetMs);
        }

        private void startFetch(Exchange exchange, String key, RateLimitStore.Reserve res, Runnable self) {
            String redisKey = "gwrl:{" + key + "}";
            store.fetch(redisKey, algorithm, limits, prefetch, timeoutMs).onComplete(ar -> {
                List<Runnable> waiters;
                boolean failed = ar.failed();
                synchronized (res) {
                    res.fetching = false;
                    waiters = res.waiters == null ? List.of() : List.copyOf(res.waiters);
                    res.waiters = null;
                    if (failed) {
                        // Do not retry Redis for this key for a moment: a dead Redis must not add latency.
                        res.degradedUntilMs = System.currentTimeMillis() + DEGRADED_MS;
                    } else {
                        long[] r = ar.result();
                        long now = System.currentTimeMillis();
                        res.tokens = r[0];
                        res.remainingHint = r[1];
                        res.resetMs = r[3];
                        res.validUntilMs = now + reserveMs;
                        res.deniedUntilMs = r[0] == 0 ? now + Math.min(r[2], 60_000) : 0;
                    }
                }
                if (failed) {
                    store.warnRedis(id, ar.cause(), failClosed ? "closed" : "open");
                }
                // Everyone re-decides on their own loop; after a failure they see the degraded window.
                self.run();
                for (Runnable w : waiters) {
                    w.run();
                }
            });
        }

        /** Redis unavailable: fail-open uses this node's local limiter, fail-closed refuses with 503. */
        private Outcome degradedDecision(Exchange exchange, String key, long now) {
            if (failClosed) {
                MultiMap h = MultiMap.caseInsensitiveMultiMap().add("retry-after", "1");
                return exchange.respond(503, "Service Unavailable", h);
            }
            return decideLocal(exchange, key, now);
        }

        private Outcome deny(Exchange exchange, long retryMs, long resetMs) {
            long retry = Math.max(1, (retryMs + 999) / 1000);
            MultiMap h = MultiMap.caseInsensitiveMultiMap().add("retry-after", Long.toString(retry));
            if (headers) {
                h.add("ratelimit-policy", policy);
                h.add("ratelimit", "\"" + name + "\";r=0;t=" + retry);
            }
            return exchange.respond(429, "Too Many Requests", h);
        }

        private void addHeaders(Exchange exchange, long remaining, long resetMs) {
            exchange.addResponseHeader("ratelimit-policy", policy);
            exchange.addResponseHeader("ratelimit", "\"" + name + "\";r=" + remaining + ";t="
                    + Math.max(0, (resetMs + 999) / 1000));
        }
    }
}
