# 0020 Control plane: change sets, single-transaction publish, validation, Admin API auth

- Status: Accepted (2026-11-21, M6)

## Context
Spec §6 and §10 require:
- the control plane is the only etcd writer;
- edits are staged in change sets and published as one transaction, so gateways never see half a
  change;
- optimistic concurrency with `mod_revision` compares, exposed as `ETag` / `If-Match`;
- referential integrity checked inside the publish;
- rollback publishes the inverse of a recorded change set;
- versioned key-schema migrations;
- validation against the same rules the gateway uses;
- OIDC auth with viewer, editor, publisher and admin roles;
- write-only secrets;
- RFC 9457 errors that name the field.

CLAUDE.md forbids Spring Boot without an ADR and prefers native images.

## Options
- Framework: (a) Spring Boot with Spring Security; (b) **Vert.x core**, as in the gateway. Spring adds
  a second stack, a large native-image surface and an ADR-gated dependency. The API is about 30
  endpoints over etcd, so a small hand-written router is enough.
- Validation: (a) a JSON Schema engine over the plugin schemas; (b) **the gateway's own pipeline**:
  `EtcdSnapshotBuilder` decodes the candidate keyspace and `SnapshotCompiler` with the built-in
  registry compiles it. Option (b) is the same code, not merely the same schema, so it also checks
  cross-object rules: references, tenant isolation, duplicate key IDs, route conflicts.
- Concurrency for publishes that touch different keys: (a) compare only the touched keys; (b)
  **also compare `history/head`**, re-reading and re-validating when another publish landed.
  With (a), a publish deleting service `s` and one adding a route to `s` could both commit and leave
  a dangling reference.

## Decision
- **Keyspace** (docs/architecture/etcd-keys.md):
  - `changesets/{id}`: the draft as one value: ops = {key, PUT|DELETE, envelope, baseRevision}.
  - `history/entries/{seq:012d}`: before and after for each op; keeps the last `CP_HISTORY_LIMIT`
    entries (default 100).
  - `history/head`: {seq}.
  - `audit/{micros}-{rand}` (ADR 0021).
  - `changesets/` and `history/` are outside the gateways' watched subtrees.
- **Staging**:
  - `PUT|DELETE /admin/v1/{collection}/{id}?changeset=`, with each object checked on its own as the
    gateway would decode it.
  - Changing or deleting an existing object needs `If-Match` (428 without it). A stale ETag is 412
    at once. Creating needs no ETag (base 0 means the key must not exist).
  - Each edit of a draft is a compare-and-swap on the draft's own `mod_revision`, with its audit
    event in the same transaction.
- **Publish**:
  1. Read every config subtree and `history/head` at one revision.
  2. Any op whose base differs from the live revision is a 412 listing the keys.
  3. Apply the ops in memory and dry-run compile. Errors are 422, with paths rewritten to etcd keys
     (`routes/a.spec.path`).
  4. Commit **one** transaction:
     - compares: every touched key = its base, `history/head`, and the draft;
     - writes: the ops, the history entry, the head, the draft's deletion, the audit event, and the
       history trim.
  5. If the transaction fails, re-read. A touched key that moved is a 412, a changed draft is a 409,
     and if only the head moved the publish re-validates and retries (5 times, then 503).
- **Rollback** (`POST /history/{seq}/rollback`):
  - Refused with 409 if any of the entry's keys no longer holds that entry's `after` value: a later
    publish changed it, so roll that back first.
  - Otherwise the inverse ops (restore `before` or delete) go through the same publish path: validated,
    one transaction, recorded with `rollbackOf`.
  - Entries older than the history window cannot be rolled back. Disaster recovery is an etcd
    snapshot restore.
- **Limits**:
  - A draft holds at most `CP_ETCD_MAX_TXN_OPS − 5` ops (default 123). The 5 are the publish's own
    writes.
  - The encoded publish transaction must be ≤ `CP_MAX_TXN_BYTES` (default 1,000,000; etcd's default
    request limit is 1.5 MiB). Each object must be ≤ `CP_MAX_VALUE_BYTES` (256 KiB).
  - The control plane never splits a change set. Larger changes become several change sets, each
    validated against the full live state, so each one is safe to apply on its own. That is the only
    safe split boundary.
- **Gateway-local resources** (files such as a JWT `secretFile`): the dry run sets the
  `ValidationOnly` plugin service and checks the path's shape only. If the file is missing on a
  gateway, that gateway rejects the snapshot, keeps serving the previous one, and reports
  `configError` in its heartbeat, which `GET /gateways` shows.
- **Authentication**:
  - Bearer access tokens, verified by the gateway's `JwtVerifier`: pinned asymmetric algorithms
    (HMAC refused for admin tokens), exact issuer, audience, `exp` required, and clock skew.
  - Keys come from a JWKS file (re-read on an unknown `kid`, at most every 10 s), a JWKS URL, or OIDC
    discovery (refreshed off the request path).
  - The role is the highest recognised value in `CP_OIDC_ROLES_CLAIM`, with an optional prefix. Roles
    are hierarchical: viewer < editor < publisher < admin.
  - Settings, certificates and audit need admin. A valid token without a role gets 403.
- **Secrets**:
  - API keys are generated by the control plane. The response carries the key once; only the salted
    hash is staged.
  - Every read path (objects, drafts, diffs, history) removes credential hashes.
  - Values containing PEM private-key material are refused anywhere; certificates hold references
    (`certRef`, `keyRef`) only.
  - Nothing recoverable is stored today, so no envelope encryption is needed yet. A future field that
    must be recoverable needs it (spec §6) before it ships.
- **Migrations**: run at startup. A missing `meta/schema-version` is initialised (compare-create).
  A newer version than this build supports stops the control plane. Only version 1 exists.

## Performance
- Publishing is one range read per config subtree at one revision, plus a compile on the worker pool
  and one transaction.
- Dev host (macOS arm64; not the reference host), measured in `ControlPlaneIT`: publish request to
  the route live on all 3 gateways took 117–131 ms (native) and 140–147 ms (JVM). The target is
  ≤ 2 s.

## Memory and Native Image
- No new dependencies. Jackson is used in tree mode only (no data binding, no reflection).
- The schemas are registered as resources (`control-plane/.../META-INF/native-image/`).
- The native binary is 48.4 MB.

## Operational impact
- The control plane is stateless and can run several replicas; `history/head` serialises their
  publishes.
- Its etcd role is read on the prefix, and readwrite on the config subtrees, `changesets/`,
  `history/` and `audit/` only. It cannot write `nodes/`.
- Watchers: each gateway holds one watch on the prefix plus one lease. Expected load is gateways × 1
  watch, and every publish fans out to all of them. Up to a few hundred gateways, a 3-member etcd
  (2 vCPU, 8 GB, SSD) is ample. Beyond that, write the watch-relay ADR (risk R5).
