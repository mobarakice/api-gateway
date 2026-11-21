# PROGRESS

Resume rule: read `CLAUDE.md` and this file, then continue from the last green milestone.

| # | Scope | Status |
|---|---|---|
| M0 | Environment report, architecture, ADRs, risk register, roadmap | done (2026-11-18) |
| M1 | Gradle multi-project, quality gates, Vert.x h1/h2/h2c, first native binary, etcd native spike | done (2026-11-18) |
| M2 | Snapshot model, route trie, plugin SPI/registry, upstreams, LB, streaming proxy | done (2026-11-19) |
| M3 | Config worker against etcd | done (2026-11-21) |
| M4 | Security plugins: JWT, OIDC/JWKS, API keys, RBAC/ABAC, tenant isolation, SSRF | done (2026-11-21) |
| M5 | Traffic plugins: rate limit, cache, breaker, retries, health, transforms, CORS, IP rules | done (2026-11-21) |
| M6 | Control plane | done (2026-11-21) |
| M7 | Admin console | done (2026-11-21) |
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
- Active and passive health checks, retries with budget, and circuit breaker (done in M5).
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

## M4 results (2026-11-21, dev host: macOS arm64, Oracle GraalVM 25.0.4)

Delivered (ADR 0015 authentication + SSRF, ADR 0016 authorization + tenants + consumer precedence,
`docs/security/threat-model.md`):
- **`jwt` plugin**, on JDK crypto only: RS256/384/512, PS256/384/512, ES256/384/512, EdDSA (Ed25519),
  HS256/384/512.
  - Algorithms pinned per issuer; `none` is not configurable and HS/public-key mixing is refused. The
    key family must match the alg, and header-supplied keys (`jku`, `jwk`, `x5u`, `x5c`) are ignored.
  - Strict parsing: duplicate claims, `crit` and padding are rejected.
  - Claims: `exp` required; `nbf`, `iat` and max age with skew; exact `iss`; `aud` intersection; `jti`
    deny-list.
  - Key sources: inline JWKS, secret file, `jwksUri`, or OIDC discovery (HTTPS only, CA pin,
    periodic refresh with jitter, rate-limited refresh on unknown `kid`, async resume).
  - Claims map to the principal: subject, roles, tenant, consumer.
- **`key-auth` plugin**: `<keyId>.<secret>`, salted SHA-256 hashes in `credentials/{consumerId}/{id}`,
  constant-time compare, uniform 401, header hidden from upstream.
- **`authz` plugin**: RBAC + ABAC policies (`policies/authz/*`) compiled into per-route indexes,
  condition trees, deny overrides allow, default deny.
- **Tenant isolation**:
  - `tenantId` on routes, services, upstreams, consumers, credentials and policies;
  - the validator forbids cross-tenant references;
  - tenant routes must authenticate (compile error otherwise);
  - a principal from another tenant gets 403 and a warn log.
- **Consumer-scope precedence** (consumer > route > service > global) via per-consumer override
  instances; `plugin-configs/*` reused through `configRef`.
- **Identity headers** are set only by authentication and always stripped inbound (hardening found
  during M4: before, they were only in the *default* strip list).
- **SSRF** (`AddressPolicy`, `TargetResolver`):
  - deny list unless `settings.upstreamAllowCidrs` covers the address; literal targets are rejected
    at validation;
  - names are resolved off-loop, every answer must pass, and the connection is pinned to the checked
    IP with Host/SNI kept.
- **Revocation**: `policies/revoked-tokens/*` (≤ 100,000), matched locally from the snapshot.
- etcd builder: new subtrees compiled, envelope `tenantId` injected, `credentials/{consumerId}/{id}`
  key-derived IDs.
- JSON Schemas: `jwt.json`, `key-auth.json`, `authz.json`. Docs: authoring guide, `etcd-keys.md`,
  `configuration.md`.

Exit checks, all run and passing:
- `./gradlew build` (Checkstyle, SpotBugs, OSV scan, unit tests): green.
- `./gradlew nativeTest`: 110/110 as native images (core 17, config-model 18, app 18, config 20,
  plugins 17, proxy 20).
- `:integration-tests:integrationTest` 24/24 and `:nativeIntegrationTest` 24/24.
- Security suite:
  - `JwtVerifierTest` (10): all algorithm families; alg confusion (HS256 keyed with the RSA public
    key); `none` in three spellings; tampering; header-key injection; expired/nbf/iat/wrong
    iss/wrong aud; duplicate claims; `crit`; revocation; max age; the cache bound to its key.
  - `SecurityPipelineTest` (7, JVM + native), real pipeline, upstream and HTTPS OIDC fixture:
    - identity spoofing;
    - generic 401/403 bodies with no token, key, upstream or policy leakage;
    - RBAC, deny-over-allow and ABAC;
    - API keys;
    - consumer override precedence;
    - cross-tenant (tenant b and global credentials on a tenant-a route, tenant claim vs consumer
      tenant mismatch);
    - discovery, key rotation and rate-limited forged-kid refresh.
  - `SsrfTest` (4): metadata, private, loopback, ULA, IPv4-mapped, CGNAT and unspecified answers
    never connected; mixed answers refused; pinning (a name that doesn't exist in real DNS is
    reached only through the checked IP); unknown host gives a generic 502.
  - `SecurityConfigTest` (8), `SecurityPluginFactoriesTest` (4), `EtcdSnapshotBuilderTest` (+2).
  - `SecurityIT` (process level, JVM + native):
    - discovery over TLS with RS256/ES256/EdDSA;
    - HS256 from a secret file;
    - alg confusion, `none`, expired, audience, issuer and revoked cases;
    - API keys and cross-tenant 403 (audited in the log);
    - CL+TE and duplicate-CL smuggling (400); a 20 KB header (431); obs-fold header injection
      (unfolded, never a separate header);
    - an SSRF change to 169.254.169.254 rejected while traffic continues;
    - process output scanned: no token, API key secret or HMAC secret appears.
  - Unchanged and green: the gateway etcd role cannot write config (`EtcdClientIT`, `EtcdCheckIT`).
- No external OIDC provider was used. OIDC is tested against a local HTTPS discovery/JWKS fixture
  with real keys and TLS (TestPki CA), not a mock of the plugin.

Dev-host measurements (JMH `AuthBenchmark`, JIT, 1 fork). These are not reference-host results:

| per verification | first sight of a token | repeat token (cache) |
|---|---|---|
| RS256 | 39.2 µs | 1.65 µs |
| ES256 | 359.6 µs | 1.27 µs |
| EdDSA | 390.8 µs | 1.29 µs |
| HS256 | 1.37 µs | 1.14 µs |
| API key | 0.13 µs | – |

- The ES256/EdDSA cost on the event loop was found by this benchmark and led to the
  verified-signature cache (ADR 0015).
- Floods of distinct valid EC/EdDSA tokens remain about 0.4 ms each (threat model, residual risks).
- The native binary is 49.4 MB.

Notes:
- Native image builds log one ERROR line from Netty: `BouncyCastleAlpnSslUtils` cannot find
  `BouncyCastleJsseProvider`. Netty probes for Bouncy Castle while its class initializes at image
  build time, and the binary then uses JDK ALPN. Nothing is logged at run time (native `ProxyIT`
  asserts no ERROR lines). Not verified whether this predates M4.

Not in M4 (planned later):
- HMAC request signing with a replay window (spec §9, "optional"): deferred again in M5. It needs a replay cache;
  Redis is optional, with a local per-gateway fallback.
- Writing `policies/revoked-tokens/*` with etcd leases (TTL = `expiresAt`) so etcd expires them: M6,
  in the control-plane writer. The gateway already ignores entries past `expiresAt`.
- Redis-distributed revocation: M5, together with the other Redis features.
- Certificates (`certificates/`), TLS hot reload, mTLS to upstreams: M5.
- Admin API privilege escalation tests: M6. Console plugin forms for the new schemas: M7.
- Auth metrics (rejections by reason, JWKS refresh state): M8. Today they are in debug/warn logs.

## M5 results (2026-11-21, dev host: macOS arm64, Oracle GraalVM 25.0.4, Redis 8.10.3, etcd v3.7.3)

Delivered (ADR 0017 upstream resilience, ADR 0018 rate limiting + Redis client, ADR 0019 response cache):
- **Resilience** (`gateway-proxy`):
  - per-upstream circuit breaker (failure-rate and slow-call thresholds, half-open trial limit);
  - passive outlier ejection with growing ejection time and `maxEjectionPercent`;
  - process-wide active health checks through the SSRF resolver;
  - availability-aware balancers, plus panic mode when no target is available;
  - retries for idempotent methods only, with jittered backoff and a per-route budget (20% + 3/s);
    bodies are retried only before they were read;
  - mTLS to upstreams (`clientCert`).
- **Plugins** (`gateway-plugins`, all with JSON Schemas in `config-model`):
  - `ip-restriction`: real client IP via trusted proxies;
  - `request-size-limit`: Content-Length check and a streaming cap;
  - `cors`: preflight answered by the gateway; headers also on gateway errors;
  - `request-transform`: path strip/add/template, query and header rename;
  - `rate-limit`: token bucket, fixed window or sliding window; composite keys; local or global
    (Redis, Lua, prefetch); fail-open or fail-closed; `RateLimit` / `RateLimit-Policy` /
    `Retry-After` headers;
  - `cache`: Caffeine L1 plus optional Redis L2, key isolation, Cache-Control rules, miss coalescing,
    in-band stale-while-revalidate, purge by route or tag via `policies/cache-purge/{id}`.
- **SPI** additions: `respondWith`, `respond(..., headers)`, `addResponseHeader`, `tapResponse`,
  `limitRequestBody`. Problem responses now run header filters.
- **Redis**: in-house pipelined RESP2 client (TLS, ACL auth from a password file, per-command
  timeouts, bounded queue and parser, jittered reconnect). Configured by `GW_REDIS_*`.
- **Native metadata**: Caffeine 3.2.4 reachability-repo metadata vendored, plus a reviewed file for
  the four generated classes we use.
- Docs: `configuration.md`, `authoring.md`, `etcd-keys.md`. Tests: `RedisTestNode` in test-support.

Checks (all run on the dev host):
- `./gradlew build` (Checkstyle, SpotBugs, OSV scan, unit tests): green, 162 JVM unit tests, including
  `TrafficPipelineTest` (13), `ResilienceTest` (7), `CircuitBreakerTest` (5), RESP parser, algorithms,
  cache rules, factories.
- `./gradlew nativeTest`: green in all six modules (21 + 17 + 20 + 32 + 33 + 30 tests).
- `./gradlew :integration-tests:integrationTest`: 28/28 green. Includes `TrafficIT` (4) against real
  Redis:
  - a global limit shared by two gateway processes;
  - **Redis paused**: fail-open falls back to local limits, fail-closed returns 503, then recovery;
  - L2 shared across processes;
  - **miss coalescing**: 16 concurrent misses → 1 upstream request;
  - Redis password absent from logs.
- `./gradlew :integration-tests:nativeIntegrationTest`: 28/28 green, the same suites against the
  native binary.
- Native binary: 48.8 MB.
- JMH `TrafficBenchmark` (dev host, JDK 25 JIT, 1 fork, `-prof gc`). The request is a dynamic-proxy
  stand-in, so these are upper bounds:

| Benchmark | ns/op | B/op |
|---|---|---|
| ip-restriction (3 allow CIDRs, 1 deny) | 36.9 | 48 |
| CORS response headers | 36.6 | 120 |
| rate limit, local token bucket, with headers | 84.9 | 440 |
| cache hit (key, L1, purge check, serve) | 159.3 | 767 |

Notes:
- The first integration run failed because the Docker daemon had stopped ("Previous attempts to find
  a Docker environment failed"). After restarting Docker Desktop, all suites passed; no code changed
  in between.
- Rate-limit local state is now size-bounded only (no access expiry): idle keys leave by eviction.
  This keeps us on the Caffeine classes the reviewed metadata covers (ADR 0018).

Not in M5 (planned later):
- HMAC request signing (spec §9 "optional"): not done; it needs a replay cache. Revisit after M6.
- WebSocket upgrade proxying and gRPC trailers / `TE: trailers` passthrough: still open, now M9
  hardening.
- Certificates from etcd (`certificates/`) and TLS hot reload: M6 (control-plane writer) and M9.
- Redis-distributed token revocation: not done; revocation stays snapshot-local (etcd).
- Redis TLS (`rediss://`) is implemented but no integration test covers it. Redis Cluster and
  Sentinel are not supported.
- Background (detached) stale-while-revalidate: we revalidate in-band instead (ADR 0019).
- Breaker, rate-limit and cache metrics: M8; today they are logs only. Console forms for the new
  schemas: M7. Purge API writing `policies/cache-purge/*`: M6.

## M6 results (2026-11-21, dev host: macOS arm64, Oracle GraalVM 25.0.4, etcd v3.7.3)

Built:
- `control-plane`: a Vert.x Admin API (`/admin/v1`; ADR 0020), with no Spring and no new
  dependencies.
  - CRUD for routes, services, upstreams, consumers, credentials, plugin-configs, global-plugins,
    `policies/{type}` and certificates (references only), plus settings.
  - Plugin catalog and schemas; change sets (create, stage, unstage, discard, validate, diff,
    publish); history and rollback; `GET /gateways` with `behind`; `GET /audit`; health and ready.
  - ETag / If-Match, 428 when it is missing, cursor pagination, and RFC 9457 errors with field
    paths.
- Publish: one etcd transaction with `mod_revision` compares on every touched key plus
  `history/head`; re-validated on retry; op and byte limits enforced. The dry run reuses the
  gateway's snapshot builder and compiler.
- Auth: OIDC bearer tokens through the gateway's JWT verifier, using pinned asymmetric algorithms;
  roles viewer < editor < publisher < admin.
- API keys are generated server side and shown once; only the hash is stored, and hashes are
  removed from every read.
- Audit (ADR 0021): successful mutations are written to etcd in the same txn, the log and an
  optional JSON Lines sink. Refused attempts go to the log and sink only.
- Schema migration v1 for `meta/schema-version`. A new etcd role `control-plane` can write the config
  subtrees, `changesets/`, `history/` and `audit/`, but not `nodes/`.
- Shared changes:
  - `EtcdHttpClient.txn` plus `Txn`.
  - A `ValidationOnly` plugin service: a JWT `secretFile` is checked for shape only.
  - `OidcKeySources.validationOnly`.
  - `JsonLogging.event`.
- Docs: OpenAPI 3.1 `docs/api/admin-v1.yaml` (Redocly lint: valid, 52 warnings, all missing
  `operationId`s), `docs/operations/control-plane.md`, and `etcd-keys.md` updated.

Checks:
- `./gradlew build` (with the OSV scan): green.
- Control-plane unit tests: 22 (PublisherTest, AdminApiTest, AuditLogTest, AdminAuthTest).
- `./gradlew nativeTest`: green in all 7 modules (config-model 21, gateway-config 20, core 17,
  proxy 33, plugins 32, app 31, control-plane 22).
- `ControlPlaneIT` (3-member etcd with TLS and auth, the control plane over HTTPS, 3 gateway
  processes), JVM and native: 7/7 each. It covers:
  - Exit 1: publish to live on all 3 gateways, measured from sending the publish to the last
    gateway serving the route, 5 samples. Native 115–193 ms; JVM 135–154 ms. Target ≤ 2 s.
    `GET /gateways` shows 3 nodes, none behind.
  - Exit 2: concurrent edits give 412 with the conflicting key; staging against a stale ETag gives
    412.
  - Exit 3: rollback restores v1 on all 3 gateways (about 100 ms), and `rollbackOf` is recorded.
  - An API key issued through the API works on all gateways through key-auth plus rate-limit plus
    cache. The secret is not in etcd, the control-plane output, the audit sink or any API response.
  - Privilege escalation is refused: viewer write, editor publish/rollback, publisher
    settings/audit, wrong audience, `alg: none` and no token. Denials are audited with the subject.
  - etcd least privilege: the control plane cannot write `nodes/`; gateways cannot write config,
    `changesets/` or `audit/`.
  - Gateways keep serving while the control plane is down; after a restart, publishing works.
- Full integration suites: JVM 35/35, native 35/35.
- Native control-plane binary: 48.4 MB.

Fixed along the way:
- The plugin schemas were not in the native image (resource metadata added).
- `System.exit` was called on an event loop and deadlocked against the shutdown hook (now exits on a
  fresh thread).
- `TrafficPipelineTest` purge race: an entry stored in the purge's own millisecond counts as purged,
  and the native run was fast enough to hit that.

Not in M6 (planned later):
- Tenant-scoped admins: roles are global today, so tenant admins wait for the console's needs (M7).
- Revoked-token keys with etcd leases: not done; the gateway ignores expired entries, and they are
  removed through the API.
- Envelope encryption: nothing recoverable is stored yet. Required before any recoverable secret
  field ships.
- External HTTP audit sink: the file sink and logs feed the pipeline.
- Watch relay for large fleets (risk R5).
- Admin API metrics: M8.
- Certificate material loading and TLS hot reload on gateways: M9.
- Interop against a real IdP: still a local JWKS file in tests.
- Propagation was measured on the dev host with a local etcd cluster in Docker, not on the reference
  topology.

## M7 results (2026-11-21, dev host: macOS arm64, Node 22.13, Docker Desktop, Chromium 158 headless)

Built:
- **`admin-console/`** (ADR 0022): React 19, TypeScript 6 strict, Vite 8, TanStack Query, React Router 7.
  - Screens: gateways, routes, services, upstreams, consumers and credentials, plugin configs,
    global plugins, authz policies, revoked tokens, cache purges, certificates, settings, the plugin
    catalog, change sets (ops, validate, diff, publish), history and rollback, and audit.
  - Sign-in: OIDC authorization code + PKCE (`oidc-client-ts`) with tokens in memory. A reload re-enters
    through the IdP's SSO session.
  - Plugin forms render from the served JSON Schemas, with client validation (`@cfworker/json-schema`,
    no `eval`). Server field errors appear on the field and in an error summary.
  - The UI is role-aware.
  - API keys are shown once.
  - Publish and rollback always show a diff and need confirmation.
- **Control plane:** `CP_CORS_ORIGINS` (exact origins, no credentials) plus `CorsTest`.
- **Local stack:** `deployment/docker/compose.yml` runs etcd (TLS + auth), Redis (ACL), an echo upstream,
  2 gateways, the control plane, Keycloak 26 (realm with 4 role users) and the console on
  static-web-server.
  - A generated dev PKI and passwords live in `.local/` (git-ignored).
  - Services run hardened: non-root, read-only, all capabilities dropped.
- **Docs:** ADR 0022, `docs/operations/admin-console.md`, and the CORS addition to `control-plane.md` and
  the OpenAPI spec.

Checks:
- `pnpm lint` (typescript-eslint strict type-checked plus jsx-a11y strict; `localStorage` banned):
  clean.
- `pnpm typecheck`: clean.
- `pnpm test` (Vitest): 20/20.
  - Every built-in plugin schema renders, and client validation maps errors like the server.
  - Role gating, If-Match on staging, inline server errors, the publish confirmation, and the API key
    shown once and then dropped.
- `pnpm e2e` (Playwright against the Compose stack, no mocks): 8/8, two consecutive runs.
  - The console is served with a strict CSP, including Trusted Types, and has no inline script or style.
  - **No CSP violation** was recorded on any console page (a `securitypolicyviolation` listener plus
    console errors).
  - axe-core WCAG 2.0/2.1/2.2 A+AA reports 0 violations on gateways, settings, the route editor with
    plugin forms, the route editor with errors, the change set, the publish dialog and audit.
  - Flow:
    - an admin allows the Compose network;
    - an editor stages an upstream, a service, a consumer with an API key, and a route with key-auth
      plus rate-limit through the schema form, then validates;
    - the viewer is read-only;
    - a server field error shows on its field;
    - a publisher publishes after reviewing the diff;
    - both gateways serve the route with the API key (401 without it) **232–269 ms after the publish
      click** (target ≤ 2 s);
    - a rollback removes the route from both gateways;
    - the admin sees the audit trail, and sign-out works.
- `./gradlew build`: green. `:control-plane:nativeTest`: 25/25. `ControlPlaneIT` (JVM): green.

Not in M7 (planned later):
- Native, distroless images for the gateway and control plane, and Kubernetes manifests including
  the console's Deployment and NetworkPolicy: M9. The Compose stack uses JVM images today.
- Staging checks each object on its own: decoding, unknown fields, references-free rules. Some rules
  (for example route path syntax) are only reported by **Validate** and publish, which run the full
  compiler.
- Tenant-scoped admin roles: still global roles.
- Console metrics and RUM: none. A visual design review on real devices: not done; the checks were
  axe plus keyboard-only flows in e2e.
- Upgrade TypeScript 7, ESLint 10, React Router 8 and jsdom 30+ once typescript-eslint and
  jsx-a11y support them and the toolchain moves to Node ≥ 22.22.

## Known blockers

- BLOCKED: reference benchmark host. The spec's reference host (2 vCPU / 4 GB, Linux) and a separate
  load-generator host are not available; the only host is a macOS arm64 laptop (8 CPU, 16 GB).
  Requester must provide two Linux hosts (gateway + load generator) and a third for the upstream.
- BLOCKED: Kubernetes cluster for multi-replica benchmarks and the rollout drain test. Requester must
  provide a CNCF-conformant cluster >= 1.30 (kind in Docker can cover functional checks only).
- OIDC interop: the console e2e runs against a real IdP (Keycloak 26 in Compose) through the
  control plane. The gateway's `jwt` plugin is still tested with a local HTTPS discovery/JWKS
  fixture only (`SecurityPipelineTest`, `SecurityIT`).
