# 0021 Audit events and retention

- Status: Accepted (2026-11-21, M6)

## Context
Every Admin API mutation must emit an append-only structured audit event (who, what, before/after
hash, change set, time, source IP) to the log pipeline and a configurable external sink. A bounded
recent window is kept in etcd for the console. etcd is not an archive: its total size is small
(default quota 2 GiB) and every byte is replicated and kept in memory.

## Decision
- **Event**:
  - `{id, at, action, outcome, actor{subject, role}, sourceIp, requestId, target, changeSetId?, seq?,
    changes[{key, action, beforeHash, afterHash}], detail?}`.
  - Hashes are SHA-256 of the stored bytes. Values themselves, tokens and secrets never appear.
  - `sourceIp` is the peer, or the right-most untrusted `X-Forwarded-For` hop behind
    `CP_TRUSTED_PROXIES`.
- **Successful mutations** (create/stage/unstage/discard a draft, publish, rollback, migrate): the
  event is written to `audit/{micros}-{rand}` **in the same etcd transaction as the mutation**, so a
  committed change always has its event. After the commit it is logged (logger `audit`, field
  `audit`) and appended to the sink.
- **Refused attempts** (denied, conflict, invalid): logged and sunk, never written to etcd, so callers
  without rights cannot grow etcd.
- **Window**: every 60 s, the oldest events beyond `CP_AUDIT_WINDOW` (default 1,000) are deleted,
  100 per transaction. `GET /admin/v1/audit` (admin) reads the window, newest first.
- **Archive**: the log pipeline (stdout JSON lines) and `CP_AUDIT_FILE` (append-only JSON Lines,
  opened with O_APPEND). Ship either to WORM storage. Retention there is a deployment policy
  (suggested: at least 1 year).

## Consequences
- An event can be lost from the archive only if the process dies between the etcd commit and the log
  write. It is then still in the etcd window for up to `CP_AUDIT_WINDOW` newer events.
- Events are the same JSON in all three places, so one parser covers them.
