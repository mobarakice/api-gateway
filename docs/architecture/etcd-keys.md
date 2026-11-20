# etcd key schema

Prefix is configurable, one per environment: `/gw/{env}/`. Gateways watch the config prefixes
only; `changesets/`, `history/` and `audit/` are control-plane-only and are excluded from the
gateway's snapshot build.

| Key | Written by | Watched by gateway | Notes |
|---|---|---|---|
| `routes/{id}` | control plane | yes | |
| `services/{id}` | control plane | yes | |
| `upstreams/{id}` | control plane | yes | targets validated for SSRF at publish |
| `consumers/{id}` | control plane | yes | kind `Consumer`; spec `{username?, roles?, attributes?, plugins?}` |
| `credentials/{consumerId}/{id}` | control plane | yes | kind `Credential`; spec `{type: api-key, keyId, hash: sha256$salt$digest}`. `consumerId` comes from the key. Never plaintext |
| `plugin-configs/{id}` | control plane | yes | kind `PluginConfig`; spec `{plugin, config}`, referenced by `configRef` |
| `global-plugins/{name}` | control plane | yes | envelope `kind: GlobalPlugin`, `id` = plugin name; spec = `{enabled?, config?}` |
| `policies/authz/{id}` | control plane | yes | kind `AuthzPolicy` (ADR 0016) |
| `policies/revoked-tokens/{id}` | control plane | yes | kind `RevokedToken`; spec `{jti, issuer?, expiresAt}`; ≤ 100,000 |
| `policies/{type}/{id}` (other types) | control plane | yes | rate-limit and cache types arrive in M5; until then any other type rejects the state |
| `certificates/{id}` | control plane | yes | references to key material, not raw private keys |
| `meta/schema-version` | control plane (migrations) | yes | plain integer (`1`), not an envelope. Required: a keyspace without it is rejected |
| `meta/settings` | control plane | yes | envelope `kind: Settings`, `id: settings`; spec = the `settings` object of the config document. Optional (defaults) |
| `changesets/{id}` | control plane | no | drafts |
| `history/{seq}` | control plane | no | published change sets, bounded to last N |
| `audit/{seq}` | control plane | no | bounded recent window for the console (see audit ADR, M6) |
| `nodes/{gatewayId}` | gateway (leased, 15 s TTL) | no | heartbeat `{gatewayId, version, state, appliedRevision, source, configError?}` (not an envelope). The only gateway-writable key range — see note |

**Note on `nodes/`:** invariant 4 says gateways have a read-only role on the config. The gateway role
is granted `readwrite` on `/gw/{env}/nodes/` only, and `read` on the config prefixes. The security
suite checks that the gateway role cannot write anything outside `nodes/`.

## Value envelope

```json
{
  "id": "orders-api",
  "kind": "Route",
  "schemaVersion": 1,
  "tenantId": "acme",
  "updatedAt": "2026-11-18T10:00:00Z",
  "updatedBy": "user:alice",
  "spec": { "...": "object body" }
}
```

Rules the gateway enforces when it builds a snapshot (ADR 0014):
- `kind` must match the subtree: `Route`, `Service`, `Upstream`, `GlobalPlugin`, `Settings`, `Consumer`,
  `Credential`, `PluginConfig`, `AuthzPolicy`, `RevokedToken`.
- The envelope's `tenantId` is the object's tenant; the spec must not repeat it.
- `id` must equal the key's last segment, and the spec must not repeat it (the key is the identity).
- `schemaVersion` must be supported by the running build.
- Fields outside the envelope are rejected.
- Values under `certificates/` are envelope-checked and kept in the LKG; they are compiled in M5
  (TLS). Everything else is compiled from M4.
- One invalid key rejects the whole state. The previous snapshot keeps serving.

## Limits

- Each value is validated against a configurable cap (default 256 KiB), well under etcd's
  `--max-request-bytes` (1.5 MiB default).
- One publish transaction is capped by etcd's `--max-txn-ops` (default 128). The control plane
  checks the op count before writing. Splitting rules are defined in M6.
