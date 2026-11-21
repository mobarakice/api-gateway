# 0019 Response cache

- Status: Accepted (2026-11-21, M5)

## Context
Spec §9 requires:
- Caffeine L1 and optional Redis L2;
- keys from tenant, route, method, normalised path, query and Vary headers;
- honouring Cache-Control, miss coalescing, stale-while-revalidate, and invalidation by route and tag
  through an etcd key;
- never caching Set-Cookie, authenticated or private responses, unless a route policy allows it and
  the key isolates the principal.

Invariant 9: bodies stream; buffer only when a plugin needs it, within a cap.

## Decision
- `cache` plugin: BEFORE_PROXY phase, priority 500, plus a LOG hook. It stores GET responses; HEAD is
  served from the GET entry.
- New SPI pieces, available to all plugins:
  - `respondWith(status, headers, body)`: a plugin-made response that still runs the header filters;
  - `tapResponse(ResponseTap)`: observes the upstream head and body while it streams; the stored
    copy is complete when the upstream body ends;
  - `addResponseHeader`;
  - gateway error responses now also pass through the header filters, which CORS needs.
- Key: tenant, route ID, path, the sorted query (or `ignore`, or an include list), the configured
  `vary` request headers, and with `cacheAuthenticated` also the principal (tenant, consumer, subject).
- Freshness:
  - `s-maxage`, else `max-age`, minus `Age`; otherwise `ttlSeconds` (default 0 = explicit responses
    only); capped at `maxTtlSeconds`.
  - SWR comes from `stale-while-revalidate` or the config value.
  - A request with `no-cache` or `max-age=0` skips lookup; `no-store` bypasses.
- Never stored:
  - responses with `Set-Cookie`, `no-store` or `no-cache`, `Vary: *`, or a Vary header outside
    `vary`;
  - statuses not in `statuses`;
  - bodies over `maxBodyBytes`.
  - Without `cacheAuthenticated`, also `private` responses and requests with a principal or
    `Authorization`, which bypass the cache.
- Miss coalescing:
  - a process-wide in-flight table: the first miss leads, the others wait (up to 10,000 per key) and
    are served the stored entry;
  - if the leader's response is not cacheable, the waiters are released at its response head and
    fetch in parallel;
  - waiters wait at most `lockTimeoutMs`;
  - the leader's LOG hook releases them if it never reaches the proxy.
- Stale-while-revalidate: **in-band revalidation**.
  - The first request after expiry refreshes the entry and waits for the upstream itself.
  - Requests during the refresh get the stale entry at once (`x-cache: STALE`).
  - There is no detached background fetch. That would need an upstream call outside an exchange,
    duplicating routing, SSRF and resilience logic; this may be revisited.
- Purge:
  - `policies/cache-purge/{id}` = `{routeId | tag, issuedAt}`, written by the control plane and
    compiled into the snapshot as the latest time per route and per tag;
  - lookups treat older entries as misses and drop them (lazy: no scan, no fan-out);
  - tags come from the `tagHeader` (default `cache-tag`) of the response.
- L2 (`l2: true`):
  - Redis `GET`/`SET PX` of `gwc:` + SHA-256(key), with the full key stored inside and checked;
  - always fail-open: timeouts and errors are misses, and writes are fire-and-forget.
- `x-cache: HIT|MISS|STALE|EXPIRED|BYPASS` unless `statusHeader: false`.

## Performance and memory
- L1 is one byte-weighted Caffeine (`GW_CACHE_MAX_BYTES`, default 64 MiB) shared by all loops and
  routes. Leaders buffer at most `maxBodyBytes`.
- JMH, dev host only: a hit costs about 159 ns and 767 B/op (key string, header map). Measured with a
  proxy-based request stand-in, so this is an upper bound.
- Routes without the plugin pay nothing (invariant 8). Uncached methods return at the first check.

## Native Image
- Caffeine (see ADR 0018 for metadata). The L2 codec is hand-written binary; no serialization
  framework.

## Operational impact
- One cache per gateway process; L2 shares entries across nodes. Entries are not encrypted at rest in
  Redis: keep authenticated caching off for sensitive data, or run Redis with TLS and ACLs.
- Purges are best-effort lazy: memory is reclaimed on access or by eviction.
