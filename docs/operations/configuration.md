# Gateway configuration

## Bootstrap (environment)

| Variable | Default | Meaning |
|---|---|---|
| `GW_BIND_HOST` | `0.0.0.0` | Listen address for all listeners |
| `GW_HTTP_PORT` | `8080` | Plaintext listener: HTTP/1.1 + h2c (prior knowledge and upgrade) |
| `GW_HTTPS_PORT` | `8443` | TLS listener (only with `GW_TLS_CERT` + `GW_TLS_KEY`): h2 via ALPN, HTTP/1.1 |
| `GW_STATUS_PORT` | `9901` | `/healthz`, `/readyz`; never expose publicly |
| `GW_EVENT_LOOPS` | CPU count | Event loops (one traffic verticle each) |
| `GW_TLS_CERT`, `GW_TLS_KEY` | – | PEM chain and PKCS#8 key; set both or neither |
| `GW_SHUTDOWN_GRACE_SECONDS` | `20` | Drain time on SIGTERM |
| `GW_CONFIG_FILE` | – | Whole-config JSON file (M2). etcd arrives in M3 |
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
    "maxRequestBodyBytes": 10485760
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
