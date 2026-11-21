# 0018 Rate limiting and the Redis client

- Status: Accepted (2026-11-21, M5)

## Context
Spec §9 requires:
- token bucket (default), fixed window and sliding window, with keys by IP, API key, consumer,
  tenant, route and combinations, burst capacity, and 429 with `Retry-After` and `RateLimit` headers;
- local mode (default) and global mode through Redis with local token pre-fetch;
- documented accuracy versus cost, and documented Redis-down behaviour.

Invariant 2: Redis is the only network call allowed in the request path, opt-in, with a timeout and a
fail-open or fail-closed mode.

## Options
- Algorithms: **token bucket** (smooth, allows bursts up to `burst`); **fixed window** (cheapest, but
  allows up to 2× at a boundary); **sliding window counter** (previous window weighted by overlap; no
  boundary burst, with about 1–2% approximation error). A sliding *log* is exact but O(requests) of
  memory per key, so it was rejected.
- Redis client: (a) `vertx-redis-client` 5.2.3. It has no native-image metadata, no entry in the
  GraalVM reachability repository (checked 2026-11-21), and a large codec surface. (b) Lettuce or
  Jedis: blocking, Netty-duplicating or reflection-heavy. (c) **An in-house minimal RESP2 client on
  Vert.x `NetClient`**, the same reasoning as the etcd client (ADR 0009).

## Decision
- `rate-limit` plugin: ACCESS phase, priority 900, so it runs after authentication and the consumer
  is known.
  - Config: `algorithm`, `limit`, `windowSeconds`, `burst` (token bucket only), `key` (ip, consumer,
    credential, tenant, route, `header:<name>`), `mode`, `global{failMode, timeoutMs, prefetch,
    reserveMs}`, `headers`.
  - A key part that is unknown for a request falls back to the client IP.
  - Instances are shared by routes with the same config: put `route` in the key for per-route quotas.
- Local state (`RateLimitStore`):
  - a process-wide Caffeine cache bounded by `GW_RATELIMIT_MAX_KEYS` (default 200,000), keyed by
    limiter ID (name + config digest, so state survives unchanged recompiles) plus key;
  - each state has a tiny monitor-guarded critical section with no I/O, shared by all loops, so the
    local limit is exact per process;
  - eviction can only reset a key, which admits more, never less.
- Global mode:
  - Lua scripts take up to `prefetch` units atomically, using Redis `TIME` so all nodes agree on
    windows. They return `{granted, remaining, retryAfterMs, resetMs}`, through EVALSHA with an EVAL
    fallback on NOSCRIPT.
  - Each node keeps a per-key reserve valid for `reserveMs`, and only one fetch per key is in flight
    (other requests wait for it).
  - A zero grant is cached as "denied until retry-after", so keys over their limit do not hit Redis.
- Accuracy vs cost:
  - Requests ≤ limit always hold cluster-wide; units are granted atomically.
  - Redis calls per key ≈ requests ÷ `prefetch`.
  - Unused reserved units expire with the reserve (≤ `prefetch` × nodes per `reserveMs`), so a busy
    key can see early 429s on some nodes.
  - `prefetch: 1` is exact, at one round trip per request.
- Redis down:
  - Commands time out after `timeoutMs`, and fail at once while disconnected.
  - **fail-open** (default): the node's local limiter with the same algorithm and limit applies,
    which is per node, so the cluster may admit up to N× the limit.
  - **fail-closed**: 503 with `Retry-After: 1`.
  - After a failure a key skips Redis for 1 s, so a dead Redis adds no latency.
  - The client reconnects with exponential backoff and full jitter (100 ms to 5 s).
- Headers:
  - `RateLimit-Policy: "<name>";q=<limit>;w=<window>` and `RateLimit: "<name>";r=<remaining>;t=<s>`
    (draft-ietf-httpapi-ratelimit-headers);
  - 429 adds `Retry-After`. Error bodies stay generic.
- Redis client (`gateway-plugins/redis`):
  - one pipelined RESP2 connection per process;
  - TLS (`rediss://`, CA file, hostname verification) and ACL AUTH (user plus password file; never
    logged; credentials in the URL are refused);
  - per-command timeouts, at most 10,000 queued commands, and a bounded parser (bulk ≤ 64 MiB, arrays
    ≤ 1M items, depth ≤ 8).
  - Standalone Redis only (no Cluster or Sentinel).

## Performance and memory
- JMH, dev host only (macOS arm64; not the reference host): a local token-bucket check costs 84.9 ns
  and 440 B/op, mostly the key string and the RateLimit headers.
- Memory is bounded by the store size.
- Global mode adds about one Redis round trip per `prefetch` requests per key per node.

## Native Image
- Caffeine 3.2.4 uses the GraalVM reachability repository metadata, vendored under
  `gateway-plugins/META-INF/native-image/com.github.ben-manes.caffeine/`. The repository's entries are
  gated on code paths our caches do not reach, so a reviewed, unconditional file
  (`META-INF/native-image/apigw/gateway-plugins/`) registers the four generated classes we use: SSMS,
  SSMW, PSMS, PSMW (size- and weight-bounded caches, no expiry).
- The Redis client uses no reflection.

## Operational impact
- Env vars: `GW_REDIS_URL`, `GW_REDIS_USER`, `GW_REDIS_PASSWORD_FILE`, `GW_REDIS_CA`,
  `GW_REDIS_TIMEOUT_MS`, `GW_REDIS_CONNECT_TIMEOUT_MS` and `GW_RATELIMIT_MAX_KEYS`.
- Without Redis, global limits use their fail mode permanently (logged).
- Redis ACL needed: `+ping +auth +select +get +set +eval +evalsha +hmget +hset +pexpire +incrby +time`
  on `~gw*`.
