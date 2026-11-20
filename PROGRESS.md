# PROGRESS

Resume rule: read `CLAUDE.md` and this file, then continue from the last green milestone.

| # | Scope | Status |
|---|---|---|
| M0 | Environment report, architecture, ADRs, risk register, roadmap | done (2026-11-18) |
| M1 | Gradle multi-project, quality gates, Vert.x h1/h2/h2c, first native binary, etcd native spike | done (2026-11-18) |
| M2 | Snapshot model, route trie, plugin SPI/registry, upstreams, LB, streaming proxy | done (2026-11-19) |
| M3 | Config worker against etcd | done (2026-11-21) |
| M4 | Security plugins | not started |
| M5 | Traffic plugins | not started |
| M6 | Control plane | not started |
| M7 | Admin console | not started |
| M8 | Observability | not started |
| M9 | Docker, Kubernetes, hardening | not started |
| M10 | Benchmarks, profiling, JVM vs native report | not started |

## M0 results

- `docs/environment.md`, `docs/architecture/{overview,etcd-keys,risks}.md`, ADRs 0001–0009 written.
- ADRs 0007, 0008, 0009 are Proposed; they are decided in M1 (0007, 0009) and M10 (0008).
- Exit check: files present and committed. No code in M0.

## M1 results (2026-11-18, dev host: macOS arm64, Oracle GraalVM 25+37.1)

Exit checks, all run and passing:
- `./gradlew build`: green (compile with `-Werror`, unit tests, Checkstyle, SpotBugs, OSV dependency scan).
- `./gradlew :gateway-app:nativeCompile`: produces a 47 MB executable (`gateway`).
- `./gradlew :gateway-app:nativeTest`: 11/11 tests pass natively. They cover h1, h2c (prior knowledge and
  upgrade), h2 over TLS with ALPN, h1 over TLS, health/readiness, and the 404 problem body.
- `./gradlew :integration-tests:integrationTest` (JVM process) and `:nativeIntegrationTest` (native binary):
  12/12 each.
  - `GatewayProcessIT`: every protocol, readiness 503 without config, SIGTERM drain with exit 143 and
    "shutdown complete", and exit 2 on invalid configuration.
  - `EtcdClientIT`: real etcd v3.7.3 with TLS and auth. Covers read, role write-denial, watch, compaction
    cancel, wrong password, and an unreachable endpoint.
  - `EtcdCheckIT`: `gateway check-etcd`. Connects over TLS with the read-only role, reads, confirms it
    cannot write, watches and gets an event, and does not leak passwords.
- ADR 0009 (etcd client) accepted: HTTP/JSON gateway over Vert.x. jetcd was disqualified because it
  depends on Vert.x 4.

Numbers measured once in the integration tests on the dev host. These are not reference-host results
and not benchmarks:

| | JVM (GraalVM JDK) | Native |
|---|---|---|
| spawn → listening, no config | 383 ms | 104 ms |
| RSS after protocol probes | 206 MB | 36 MB |

Notes:
- Startup is measured without an LKG snapshot. The cold-start target (≤ 100 ms with LKG) is measured
  once LKG exists (M3).
- The installed GraalVM 25.0.0 was too old for the reachability-metadata schema. Resolved in M2: upgraded
  to 25.0.4 (requester approved).

## M2 results (2026-11-19, dev host: macOS arm64, Oracle GraalVM 25.0.4)

Delivered:
- `config-model`:
  - config records;
  - a reflection-free decoder that reports errors with field paths and rejects unknown fields;
  - a cross-object validator (ids, references, path, host and CIDR syntax, duplicate-match detection);
  - `Cidr`;
  - the `headers` plugin schema.
- `gateway-core`:
  - `SnapshotCompiler` and immutable `Snapshot` with a one-volatile `SnapshotHolder` (ADR 0011);
  - the host table plus segment trie router (ADR 0012);
  - plugin SPI (phases, outcomes, factory, static registry) with per-route per-phase arrays;
  - the request pipeline: framing and path validation, hop-by-hop and identity stripping, trusted-proxy
    forwarding headers, generic errors (ADR 0013).
- `gateway-proxy`:
  - per-event-loop pools per upstream, kept across swaps unless the definition changes, otherwise
    retired gracefully;
  - balancers: round robin, smooth weighted, least-requests (P2C), consistent hash;
  - a streaming proxy with backpressure (`pipeTo`/`send`) and a body cap for unknown-length bodies;
  - connect, first-byte, idle and total timeouts;
  - 502/503/504 mapping, and a stream reset after the response head.
- `gateway-plugins`: the static `BuiltinPlugins` registry and the `headers` plugin.
- `gateway-config`: `FileConfigSource`. It loads `GW_CONFIG_FILE` off the event loop, publishes
  atomically, and polls for changes. An invalid change is rejected and the current snapshot keeps
  serving.
- `gateway-app`: pipeline and forwarder per verticle. Readiness turns on at the first published
  snapshot, and shutdown is sticky.
- Docs:
  - ADRs 0011–0013 and an amendment to ADR 0003 (QUIC classes, run-time init);
  - `docs/plugins/authoring.md` and `docs/operations/configuration.md`;
  - risk updates (R3, R8 closed, R13 re-checked, R14 new).

Exit checks, all run and passing:
- `./gradlew build` (including OSV scan): green. Unit tests on the JVM: config-model 10, gateway-core 17,
  gateway-proxy 16 (12 end-to-end over real loopback servers), gateway-plugins 4, gateway-config 10,
  gateway-app 11, gateway-observability 8.
- `./gradlew nativeTest`: config-model 10/10, gateway-core 17/17, gateway-proxy 16/16,
  gateway-plugins 4/4, gateway-app 11/11 as native images.
- `./gradlew :integration-tests:integrationTest` and `:nativeIntegrationTest`: 14/14 each.
  - `ProxyIT` (new) runs the real process. It covers: template route, `headers` plugin on both sides,
    forwarding headers, round robin across two targets, a 3 MB upload, h2c client to h2c upstream,
    a generic 502, 404 on a method mismatch, live file reload, and a rejected invalid change that keeps
    serving and stays ready.
  - `GatewayProcessIT` now covers ready with a config, and 503 / not-ready without one.
- JMH `RouterBenchmark` (dev host, `-prof gc`): 56.1 / 69.4 / 72.8 ns per lookup at 10 / 1K / 10K
  routes, ≈ 0 B/op.
- Native unit tests with real servers caught two upstream-library problems that the JVM-only M1 suite
  could not (ADR 0003 amendment):
  - Vert.x needs `netty-codec-classes-quic` for `remoteAddress()`;
  - Netty `SslContext` must initialise at run time.

Dev-host measurements from the integration tests. These are not reference-host results:

| | JVM (GraalVM JDK 25.0.4) | Native |
|---|---|---|
| spawn → listening | 448 ms | 100 ms |
| spawn → ready (file config loaded) | 529 ms | 107 ms |
| RSS after protocol probes | 177 MB | 42 MB |

Not in M2 (planned later, per spec order):
- Active and passive health checks, retries with budget, and circuit breaker (M5).
- SSRF connect-time checks (M4).
- gRPC trailers and `TE: trailers` passthrough (M5).
- WebSocket upgrade proxying (M5).
- Consumer-scope plugin precedence (M4).
- etcd as the config source (M3).

## M3 results (2026-11-21, dev host: macOS arm64, Oracle GraalVM 25.0.4, etcd v3.7.3)

Delivered (ADR 0014):
- `gateway-config/worker`:
  - `ConfigWorker` (a worker verticle). It does a full paged sync at one revision, then one prefix
    watch with progress notifications. Changes are debounced (100 ms, up to 1 s), applied to a working
    copy, then built, validated, compiled and swapped atomically.
  - Compaction, or etcd moving backwards, triggers a full resync. Other errors resume after
    exponential backoff with jitter.
  - The watch is recycled when a heartbeat call fails, or when the client has rotated off the watch's
    member (partition detection).
- `EtcdSnapshotBuilder`:
  - envelope checks: kind, id = key, schemaVersion, no unknown fields;
  - errors are reported against etcd keys;
  - `meta/schema-version` is required, `meta/settings` is optional.
- `LkgStore`: SHA-256-checked JSON, written temp → fsync → atomic rename, mode 0600. It is restored at
  boot only if this build compiles it.
- `NodeHeartbeat`: `nodes/{gatewayId}` on a 15 s lease, renewed every 5 s, rewritten only on change,
  re-granted after expiry, revoked on graceful shutdown.
- etcd client: `leaseGrant`, `leaseKeepAlive`, `leaseRevoke`, `Watch.endpoint()`. `EtcdEnv` is shared
  with `check-etcd`.
- `config-model`: `ConfigDecoder.decodeSettings` and `decodePluginRef`.
- `gateway-app`:
  - etcd source wiring (`GW_ETCD_*`, `GW_LKG_DIR`, `GW_GATEWAY_ID`), mutually exclusive with
    `GW_CONFIG_FILE`;
  - `/configz` on the status port.
- `test-support`: `EtcdTestCluster` (N members, TLS + auth, fixed loopback ports, kill/restart/pause).
  `EtcdTestNode` is now a 1-member cluster.
- Docs: ADR 0014; updates to `etcd-keys.md` (`meta/settings`, `GlobalPlugin`, heartbeat value,
  build rules) and `configuration.md` (env vars, state table).

Exit checks, all run and passing:
- `./gradlew build` (including OSV scan): green. gateway-config unit tests: 18 (new:
  `EtcdSnapshotBuilderTest`, `LkgStoreTest`, `BackoffTest`).
- `./gradlew nativeTest`: 76/76 as native images. gateway-config now applies `gw.native-conventions`
  (18/18 native).
- `:integration-tests:integrationTest` 23/23 and `:nativeIntegrationTest` 23/23, against real etcd
  containers with TLS + auth and the gateway's read-only role.
  - `ConfigWorkerIT` (single node):
    - sync, watch and heartbeat (lease attached, key removed on graceful stop, no password in logs);
    - invalid change rejected (previous snapshot serving, still ready, error names `routes/bad`, valid
      changes in the same state held back, malformed JSON rejected, fix applies everything);
    - etcd down at boot with no LKG (503 / NO_CONFIG, recovers);
    - etcd restart (kill -9: SERVING_STALE and still 200, watch resumes);
    - network partition via a paused container;
    - LKG restore with etcd down, plus a corrupted LKG never served.
  - `EtcdClusterIT` (3 members):
    - leader change via `move-leader`, then leader crash;
    - partitioned member abandoned for a healthy one;
    - compaction while disconnected forces a full resync.
- The cluster partition test found a real bug, fixed before commit (ADR 0014, "Partitions"): a
  rotated client left the watch on a frozen member.

Dev-host measurements from the integration tests. These are not reference-host results and not
benchmarks:

| | JVM (GraalVM JDK 25.0.4) | Native |
|---|---|---|
| etcd `put` → first 200 on the new route | 108–111 ms | 99–105 ms |
| etcd `del` → 404 | 106–115 ms | 103–112 ms |
| spawn → ready from LKG, etcd down | 390 ms (in-process 303 ms) | 25 ms (in-process 8 ms) |
| partitioned member → change served via another | 3.3 s | 8.2 s |
| binary size | – | 49 MB |

Notes:
- Propagation is dominated by the 100 ms debounce.
- Partition detection is bounded by keepalive interval plus request timeout (5 s + 3 s in the tests).
- The spec's propagation target at fleet scale (many gateways, 10K routes) needs the reference hosts
  and is measured in M10.

Not in M3 (planned later):
- Compiling consumers, credentials, policies, plugin-configs and certificates (M4). They are already
  read, envelope-checked and kept in the LKG.
- The control-plane writer, change sets, transactions with `mod_revision` compares, and history
  (M6).
- Watch relay or fan-out for large fleets (risk register, M10).
- Config-worker metrics: applied revision, apply latency, rejects (M8). Today they are in
  `/configz`, the heartbeat and logs.

## Known blockers

- BLOCKED: reference benchmark host. The spec's reference host (2 vCPU / 4 GB, Linux) and a separate
  load-generator host are not available; the only host is a macOS arm64 laptop (8 CPU, 16 GB).
  Requester must provide two Linux hosts (gateway + load generator) and a third for the upstream.
- BLOCKED: Kubernetes cluster for multi-replica benchmarks and the rollout drain test. Requester must
  provide a CNCF-conformant cluster >= 1.30 (kind in Docker can cover functional checks only).
- No external OIDC provider: OIDC tests use a containerized IdP or a local JWKS fixture; each test
  states which.
