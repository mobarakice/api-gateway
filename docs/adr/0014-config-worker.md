# 0014 etcd config worker, last-known-good store and node heartbeat

- Status: Accepted (2026-11-21, M3)

## Context
Invariants 2, 3, 5 and 6: requests never touch etcd; snapshots are built whole and swapped atomically;
a publish is one etcd transaction; the worker never stops traffic and never applies part of a batch.
Gateways must also restart and serve while etcd is unreachable, and operators need to see which
revision every gateway runs.

## Options
1. Per-key incremental updates applied to the live router (patch in place).
2. A watch per subtree, each driving its own partial rebuild.
3. **One full read at revision R, one prefix watch from R+1, every change applied to a private
   working copy of the raw key-values, then a full build → validate → compile → swap.**

## Decision
Option 3, in `gateway-config` (`apigw.config.worker`):
- **Sync**: every config subtree (`meta/`, `routes/`, `services/`, `upstreams/`, `global-plugins/`,
  `consumers/`, `credentials/`, `plugin-configs/`, `policies/`, `certificates/`) is range-read in pages
  of 200 at one revision R (the revision of an initial count request), so the build sees one
  consistent cut of the store.
- **Watch**: one watch over the whole prefix from R+1 with progress notifications. Keys outside the
  config subtrees (`nodes/`, `changesets/`, `history/`, `audit/`) are dropped on arrival. etcd delivers a
  transaction's events in one response, so a change set is never split. Relevant events are debounced
  (100 ms, extended up to 1 s while changes keep arriving) and applied together.
- **Apply**: the working copy is copied, the events applied, and the result built
  (`EtcdSnapshotBuilder`: envelope checks — `kind` matches the subtree, `id` equals the key's last
  segment, `schemaVersion` supported, no unknown envelope fields — then the spec is decoded with the
  id injected), validated and compiled. Only a complete success is published. A rejection leaves the
  previous snapshot serving, sets `INVALID_CONFIG`, and reports errors against etcd keys
  (`routes/orders.serviceId`, not `routes[3].serviceId`). The next change that makes the state valid
  applies everything.
- **Failures**: a compacted watch (`OUT_OF_RANGE`) or an etcd whose revision went backwards triggers a
  full resync. Anything else resumes from the last revision seen after exponential backoff with equal
  jitter (200 ms doubling to 10 s). The worker never waits for etcd to start, and never reports ready
  without a snapshot.
- **Partitions**: a paused or black-holed member can leave a watch stream open but silent, so it never
  ends by itself. The heartbeat's calls (keepalive every 5 s, request timeout `GW_ETCD_TIMEOUT_MS`)
  double as a liveness probe. A failed call rotates the client to the next member. The watch is
  recycled when a call fails **or** when the client is no longer on the watch's member. The second
  condition was found by the cluster partition test: a timed-out heartbeat write had already
  rotated the client, later keepalives to the healthy member succeeded, and the watch stayed on the
  frozen one. Worst-case detection is about one keepalive interval plus one timeout. The tests
  measured 3.3 s (JVM) and 8.2 s (native) on the dev host.
- **Threading**: the worker is a Vert.x `WORKER` verticle. All its callbacks run serially on one worker
  context, so the state needs no locks. Compilation and disk I/O never run on a traffic event loop.
  Traffic reads only `SnapshotHolder` (one volatile read).

### Last-known-good (LKG)
- `GW_LKG_DIR/lkg-<prefix>.json`: the raw config key-values (base64) of the last published etcd
  revision, written after each successful publish, never before.
- `{"sha256": hex, "payload": {...}}`. The digest (JDK `MessageDigest`, not our own crypto) detects
  truncation and corruption. The prefix and format are checked on load.
- Atomic write: a temp file created with mode 0600, fsync, then an atomic rename.
- At boot the LKG is compiled by the running build before it is served. If this build rejects it, it
  is ignored, never served half-valid. State `SERVING_STALE`, source `lkg`, until etcd answers.
- The LKG holds what etcd holds. From M4 that includes credential hashes and envelope-encrypted
  secrets, never plaintext, which is why the file is owner-only.

### Node heartbeat
- `nodes/{gatewayId}` on a 15 s lease, renewed every 5 s. Value:
  `{gatewayId, version, state, appliedRevision, source, configError?}`. It is rewritten only when it
  changes, so a fleet doesn't wake every watcher every few seconds.
- An expired lease (long outage) is re-granted and the key rewritten. A graceful shutdown revokes the
  lease, which removes the key at once.
- This is the gateway's only etcd write. The role allows `readwrite` on `nodes/` and `read` elsewhere.

## Performance
- Request path: unchanged (no etcd, one volatile read).
- Propagation on the dev host, measured by the integration tests from etcdctl `put` returning to the
  first proxied 200: about 100–115 ms, JVM and native. The 100 ms debounce dominates.
- A change costs one full compile. That is linear in config size: about 1 ms per 1K routes for the
  router (M2 JMH), with no reference-host data yet.

## Memory
The working copy holds the raw values of the config subtrees once, plus one transient copy during an
apply. The old snapshot becomes garbage when in-flight requests release it.

## Native Image
No new dependencies. Jackson tree model only; `MessageDigest` SHA-256 and POSIX file attributes work
in native images. The worker's unit tests run under `nativeTest` (`gateway-config` now applies
`gw.native-conventions`). All ITs pass against the native binary.

## Operational impact
- New env: `GW_ETCD_ENDPOINTS`, `GW_ETCD_CA`, `GW_ETCD_USER`, `GW_ETCD_PASSWORD_FILE`,
  `GW_ETCD_PREFIX`, `GW_ETCD_TIMEOUT_MS`, `GW_LKG_DIR`, `GW_GATEWAY_ID`. `GW_CONFIG_FILE` and etcd are
  mutually exclusive.
- `/configz` on the status port shows state, revisions and the last config or etcd error.
- Logs: `config snapshot published`, `config change rejected`, `etcd unreachable, retrying` (first
  attempt and every 10th), `etcd watch compacted ... resyncing`, `restored last-known-good snapshot`.
- On Kubernetes, `GW_LKG_DIR` should be a volume that survives container restarts (an `emptyDir`
  survives container restarts but not pod rescheduling). The pod name is the default gateway ID.
