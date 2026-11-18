# PROGRESS

Resume rule: read `CLAUDE.md` and this file, then continue from the last green milestone.

| # | Scope | Status |
|---|---|---|
| M0 | Environment report, architecture, ADRs, risk register, roadmap | done (2026-11-18) |
| M1 | Gradle multi-project, quality gates, Vert.x h1/h2/h2c, first native binary, etcd native spike | done (2026-11-18) |
| M2 | Snapshot model, route trie, plugin SPI/registry, upstreams, LB, streaming proxy | done (2026-11-19) |
| M3 | Config worker against etcd | not started |
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

## Known blockers

- BLOCKED: reference benchmark host. The spec's reference host (2 vCPU / 4 GB, Linux) and a separate
  load-generator host are not available; the only host is a macOS arm64 laptop (8 CPU, 16 GB).
  Requester must provide two Linux hosts (gateway + load generator) and a third for the upstream.
- BLOCKED: Kubernetes cluster for multi-replica benchmarks and the rollout drain test. Requester must
  provide a CNCF-conformant cluster >= 1.30 (kind in Docker can cover functional checks only).
- No external OIDC provider: OIDC tests use a containerized IdP or a local JWKS fixture; each test
  states which.
