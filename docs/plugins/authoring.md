# Writing a plugin

Plugins are compiled in (CLAUDE.md invariant 7). A plugin consists of:

| Part | Where |
|---|---|
| `PluginFactory`: name, version, phases, priority, schema resource, `create(JsonNode, PluginContext)`, `authenticates()` | `gateway-plugins/src/main/java/apigw/plugins/<name>/` |
| JSON Schema 2020-12 of its `config` | `config-model/src/main/resources/schemas/plugins/<name>.json` |
| Registration | one line in `apigw.plugins.BuiltinPlugins` |
| Tests | unit tests next to the code, a JVM + native integration test, a JMH benchmark if it runs on every request |

## Lifecycle
1. The control plane and the gateway validate `config` against the same schema file.
2. When a snapshot compiles, the factory's `create(config, context)` validates again in code and returns an
   **immutable, thread-safe** instance. Throw `PluginConfigException` with every error; paths are
   relative to `config` (`request.set.Host`). The compiler prefixes the scope path.
3. The instance is shared by every route with the same config at that scope, on every event loop.
   Never block, never do I/O except through async clients with timeouts (and only where an ADR allows
   it), never keep per-request state in fields.
4. `PluginContext` gives the factory the snapshot's authz policies (`authzPolicies()`), long-lived
   process services (`services().find(Type.class)`, e.g. the OIDC key-set cache), and the clock. A
   factory that uses a shared resource calls `context.use(resource)`; after each publish, services
   implementing `PluginServices.SnapshotAware` release resources no published snapshot uses.
5. A factory whose plugin establishes identity returns `authenticates() == true` and calls
   `exchange.authenticate(principal)`. That sets the identity headers, enforces the route's tenant,
   and switches the rest of the chain to the consumer's overrides. It must skip work when
   `exchange.principal()` is already set, so that several auth plugins on one route mean "any of".

## Phases and interfaces

| Phase | Interface | Runs |
|---|---|---|
| `REWRITE` | `RequestPlugin` | first; path/header rewrites |
| `ACCESS` | `RequestPlugin` | IP rules, authn, authz, rate limits |
| `BEFORE_PROXY` | `RequestPlugin` | request transforms, cache lookup |
| `HEADER_FILTER` | `ResponseHeadersPlugin` | upstream response headers known, body not yet sent |
| `LOG` | `LogPlugin` | once, after the exchange completed or failed |

An instance joins a phase only if the factory lists the phase **and** the instance implements its
interface. Return a narrower instance for a narrower config so unconfigured phases cost nothing.

`RequestPlugin.onRequest` returns:
- `CONTINUE`: next plugin, then proxy.
- `STOP`: you responded (use `exchange.respond(status, title)`: generic RFC 9457 body, request ID, no
  internal detail).
- `PENDING`: you started async work; call `exchange.resume()` or `respond(...)` later on the same
  event loop.

Other exchange methods (M5):
- `respond(status, title, headers)`: problem response with extra headers (e.g. `Retry-After`).
- `respondWith(status, headers, body)`: a plugin-made response (cache hit). Header filters still run.
- `addResponseHeader(name, value)`: added to whatever response the client gets, including errors.
- `tapResponse(ResponseTap)`: observe the upstream head and body as they stream; `onEnd` runs before
  the LOG phase. Buffer only within a cap.
- `limitRequestBody(maxBytes)`: 413 when the streamed body exceeds the cap.
- Gateway error responses also pass through `HEADER_FILTER` plugins, so CORS headers reach the browser
  on 401/429.

A plugin that throws fails only its request (500) in request phases, or is skipped in late phases; the
error is logged with route ID and request ID.

## Precedence and order
Scopes: consumer > route > service > global. A narrower scope replaces a wider one by plugin name; a
narrower entry with `"enabled": false` removes the plugin for that route. Within a phase, higher
`priority()` runs first; ties break by name.

Consumer scope (ADR 0016) is applied at run time. Once a consumer authenticates, every later chain
slot uses the consumer's instance of that plugin, or skips it if the consumer disabled it. A consumer
can only override plugins that the route's merged chain already contains, and cannot override
authenticating plugins.

Any scope may reference a shared config: `{"name": "headers", "configRef": "std-headers"}` refers to
`plugin-configs/std-headers` (`{plugin, config}`). `config` and `configRef` are mutually exclusive.

| Plugin | Phases | Priority | Authenticates |
|---|---|---|---|
| `cors` | ACCESS, HEADER_FILTER | 4000 | no |
| `ip-restriction` | ACCESS | 3000 | no |
| `request-size-limit` | ACCESS | 2900 | no |
| `jwt` | ACCESS | 2100 | yes |
| `key-auth` | ACCESS | 2050 | yes |
| `authz` | ACCESS | 1000 | no |
| `rate-limit` | ACCESS | 900 | no |
| `cache` | BEFORE_PROXY, LOG | 500 | no |
| `request-transform` | BEFORE_PROXY | 100 | no |
| `headers` | BEFORE_PROXY, HEADER_FILTER | 0 | no |

## Built-in: `headers`
```json
{"name": "headers", "config": {
  "request":  {"remove": ["x-internal"], "set": {"x-env": "prod"}, "add": {"x-tag": "a"}},
  "response": {"remove": ["server"], "set": {"cache-control": "no-store"}}}}
```
Per side: remove, then set, then add. Names are case-insensitive tokens; values are visible ASCII,
space or tab. `Host`, `Content-Length` and hop-by-hop headers cannot be edited. Request edits apply to
the headers sent upstream (after forwarding headers are computed), so a plugin can override
`x-forwarded-proto` deliberately but cannot change framing.

## Built-in: `jwt` (ADR 0015)
```json
{"name": "jwt", "config": {
  "issuers": [{"issuer": "https://idp.example.com", "audiences": ["orders-api"],
               "algorithms": ["RS256", "ES256"], "discovery": true,
               "rolesClaim": "realm_access.roles", "tenantClaim": "tid", "consumerClaim": "azp"}],
  "clockSkewSeconds": 60, "optional": false, "forwardToken": true}}
```
Each issuer needs exactly one key source: `discovery`, `jwksUri` (https), inline `jwks`, or `secretFile`
(HS* only, never inline). `none` cannot be configured, and HS* cannot be mixed with public-key algorithms.
Failures are `401` with `WWW-Authenticate: Bearer realm="gateway", error="invalid_token"`. With
`optional`, a request *without* a token continues unauthenticated; an invalid token is still 401.

## Built-in: `key-auth`
```json
{"name": "key-auth", "config": {"header": "x-api-key", "hideCredentials": true}}
```
Clients send `<keyId>.<secret>`. Credentials live at `credentials/{consumerId}/{id}`:
`{"type": "api-key", "keyId": "...", "hash": "sha256$<salt>$<digest>"}`. Generate them with
`ApiKeyHash.newSecret()` and `ApiKeyHash.hash(secret)` (the control plane does this in M6). Only the
hash is stored.

## Built-in: `authz` (ADR 0016)
```json
{"name": "authz", "config": {"policies": ["orders-readers", "no-delete"]}}
```
Policies are `policies/authz/{id}`:
```json
{"effect": "allow", "routes": ["get-order"], "methods": ["GET"],
 "subjects": {"roles": ["orders:read"]},
 "condition": {"op": "eq", "attr": "principal.tenant", "ref": "route.tenant"}}
```
Any matching deny wins. Without a matching allow, the result is 403 (401 if unauthenticated). Place
`authz` after an authenticating plugin; it runs at a lower priority in the same phase.

## Built-in: `ip-restriction`
`{"allow": ["10.0.0.0/8"], "deny": ["10.6.6.0/24"], "status": 403}`. Uses the real client IP
(`X-Forwarded-For` honoured only from `settings.trustedProxies`). Deny wins; with an `allow` list,
anything outside it is refused.

## Built-in: `request-size-limit`
`{"maxBytes": 1048576}`: 413 on a larger `Content-Length` before proxying, or as soon as the
streamed body passes the cap.

## Built-in: `cors`
`{"allowOrigins": ["https://app.example.com", "https://*.example.com"], "allowMethods": ["GET",
"HEAD", "POST"], "allowHeaders": [], "exposeHeaders": [], "allowCredentials": false,
"maxAgeSeconds": 600}`. Preflights are answered by the gateway (204, never proxied). `https://*.example.com`
allows any subdomain (not the apex); `*` cannot be combined with credentials. Allowed origins are
echoed, with `Vary: Origin` (except for `*`).

## Built-in: `request-transform`
`{"path": {"stripPrefix": "/api", "addPrefix": "/v2"} | {"template": "/items/{id}"}, "query":
{"remove": [], "rename": {}, "set": {}, "add": {}}, "headers": {"rename": {}}}`. Templates use
route path params. Header add/remove stays in `headers`.

## Built-in: `rate-limit` (ADR 0018)
`{"name": "default", "algorithm": "token-bucket" | "fixed-window" | "sliding-window", "limit": 100,
"windowSeconds": 1, "burst": 100, "key": ["ip"], "mode": "local" | "global", "global": {"failMode":
"open" | "closed", "timeoutMs": 25, "prefetch": 10, "reserveMs": 1000}, "headers": true}`.
- `key` parts: `ip`, `consumer`, `credential`, `tenant`, `route`, `header:<name>`; combine them for
  composite keys. A part unknown for a request falls back to the client IP.
- 429 with `Retry-After`; `RateLimit-Policy` and `RateLimit` headers when `headers` is on.
- Global mode needs `GW_REDIS_URL`. Redis down: `open` = this node's local limit, `closed` = 503.

## Built-in: `cache` (ADR 0019)
`{"ttlSeconds": 0, "maxTtlSeconds": 3600, "staleWhileRevalidateSeconds": 0, "statuses": [200],
"vary": ["accept"], "query": "all" | "ignore" | ["page"], "maxBodyBytes": 1048576,
"cacheAuthenticated": false, "tagHeader": "cache-tag", "l2": false, "l2TimeoutMs": 20,
"lockTimeoutMs": 5000, "statusHeader": true}`.
- GET only (HEAD served from GET entries). Never stores `Set-Cookie`, `no-store`, `no-cache`,
  `Vary: *`, or Vary headers not listed in `vary`.
- Authenticated or `private` responses only with `cacheAuthenticated`, which adds the principal to the
  key.
- Concurrent misses coalesce onto one upstream request. `x-cache`: HIT, MISS, STALE, EXPIRED, BYPASS.
- Purge with `policies/cache-purge/{id}` by route or by tag (from `tagHeader`).
