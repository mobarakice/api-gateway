# Control plane: operations

The Admin API (`/admin/v1`, OpenAPI: `docs/api/admin-v1.yaml`) is the only writer of gateway config
in etcd (ADR 0020). It is stateless: run 2+ replicas behind a load balancer.
`history/head` serialises their publishes.

```bash
./gradlew :control-plane:run                       # JVM (dev)
./gradlew :control-plane:nativeCompile             # -> control-plane/build/native/nativeCompile/control-plane
```

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `CP_BIND_HOST` / `CP_HTTP_PORT` | `0.0.0.0` / `9080` | listener |
| `CP_TLS_CERT` / `CP_TLS_KEY` | — | PEM files; enables HTTPS (h2 + http/1.1 via ALPN). Set both or neither |
| `CP_ETCD_ENDPOINTS` | required | comma-separated `https://host:port` |
| `CP_ETCD_CA` | JDK trust | PEM CA for etcd |
| `CP_ETCD_USER` / `CP_ETCD_PASSWORD_FILE` | — | the control-plane etcd user; the password is read from a file, never from env |
| `CP_ETCD_PREFIX` | required | e.g. `/gw/prod/` |
| `CP_ETCD_TIMEOUT_MS` | `5000` | per etcd request |
| `CP_OIDC_ISSUER` / `CP_OIDC_AUDIENCE` | required | exact `iss`; accepted `aud` values (comma-separated) |
| `CP_OIDC_ALGORITHMS` | `RS256,ES256` | pinned; HMAC algorithms are refused |
| `CP_OIDC_JWKS_FILE` \| `CP_OIDC_JWKS_URI` \| `CP_OIDC_DISCOVERY` | exactly one | signing keys |
| `CP_OIDC_CA` | JDK trust | PEM CA for the JWKS / discovery endpoint |
| `CP_OIDC_ROLES_CLAIM` / `CP_OIDC_ROLE_PREFIX` | `roles` / empty | claim (array or space-separated) holding `viewer`, `editor`, `publisher`, `admin` (with the prefix, e.g. `gw-admin`) |
| `CP_OIDC_SKEW_SECONDS` | `60` | clock skew for `exp`/`nbf`/`iat` |
| `CP_CORS_ORIGINS` | none | exact console origins (`https://console.example.com`), comma-separated; no wildcards, no credentials |
| `CP_TRUSTED_PROXIES` | none | CIDRs whose `X-Forwarded-For` is honoured for the audit `sourceIp` |
| `CP_AUDIT_FILE` | none | append-only JSON Lines audit sink (ADR 0021) |
| `CP_AUDIT_WINDOW` | `1000` | audit events kept in etcd |
| `CP_ETCD_MAX_TXN_OPS` | `128` | must match etcd `--max-txn-ops`; a change set holds this − 5 ops |
| `CP_MAX_TXN_BYTES` | `1000000` | keep below etcd `--max-request-bytes` (1.5 MiB default) |
| `CP_MAX_VALUE_BYTES` | `262144` | per object |
| `CP_HISTORY_LIMIT` | `100` | published change sets that can be rolled back |
| `GW_LOG_LEVEL` | `info` | shared JSON log provider |

Startup: the control plane initialises `meta/schema-version` if missing, with jittered retries while
etcd is down. It refuses to run against a newer schema version. `/admin/v1/health` is liveness;
`/admin/v1/ready` reports ready once the keyspace has been migrated and is readable.
SIGTERM drains in-flight requests (≤ 5 s).

## Workflow

1. `POST /changesets {"title": ...}` (editor).
2. Stage edits: `PUT /routes/a?changeset={id}`. To change or delete an existing object, send its
   `ETag` in `If-Match`.
3. `POST /changesets/{id}/validate` and `GET /changesets/{id}/diff`.
4. `POST /changesets/{id}/publish` (publisher). The publish is one etcd transaction, and gateways
   apply it atomically.
5. Undo with `POST /history/{seq}/rollback` (publisher). This is refused with 409 if a later publish
   changed the same keys; roll that one back first.

Errors: 412 means another publish changed a staged key (the `conflicts` field lists them; re-read
and re-stage). 409 means the change set itself changed. 422 means validation failed; `errors[].path`
names the key and field, for example `routes/a.spec.path`. 428 means `If-Match` is missing.

API keys: `POST /consumers/{cid}/credentials?changeset=` returns `apiKey` **once**. Only its salted
hash is stored, and no endpoint returns it again. To rotate a key, issue a new one and delete the old.

Gateway-local files (for example a JWT `secretFile`) are checked for shape only at publish time. If
a gateway cannot read one, it keeps its previous config and shows `configError` in
`GET /gateways`.
