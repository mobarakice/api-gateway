# Gateway configuration

## Bootstrap (environment)

| Variable | Default | Meaning |
|---|---|---|
| `GW_BIND_HOST` | `0.0.0.0` | Listen address for all listeners |
| `GW_HTTP_PORT` | `8080` | Plaintext listener: HTTP/1.1 + h2c (prior knowledge and upgrade) |
| `GW_HTTPS_PORT` | `8443` | TLS listener (only with `GW_TLS_CERT` + `GW_TLS_KEY`): h2 via ALPN, HTTP/1.1 |
| `GW_STATUS_PORT` | `9901` | `/healthz`, `/readyz`, `/configz`; never expose publicly |
| `GW_EVENT_LOOPS` | CPU count | Event loops (one traffic verticle each) |
| `GW_TLS_CERT`, `GW_TLS_KEY` | – | PEM chain and PKCS#8 key; set both or neither |
| `GW_SHUTDOWN_GRACE_SECONDS` | `20` | Drain time on SIGTERM |
| `GW_ETCD_ENDPOINTS` | – | Comma-separated `https://host:port` list. Selects etcd as the config source |
| `GW_ETCD_CA` | – | PEM CA bundle for etcd's server certificate |
| `GW_ETCD_USER`, `GW_ETCD_PASSWORD_FILE` | – | Gateway role user; the password is read from the file, never from an env value |
| `GW_ETCD_PREFIX` | – | Config prefix, e.g. `/gw/prod/` (required with etcd) |
| `GW_ETCD_TIMEOUT_MS` | `5000` | Per-request etcd timeout |
| `GW_LKG_DIR` | – | Directory for the last-known-good snapshot. Without it, a restart during an etcd outage serves nothing |
| `GW_GATEWAY_ID` | `$HOSTNAME` | ID for the `nodes/{id}` heartbeat (no `/`) |
| `GW_CONFIG_FILE` | – | Whole-config JSON file (dev/tests). Mutually exclusive with `GW_ETCD_ENDPOINTS` |
| `GW_CONFIG_POLL_MS` | `1000` | File change poll interval (50–600000) |
| `GW_LOG_LEVEL` | `INFO` | Root log level |

Without a valid configuration the gateway answers 503 and `/readyz` reports why.

## Config document
Decoded by `apigw.model.ConfigDecoder`, validated by `ConfigValidator`, compiled by
`SnapshotCompiler`. Unknown fields are errors. Every error is reported with its path.

```json
{
  "schemaVersion": 1,
  "revision": 42,
  "settings": {
    "trustedProxies": ["10.0.0.0/8"],
    "stripRequestHeaders": ["x-consumer-id", "x-tenant-id"],
    "viaName": "apigw",
    "defaultTimeouts": {"connectMs": 2000, "firstByteMs": 30000, "idleMs": 60000, "totalMs": 0},
    "maxRequestBodyBytes": 10485760,
    "upstreamAllowCidrs": ["10.1.0.0/16"]
  },
  "upstreams": [{
    "id": "users-v1", "scheme": "http", "balancer": "least-requests", "http2": false,
    "targets": [{"host": "10.1.0.5", "port": 8080, "weight": 2}, {"host": "10.1.0.6", "port": 8080}],
    "pool": {"maxConnections": 64, "maxPending": 256, "keepAliveSeconds": 60}
  }],
  "services": [{"id": "users", "upstreamId": "users-v1", "timeouts": {"firstByteMs": 5000}}],
  "routes": [{
    "id": "get-user", "hosts": ["api.example.com"], "methods": ["GET"],
    "path": "/users/{id}", "match": "template", "serviceId": "users", "priority": 0,
    "headers": {"x-api-version": "2"}, "query": {}, "preserveHost": false,
    "plugins": [{"name": "headers", "config": {"response": {"remove": ["server"]}}}]
  }],
  "globalPlugins": []
}
```

- `upstreamAllowCidrs`: exemptions from the SSRF deny list (ADR 0015). Upstream targets in loopback,
  private, CGNAT, link-local/metadata, ULA, multicast, documentation, IPv4-mapped and NAT64 ranges are
  rejected unless an entry here covers them. Literal IPs are checked when the config is validated, and
  host names when the gateway connects (every resolved address must pass). Set it to your cluster's
  pod/service CIDRs, never to `0.0.0.0/0`.
- Security objects (ADR 0015/0016): top-level `consumers`, `credentials`, `authzPolicies`, `pluginConfigs`
  and `revokedTokens`, plus `tenantId` on routes, services and upstreams. See `docs/plugins/authoring.md`.
- `match`: `exact`, `prefix` (default; segment-bounded), `template` (`{name}` = one segment).
- `balancer`: `round-robin` (default), `weighted-round-robin` (smooth), `least-requests` (power of two
  choices on in-flight requests per weight), `consistent-hash` with `hashKey` `ip` or `header:<name>`
  (160 virtual nodes per weight unit).
- `scheme: https` verifies the target certificate and host name, with SNI; `caFile` adds a PEM bundle.
- Timeouts inherit route → service → `settings.defaultTimeouts`. `firstByteMs` runs from the end of the
  request body to the response head; `idleMs` is the longest silence on the upstream stream; `totalMs`
  bounds the whole exchange (0 = none, for long streams).
- Pools are per target and per event loop. `maxPending` requests may wait for a connection; beyond that
  the request fails fast with 503.

## Errors seen by clients
Generic RFC 9457 bodies with the request ID, never upstream addresses or config details:

| Cause | Status |
|---|---|
| No snapshot loaded | 503 |
| Malformed framing, ambiguous path, bad Host | 400 |
| `CONNECT` | 501 |
| No route | 404 |
| Body over `maxRequestBodyBytes` | 413 |
| Upstream connect/protocol failure | 502 |
| Pool wait queue full | 503 |
| First-byte or total timeout | 504 |
| Failure after the response head was sent | stream reset / connection closed |

## Reloads
The file is re-read when its modification time or size changes. Replace it atomically (write a temp
file, then rename). A change that fails to parse, validate or compile is logged
(`config file change rejected`) and the current snapshot keeps serving. Upstream pools survive a reload
unless that upstream's connection settings or targets changed; changed or removed pools are retired
gracefully (in-flight requests complete, up to 30 s).

## etcd source (M3)
With `GW_ETCD_ENDPOINTS` set, the gateway reads the keyspace in `docs/architecture/etcd-keys.md`. The
objects are the same as in the file document above, one envelope per key. The behaviour (ADR 0014):

| Situation | Serving | `/readyz` | `/configz` `state` |
|---|---|---|---|
| Synced and watching | newest valid revision | 200 | `SERVING` |
| Newest revision invalid | previous snapshot | 200 | `INVALID_CONFIG` (+ `configError` naming keys) |
| etcd unreachable, had a snapshot | current snapshot | 200 | `SERVING_STALE` (+ `etcdError`) |
| Boot with etcd down, valid LKG | LKG snapshot | 200 | `SERVING_STALE`, `snapshotSource: lkg` |
| Boot with etcd down, no LKG | 503 | 503 | `NO_CONFIG` |
| Watch compacted | current snapshot during resync | 200 | `SYNCING` → `SERVING` |

Changes are applied about 100 ms after the last event of a burst (at most 1 s while a burst continues).
Retries back off from 200 ms to 10 s with jitter.
