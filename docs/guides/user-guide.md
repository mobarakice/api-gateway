# User guide: configuring the gateway

This guide covers signing in and configuring upstreams, services, routes, plugins and consumers. Every task has
two forms:

- **Console**: what to click in the admin console.
- **API**: the same call to the Admin API (`/admin/v1`, OpenAPI in [`docs/api/admin-v1.yaml`](../api/admin-v1.yaml)).

The examples use the local Docker stack ([deploy-docker.md](deploy-docker.md)) and its `echo` upstream. On
Kubernetes, replace the URLs with your own.

## 1. Concepts

```
client ──► route ──► service ──► upstream ──► targets (host:port)
            │          │
            └ plugins ─┘   + global plugins, + consumer overrides
```

| Object | What it is |
|---|---|
| **Upstream** | A pool of targets with a load balancer and optional health checks, circuit breaker and TLS to targets |
| **Service** | Points at one upstream. Holds timeouts, retries and plugins shared by its routes |
| **Route** | Matches requests by host, method, path, headers and query, and sends them to a service. Most plugins are attached here |
| **Plugin** | Authentication, authorization, traffic control or transformation. The config is checked against the plugin's JSON Schema |
| **Global plugin** | A plugin that runs on every route |
| **Plugin config** | A shared, named plugin configuration that routes reference with `configRef` |
| **Consumer** | An API client: username, roles, attributes, credentials (API keys) and plugin overrides |
| **Authorization policy** | An allow/deny rule evaluated by the `authz` plugin |
| **Settings** | Global options: `upstreamAllowCidrs`, `trustedProxies`, `viaName`, `maxRequestBodyBytes` |

When a plugin is configured at several levels, the most specific one wins: consumer > route > service > global.
A consumer can only override plugins that are already in the route's chain.

### Change sets

Nothing you edit reaches the gateways right away. Every edit is **staged** in a change set (a draft). When you
**publish** it, the whole change set is applied in one etcd transaction, so gateways see either all of it or
none of it. Every publish is recorded in **History** and can be rolled back.

```
create change set → stage edits → validate → review diff → publish → (roll back from History)
```

## 2. Sign in

Open the console (http://localhost:8280 locally) and click **Sign in**. You are redirected to the identity
provider (Keycloak locally). The four local users and their passwords are in
`deployment/docker/.local/e2e/users.json`.

| Role | Can |
|---|---|
| `viewer` | Read everything except the audit log |
| `editor` | Create change sets, stage edits, issue API keys, validate |
| `publisher` | Everything an editor can, plus publish and roll back |
| `admin` | Everything a publisher can, plus settings, certificates and audit |

- Your role is shown next to your name in the header.
- Tokens are kept in memory only. Reloading the page signs you in again, usually without a prompt.
- **Sign out** also ends the identity provider session.
- In production, roles come from your IdP's roles claim (`CP_OIDC_ROLES_CLAIM`).

### Getting a token for the API

The Admin API takes `Authorization: Bearer <access token>`, and the token must carry the right roles and audience.

- **Local Docker stack:** sign in to the console. Then, in the browser's developer tools (**Network** tab), copy
  the `Authorization` header from any request to `localhost:9443`. Dev tokens are valid for 5 minutes. The dev
  Keycloak client allows the browser flow only.
- **kind / dev overlay:** run `deployment/kubernetes/scripts/mint-token.sh <role> apigw-dev`.
- **Production:** use a token from your IdP for a user or service account that has the required role.

Set up a helper for the API examples:

```bash
export TOKEN='eyJ...'                                   # access token
CA=deployment/docker/.local/certs/ca.pem                # dev CA (omit --cacert with a public CA)
API=https://localhost:9443/admin/v1
api() { curl -sS --cacert "$CA" -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' "$@"; }
api $API/me                                             # who am I, which role
```

## 3. The change-set workflow

### 3.1 Create a change set

**Console:** use the **Change set** picker in the header, then **New change set** and enter a title. The new
change set becomes active, and every **Stage …** button stages into it.

**API:**

```bash
CS=$(api -X POST $API/changesets -d '{"title":"First API"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
```

### 3.2 Stage edits

Every object is written as `{"spec": {...}}` to `/{collection}/{id}?changeset=$CS`. To change or delete an
object that already exists, send its current `ETag` in `If-Match`. Without it you get **428**. With a
stale one you get **412**.

```bash
ETAG=$(api -o /dev/null -D - $API/routes/users | awk 'tolower($1)=="etag:"{sub(/\r$/,"",$2);print $2}')
api -X PUT "$API/routes/users?changeset=$CS" -H "if-match: $ETAG" -d '{"spec": {...}}'
api -X DELETE "$API/routes/users?changeset=$CS" -H "if-match: $ETAG"
```

The console handles ETags for you.

### 3.3 Validate and review

**Console:** go to **Change sets**, open yours, and click **Validate**. Validation runs the gateway's own compiler
over *live config + your changes*. Errors appear on the matching field and in a summary at the top. The page also
shows the diff of every staged key.

**API:**

```bash
api -X POST $API/changesets/$CS/validate       # {"valid": true|false, "errors": [{"path": "routes/users.spec.path", ...}], "conflicts": [...]}
api $API/changesets/$CS/diff
```

### 3.4 Publish (publisher)

**Console:**

1. Click **Review and publish…**.
2. Read the full diff.
3. Tick **I have reviewed these N changes**.
4. Click **Publish**.

**API:**

```bash
api -X POST $API/changesets/$CS/publish        # {"revision": ..., "seq": ...}
```

Every gateway applies the change within about a second. **Gateways** (the console home page, or
`GET /admin/v1/gateways`) shows each gateway's applied revision and state. If a gateway is behind or has a
`configError`, it shows up there.

## 4. Walkthrough: your first API

This example puts the `echo` container behind `/users`, protects the route with API keys and adds a rate limit.
Stage every step into one change set.

### 4.1 Allow the upstream network (admin)

The gateway refuses upstream targets in private, loopback and metadata ranges unless a CIDR in
`settings.upstreamAllowCidrs` covers them. This is the SSRF guard
([ADR 0015](../adr/0015-authentication-and-ssrf.md)). The Compose network is `10.250.0.0/24`.

**Console:** go to **Settings** → **Upstream allow CIDRs**, enter `10.250.0.0/24`, and click **Stage settings**.

**API:**

```bash
api -X PUT "$API/settings?changeset=$CS" -d '{"spec":{"upstreamAllowCidrs":["10.250.0.0/24"]}}'
```

- Add `-H "if-match: …"` if settings already exist.
- On Kubernetes, use the cluster's pod and service CIDRs.
- Never use `0.0.0.0/0`.

### 4.2 Upstream

**Console:** go to **Upstreams** → **New upstream**, set ID `echo`, and click **Add target** (host `echo`, port `8080`).
Pick a **Balancer**, then click **Stage upstream**. Health checks, the circuit breaker, pool limits and TLS are
under **Other fields (JSON)**.

**API:**

```bash
api -X PUT "$API/upstreams/echo?changeset=$CS" -d '{"spec":{
  "targets": [{"host": "echo", "port": 8080, "weight": 1}],
  "balancer": "least-requests",
  "healthCheck": {"passive": {"consecutiveFailures": 5, "ejectionMs": 30000, "maxEjectionPercent": 50}}
}}'
```

- **Balancers:**
  - `round-robin` (the default);
  - `weighted-round-robin`;
  - `least-requests`;
  - `consistent-hash`, with `hashKey` set to `ip` or `header:<name>`.
- **TLS to the targets:** `scheme: https`. Add `caFile` for a private CA, and `clientCert` for mTLS.
- **All fields:** [configuration → Config document](../operations/configuration.md#config-document).

### 4.3 Service

**Console:** go to **Services** → **New service**, set ID `users`, set **Upstream** to `echo`, and click **Stage service**.

**API:**

```bash
api -X PUT "$API/services/users?changeset=$CS" -d '{"spec":{
  "upstreamId": "echo",
  "timeouts": {"connectMs": 2000, "firstByteMs": 5000},
  "retries": {"attempts": 1, "on": ["connect-failure", "reset", "timeout"]}
}}'
```

- Timeouts are inherited in this order: route, then service, then `settings.defaultTimeouts`.
- Requests that carry a body are retried only after a connect failure.

### 4.4 Route

**Console:** go to **Routes** → **New route** and set:

- ID: `users`
- **Path**: `/users`
- **Path match**: `prefix`
- **Service**: `users`

Optionally, set **Methods**, **Hosts**, **Priority**, **Header matches** and **Query matches**. Then click
**Stage route**.

**API:**

```bash
api -X PUT "$API/routes/users?changeset=$CS" -d '{"spec":{
  "path": "/users", "match": "prefix", "serviceId": "users",
  "methods": ["GET", "POST"], "hosts": []
}}'
```

| `match` | `path` example | Matches |
|---|---|---|
| `prefix` (default) | `/users` | `/users`, `/users/42`. It does **not** match `/users-old`, because prefixes are segment-bounded |
| `exact` | `/users` | `/users` only |
| `template` | `/users/{id}` | `/users/42`. Each `{name}` matches exactly one segment |

- Leaving `hosts` empty matches any host. `*.example.com` is allowed.
- Among equal matches, the higher `priority` wins.
- `preserveHost: true` forwards the client's `Host` header instead of the upstream's.

Validate and publish (§3.3–3.4), then test:

```bash
curl -i http://localhost:8080/users/42       # 200 from echo
curl -i http://localhost:8080/nope           # 404, generic problem body with a request ID
```

### 4.5 Add plugins

Plugins are a list of `{"name": ..., "config": {...}}` on a route, service or consumer. Global plugins are their
own objects.

**Console:**

1. Open the route and find **Plugins**.
2. Click **Add plugin** and choose one.
3. Fill in the form. It is generated from the plugin's JSON Schema, and the browser checks the same rules as
   the server.
4. Stage the route again.

**Plugin catalog** lists every plugin with its version and schema.

**API:** to see a plugin's schema, run `api $API/plugins/key-auth/schema`. To attach plugins, re-stage the route
with its `If-Match` (§3.2):

```bash
api -X PUT "$API/routes/users?changeset=$CS" -H "if-match: $ETAG" -d '{"spec":{
  "path": "/users", "match": "prefix", "serviceId": "users",
  "plugins": [
    {"name": "key-auth",   "config": {"header": "x-api-key", "hideCredentials": true}},
    {"name": "rate-limit", "config": {"limit": 10, "windowSeconds": 1, "key": ["consumer"]}},
    {"name": "headers",    "config": {"response": {"remove": ["server"]}}}
  ]
}}'
```

Other common examples:

```jsonc
// JWT from your IdP (exactly one key source per issuer: discovery | jwksUri | jwks | secretFile)
{"name": "jwt", "config": {"issuers": [{"issuer": "https://idp.example.com/realms/x",
  "audiences": ["orders-api"], "algorithms": ["RS256"], "discovery": true}]}}

// CORS
{"name": "cors", "config": {"allowOrigins": ["https://app.example.com"], "allowMethods": ["GET", "POST"]}}

// Response cache: 60 s for responses that don't set their own freshness; l2 uses Redis
{"name": "cache", "config": {"ttlSeconds": 60, "statuses": [200], "l2": false}}

// Global rate limit shared by all gateways through Redis, fail-open on Redis trouble
{"name": "rate-limit", "config": {"limit": 1000, "windowSeconds": 60, "key": ["ip"], "mode": "global",
  "global": {"failMode": "open", "timeoutMs": 25}}}
```

To share one config across routes, create **Plugin configs** → `std-headers` (`{"plugin": "headers", "config": {...}}`).
Then reference it with `{"name": "headers", "configRef": "std-headers"}`.

To run a plugin on every route, use **Global plugins** → **New global plugin**. The ID is the plugin name.

```bash
api -X PUT "$API/global-plugins/cors?changeset=$CS" -d '{"spec":{"enabled":true,"config":{"allowOrigins":["https://app.example.com"]}}}'
```

#### Built-in plugins

| Plugin | Purpose |
|---|---|
| `jwt` | Validate bearer JWTs (OIDC discovery, JWKS, inline keys). Sets the principal |
| `key-auth` | API keys issued to consumers (`<keyId>.<secret>`). Sets the principal |
| `authz` | RBAC/ABAC allow/deny policies, evaluated after authentication |
| `ip-restriction` | Allow/deny CIDRs based on the real client IP |
| `request-size-limit` | Cap request body size per route |
| `cors` | CORS preflight and response headers |
| `headers` | Add, set or remove request and response headers |
| `request-transform` | Rewrite the path, query or headers before proxying |
| `rate-limit` | Token bucket, fixed window or sliding window. Local, or global via Redis |
| `cache` | HTTP response cache: L1 in memory, L2 in Redis (optional) |

For every option, see [plugin reference](../plugins/authoring.md).

### 4.6 Consumers and API keys

**Console:**

1. Go to **Consumers** → **New consumer**, set ID `acme`, **Username** `acme`, and optionally **Roles** and **Attributes**.
   Click **Stage consumer**.
2. On the consumer's page, under **Credentials**, click **Generate API key**. **Copy the key now**: only a salted
   hash is stored, so it can never be shown again.
3. Publish. The key works only after the change set is published.

**API:**

```bash
api -X PUT "$API/consumers/acme?changeset=$CS" -d '{"spec":{"username":"acme","roles":["orders:read"]}}'
api -X POST "$API/consumers/acme/credentials?changeset=$CS" -d '{"type":"api-key"}'   # → {"id","keyId","apiKey"}; apiKey shown once
```

After publishing:

```bash
curl -i http://localhost:8080/users                              # 401
curl -i -H 'x-api-key: <apiKey>' http://localhost:8080/users     # 200; rate-limit headers in the response
```

- **Rotating a key:**
  1. Generate a new key.
  2. Hand it to the client.
  3. Revoke the old one (**Stage revoke of <id>**, or `DELETE /consumers/acme/credentials/{id}` with `If-Match`).
  4. Publish.
- **Consumer overrides:** use **Plugin overrides** on a consumer, for example a higher `rate-limit` for one
  partner. Overrides apply only to plugins that are already in the route's chain.
- **Identity headers:** after authentication, the gateway sets `x-consumer-id` (and `x-tenant-id` for tenant
  routes) on the upstream request. Clients cannot spoof these headers, because they are always stripped from
  inbound requests.

### 4.7 Authorization and tenants

1. Create **Authorization policies**, for example: effect `allow`, routes `users`, methods `GET`, subjects
   roles `orders:read`.
2. Add `{"name": "authz", "config": {"policies": ["<policy-id>"]}}` to the route, after an authenticating plugin.

Any matching deny wins. Without a matching allow, the result is 403, or 401 if the request is unauthenticated.

Routes, services, upstreams and consumers can carry a `tenantId`. A tenant route must have an authenticating
plugin in its chain. Details: [ADR 0016](../adr/0016-authorization-tenants-and-consumer-precedence.md) and the
[plugin reference](../plugins/authoring.md).

### 4.8 Other objects

| Console page | Use |
|---|---|
| **Revoked tokens** | Reject a JWT by `jti` until it expires |
| **Cache purges** | Invalidate cached responses by route or tag (`cache-tag` response header) |
| **Certificates** (admin) | References to TLS key material held outside etcd. Private keys are never accepted |

## 5. Errors and what to do

Admin API errors use RFC 9457 problem bodies.

| Status | Meaning | Fix |
|---|---|---|
| 401 | Missing, expired or wrong-audience token | Sign in again or get a new token |
| 403 | Your role does not allow this | Ask for a higher role (see §2) |
| 409 | The change set itself changed (another tab or user) | Reload the change set |
| 412 | Someone published a change to this object after you loaded it. `conflicts` lists the keys | Reload the object and stage your edit again |
| 422 | Validation failed. `errors[].path` names the field, for example `routes/users.spec.path` | Fix the field. The console highlights it |
| 428 | `If-Match` is missing on an update or delete | Send the object's current `ETag` |

Gateways return generic problem bodies with a request ID to clients, and never expose upstream details:

| Status | Cause |
|---|---|
| 404 | No route |
| 413 | Body too large |
| 502 | Upstream connect or protocol failure |
| 503 | No config, pool queue full, or circuit open |
| 504 | Timeout |

Full table: [configuration → Errors seen by clients](../operations/configuration.md#errors-seen-by-clients).

## 6. Roll back

**Console:** go to **History**, open the entry, click **Roll back…**, then **Publish rollback** (publisher).

**API:**

```bash
api $API/history                                  # newest first
api -X POST $API/history/<seq>/rollback
```

A rollback publishes the inverse of the entry as a new transaction. It is refused if a later publish changed the
same objects. In that case, roll back the later entry first.

## 7. Audit and observing traffic

- **Audit** (admin): every Admin API write, with who, when, source IP and the change. Read it in the **Audit**
  page or with `GET /admin/v1/audit`. Retention rules: [ADR 0021](../adr/0021-audit-retention.md).
- **Metrics:** Grafana (http://localhost:3000) has dashboards for traffic per route, upstreams, plugins, config
  propagation and the control plane.
- **Traces:** Jaeger (http://localhost:16686, service `apigw-gateway`).
- **Logs:** JSON access logs on the gateways' stdout (`docker compose logs gateway-1`).

Metric catalogue, alerts and runbooks: [observability](../operations/observability.md).
