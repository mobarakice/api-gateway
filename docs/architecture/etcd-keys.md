# etcd key schema

Prefix is configurable, one per environment: `/gw/{env}/`. Gateways watch the config prefixes
only; `changesets/`, `history/` and `audit/` are control-plane-only and are excluded from the
gateway's snapshot build.

| Key | Written by | Watched by gateway | Notes |
|---|---|---|---|
| `routes/{id}` | control plane | yes | |
| `services/{id}` | control plane | yes | |
| `upstreams/{id}` | control plane | yes | targets validated for SSRF at publish |
| `consumers/{id}` | control plane | yes | |
| `credentials/{consumerId}/{id}` | control plane | yes | hashed (API keys) or envelope-encrypted; never plaintext |
| `plugin-configs/{id}` | control plane | yes | reusable plugin config sets |
| `global-plugins/{name}` | control plane | yes | |
| `policies/{type}/{id}` | control plane | yes | authz, rate-limit policies, cache invalidation tags |
| `certificates/{id}` | control plane | yes | references to key material, not raw private keys |
| `meta/schema-version` | control plane (migrations) | yes | gateway refuses a snapshot with an unsupported major version |
| `changesets/{id}` | control plane | no | drafts |
| `history/{seq}` | control plane | no | published change sets, bounded to last N |
| `audit/{seq}` | control plane | no | bounded recent window for the console (see audit ADR, M6) |
| `nodes/{gatewayId}` | gateway (leased) | no | heartbeat: applied revision, status. The only gateway-writable key range — see note |

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

## Limits

- Each value is validated against a configurable cap (default 256 KiB), well under etcd's
  `--max-request-bytes` (1.5 MiB default).
- One publish transaction is capped by etcd's `--max-txn-ops` (default 128). The control plane
  checks the op count before writing. Splitting rules are defined in M6.
