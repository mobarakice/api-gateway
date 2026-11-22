# CLAUDE.md — API Gateway

Custom, high-performance API gateway in Java 25, compiled with GraalVM Native Image.
Configuration lives in etcd; gateways pick up changes through an etcd watch worker.
Admins edit configuration in a separate web console that talks only to the Admin API.

Read this file and `PROGRESS.md` at the start of every session. Keep both true:
if you change a command, module, or convention, update this file in the same commit.

## Repository map

| Path | What it is |
|---|---|
| `build-logic/` | Gradle convention plugins (java, quality, native). Shared build rules live here, not in module scripts |
| `gradle/libs.versions.toml` | Every dependency and plugin version. Never hardcode versions in module build files |
| `config-model/` | Config DTOs and JSON Schemas shared by gateway and control plane |
| `gateway-core/` | Request pipeline, routing trie, immutable snapshot model, plugin SPI and registry |
| `gateway-plugins/` | Built-in plugins: `jwt` (+ OIDC key sets), `key-auth`, `authz`, `headers`, `cors`, `ip-restriction`, `request-size-limit`, `request-transform`, `rate-limit` (ADR 0018), `cache` (ADR 0019); minimal RESP2 Redis client (`redis/`). Test fixtures (`TestJwt`, JDK-signed tokens) in `src/testFixtures` |
| `gateway-proxy/` | Upstream pools, load balancing, SSRF connect-time resolver (`TargetResolver`), circuit breaker, passive ejection + active health checks, retries with budget (ADR 0017), response tap, streaming proxy |
| `gateway-config/` | etcd HTTP/JSON client, config worker (`worker/`: sync, watch, LKG, heartbeat — ADR 0014), file source |
| `gateway-observability/` | In-house metric registry + Prometheus text (`metrics/`), W3C trace context + OTLP/HTTP JSON exporter (`tracing/`), SLF4J JSON log provider (`logging/`) — ADR 0007, 0023. No Micrometer/OTel SDK. Gateway wiring in `gateway-app/.../telemetry/`; core SPI in `gateway-core/.../observe/` |
| `gateway-app/` | `main`, wiring, Native Image metadata |
| `control-plane/` | Admin API (Vert.x, `api/`), OIDC auth + roles (`auth/`), change sets, dry-run validation and single-txn publish/rollback (`changes/`, ADR 0020), audit (`audit/`, ADR 0021), etcd store (`store/`), migrations |
| `test-support/` | Test-only helpers: throwaway PKI (`TestPki`), etcd Testcontainers cluster/node (`EtcdTestCluster`, `EtcdTestNode`) with TLS + auth + gateway and control-plane roles, fixed ports, kill/restart/pause; Redis node (`RedisTestNode`, ACL user, pause/resume). Never shipped |
| `admin-console/` | React 19 + TypeScript (strict) + Vite console (pnpm; not a Gradle project; ADR 0022): `src/api` (client, TanStack Query hooks), `src/auth` (OIDC code + PKCE, memory-only tokens), `src/schema` (JSON Schema forms + `@cfworker/json-schema`), `src/forms`, `src/pages`; Playwright e2e in `e2e/` |
| `integration-tests/`, `benchmarks/` | Testcontainers suites; JMH and load scenarios |
| `deployment/docker/` | Multi-stage Dockerfiles: `*.native.Dockerfile` (GraalVM build stage → distroless, uid 10001; default), `*.jvm.Dockerfile`, console; `etcd-tools/` (etcdctl/etcdutl + rclone, `etcd-ops` backup/restore/defrag). `compose.yml` = local stack (etcd TLS+auth, Redis, echo, 2 gateways, control plane, Keycloak, console, Prometheus, Grafana, Jaeger) on native images, `compose.jvm.yml` override for JVM images; init service writes certs/passwords to git-ignored `docker/.local/` |
| `deployment/kubernetes/` | Kustomize `base/` (gateway, control-plane, console, etcd StatefulSet + backup/defrag CronJobs, NetworkPolicies, PSA restricted), `components/monitoring` (PodMonitors, generated PrometheusRule), `overlays/{dev,staging,prod}`, `validate.sh`, `scripts/` (kind cluster, dev secrets, token minting, seed, drain test, backup/restore rehearsal). ADR 0024; `docs/operations/kubernetes.md`, `etcd-backup-restore.md` |
| `deployment/observability/` | Prometheus scrape config, recording/alert rules + promtool tests, Grafana dashboard JSON and provisioning. Runbooks: `docs/operations/observability.md` |
| `performance/` | k6/wrk2 scripts and raw results |
| `docs/` | Architecture, ADRs (`docs/adr/`), API (OpenAPI), operations, performance |

## Commands

```bash
# Java (always use the wrapper)
./gradlew build                      # compile, unit tests, Checkstyle, SpotBugs, dependency scan
GW_CONFIG_FILE=path/to/gateway.json ./gradlew :gateway-app:run   # gateway on the JVM (dev), file config
GW_ETCD_ENDPOINTS=https://localhost:2379 GW_ETCD_CA=... GW_ETCD_USER=gateway GW_ETCD_PASSWORD_FILE=... \
  GW_ETCD_PREFIX=/gw/dev/ GW_LKG_DIR=/tmp/gw-lkg ./gradlew :gateway-app:run   # etcd config (all env vars: docs/operations/configuration.md)
# Redis (global rate limit, L2 cache): GW_REDIS_URL=redis://localhost:6379 GW_REDIS_USER=gateway GW_REDIS_PASSWORD_FILE=...
./gradlew :control-plane:run         # Admin API on the JVM (dev); CP_* env vars: docs/operations/control-plane.md
./gradlew :control-plane:nativeCompile  # -> control-plane/build/native/nativeCompile/control-plane
./gradlew :integration-tests:integrationTest        # Testcontainers suites, gateway as JVM process (needs Docker)
./gradlew :integration-tests:nativeIntegrationTest  # same suites against the native binary (needs Docker)
./gradlew :gateway-app:nativeCompile # native binary -> gateway-app/build/native/nativeCompile/gateway
./gradlew nativeTest                 # unit tests as native images (config-model, gateway-config, core, proxy, plugins, app, control-plane)
./gradlew :benchmarks:jmh -Pjmh.args="RouterBenchmark -prof gc"   # JMH; results in benchmarks/build/results/jmh
./gradlew :gateway-app:installDist   # jvm-prod distribution -> gateway-app/build/install/gateway/
gateway-app/build/native/nativeCompile/gateway check-etcd --help   # etcd connectivity/role check

# Container images (native, distroless, non-root; Linux binaries built inside Docker)
docker build -f deployment/docker/gateway.native.Dockerfile -t apigw/gateway:0.9.0 .        # --build-arg NATIVE_GC=G1
docker build -f deployment/docker/control-plane.native.Dockerfile -t apigw/control-plane:0.9.0 .
docker build -f deployment/docker/etcd-tools/Dockerfile -t apigw/etcd-tools:0.9.0 deployment/docker/etcd-tools

# Local stack: etcd, Redis, echo upstream, 2 gateways, control plane, Keycloak, console (native images)
docker compose -f deployment/docker/compose.yml up -d --build   # console http://localhost:8280; users: docs/operations/admin-console.md
# JVM images instead: ./gradlew :gateway-app:installDist :control-plane:installDist, then
#   docker compose -f deployment/docker/compose.yml -f deployment/docker/compose.jvm.yml up -d --build
# Grafana http://localhost:3000 (admin / docker/.local/secrets/grafana-admin), Prometheus :9090, Jaeger :16686
# Alert rules: promtool check/test (docs/operations/observability.md); ObservabilityIT runs them against real Prometheus

# Admin console
cd admin-console
pnpm install
pnpm dev          # dev server, proxies /admin to the local control plane
pnpm lint && pnpm typecheck && pnpm test
pnpm e2e          # Playwright against the Compose stack (once: pnpm exec playwright install chromium)

# Kubernetes manifests
kustomize build deployment/kubernetes/overlays/<env> | kubeconform -strict -
deployment/kubernetes/validate.sh    # all overlays x k8s 1.30/1.34, CRD schemas, PrometheusRule drift (kubeconform in Docker)
# kind rehearsal (functional only): docs/operations/kubernetes.md#local-rehearsal-on-kind
#   scripts/drain-test.sh (rollouts under load, zero failures) and scripts/backup-restore.sh (etcd total loss + restore)
```

`./gradlew build` needs network (OSV dependency scan, ADR 0010) but not Docker.
The toolchain is Oracle GraalVM 25 (auto-detected via `nativeImageCapable`; never auto-downloaded).

Before saying a change is done, run `./gradlew build` and the tests for what you touched.
For anything in the request path or config worker, also run the native build and `nativeTest`.

## Architecture invariants (do not break)

1. **Nothing blocks an event loop.** No blocking I/O, `Thread.sleep`, `synchronized` around I/O,
   or `.join()`/`.get()` on futures in Vert.x handlers. Blocking work goes to a bounded worker pool.
2. **No etcd calls in the request path.** Requests read only the current in-memory snapshot.
   Redis is the only network call allowed in the request path, and only for opt-in global rate
   limiting and L2 cache, always with a timeout and a configured fail-open/fail-closed mode.
3. **Snapshots are immutable and swapped atomically.** Build a complete new snapshot, validate it,
   compile it, then publish with one atomic reference swap. Never mutate a live snapshot.
4. **Only the control plane writes to etcd config.** Gateways use a read-only role on config
   prefixes; their only write is their own leased `nodes/{gatewayId}` heartbeat key.
   The admin console never talks to etcd.
5. **Publishing is one etcd transaction** with `mod_revision` compares on every touched key.
   Gateways must never be able to observe half a change set.
6. **The config worker never stops traffic.** etcd down, compaction, invalid config: keep serving
   the current or last-known-good snapshot, report status, resync, retry with backoff and jitter.
   Never apply part of a batch. Never report ready with an empty config.
7. **Plugins are compiled in** and registered statically. No reflection-based discovery,
   runtime class loading, or scripting.
8. **Per-route plugin chains are built at snapshot compile time.** A disabled feature must cost
   nothing per request — no per-request flag checks or config lookups.
9. **Bodies stream** with backpressure. Buffer only when a plugin needs it, within a size cap.

## Configuration in etcd

- Key layout: `/gw/{env}/{routes|services|upstreams|consumers|credentials|plugin-configs|global-plugins|policies|certificates}/{id}`,
  plus `meta/schema-version`, `changesets/`, `history/`, and leased `nodes/{gatewayId}` heartbeats.
  The full reference is `docs/architecture/etcd-keys.md`; update it when keys change.
- Values are JSON with `id`, `kind`, `schemaVersion`, `tenantId` (if scoped), `updatedAt`, `updatedBy`.
- Control-plane keys: `changesets/{id}`, `history/head` (compared by every publish),
  `history/entries/{seq}`, `audit/{micros}-{rand}`. The control-plane etcd role cannot write `nodes/`.
- The control plane validates by running the gateway's own `EtcdSnapshotBuilder` + `SnapshotCompiler`
  with `ValidationOnly` services: a plugin factory must not read gateway-local files or fetch
  anything when `PluginContext.validationOnly()` is true.
- Validate value size and transaction op count before writing; etcd rejects oversized requests.
- Key-schema changes need a versioned migration in `control-plane` and a bump of `meta/schema-version`.
- Secrets: hash credentials; envelope-encrypt anything that must be recoverable. Never store plaintext.
  API keys: `credentials/{consumerId}/{id}` holds `keyId` + `sha256$salt$digest` (ADR 0015).
- Upstream targets in private/loopback/metadata ranges are rejected unless covered by
  `settings.upstreamAllowCidrs` (ADR 0015). Test and dev configs on loopback must allow `127.0.0.0/8`.

## Plugins

- A plugin = name, version, phase(s), priority, JSON Schema in `config-model/`, and a factory that
  returns an immutable compiled instance. Register it in the static registry.
- Precedence: consumer > route > service > global. Consumer overrides swap instances at run time
  and only for plugins already in the route chain (ADR 0016).
- Authenticating plugins return `authenticates() == true` and call `exchange.authenticate(principal)`;
  identity headers (`x-consumer-id`, `x-tenant-id`, ...) are set only there and always stripped inbound.
  Tenant routes must have one in their chain.
- The gateway and the control plane validate against the same schema file. Change the schema and
  you must update: validation tests, the plugin authoring doc, and any console form overrides.
- Every new plugin needs unit tests, a JVM + native integration test, and a microbenchmark if it
  runs on every request.

## Native Image

- Prefer build-time initialization where safe. Avoid reflection; if unavoidable, add reviewed
  metadata under `gateway-app/src/main/resources/META-INF/native-image/`.
- Before adding a dependency, confirm Native Image support (official docs or the GraalVM
  Reachability Metadata repository) and note it in the PR/commit message.
- Required flags live in `build-logic/.../gw.native-conventions.gradle.kts` (e.g. `-H:+SharedArenaSupport`
  for Netty on JDK 25 — ADR 0008, still required on 25.0.4). Netty's HTTP/3, native QUIC and tcnative jars
  are excluded build-wide; `netty-codec-classes-quic` must stay (Vert.x `remoteAddress()` needs it) and
  its package plus Netty `SslContext` initialise at run time (ADR 0003 amendment).
- Modules whose code runs in the request path apply `gw.native-conventions` so `nativeTest` covers them.
- Caffeine: reachability-repo metadata is vendored, plus a reviewed file in
  `gateway-plugins/.../META-INF/native-image/apigw/gateway-plugins/` for the generated cache classes we use
  (SSMS/SSMW/PSMS/PSMW). A new Caffeine builder option (expiry, refresh, stats) needs its classes added there.
- The GraalVM reachability metadata repository is off by default (`-Pgw.metadataRepository=true`).
- Container builds add `-Pgw.nativeStaticLibc=true` (everything static except glibc, for distroless `base`) and
  `-Pgw.nativeGc=serial|G1` (ADR 0008 amendment). Images run as uid 10001 with a read-only rootfs and no capabilities.
- The tracing agent is for discovery only. Review and trim what it generates before committing.
- A feature is not done until it works in the native binary, not just on the JVM.

## Code conventions

- Java 25, records for immutable data, sealed types for closed hierarchies.
  No Lombok. No field injection. Constructor wiring in `gateway-app`.
- Hot path: avoid per-request allocation in routing and plugin checks, no regex compilation,
  no string concatenation for keys where a precomputed value works, no streams in tight loops.
  Prove optimizations with a JMH benchmark in `benchmarks/`.
- Errors to clients: generic body with a request ID (RFC 9457 problem details on the Admin API).
  Never leak upstream hosts, IPs, stack traces, or config details.
- Logging: structured JSON via SLF4J (our provider in `gateway-observability`; no Logback/Log4j2). Never log tokens, credentials, keys, secrets, or bodies.
  Use route ID, never raw path, as a metric label. Per-route meters are resolved at snapshot compile time
  (`RouteTelemetryFactory`) and pruned on publish; a new metric/alert/dashboard panel needs the catalogue in
  `docs/operations/observability.md` updated, and alerts need a promtool test case and a runbook section.
- Frontend: TypeScript strict, no `any` without a comment explaining why. API state via TanStack
  Query. Plugin forms render from the schema served by `GET /admin/v1/plugins/{name}/schema`.
  Tokens in memory only, never `localStorage`. No inline scripts or `style` props (strict CSP with Trusted
  Types); no `eval`-based libraries (e.g. Ajv) in the browser. Pin npm versions exactly (`.npmrc` save-exact).
  Every page must pass axe (WCAG 2.2 AA) in the e2e suite.

## Testing

- Unit tests next to the code (JUnit 5; Vitest for the console). Shared test helpers that must run in
  native images go in a module's `testFixtures` (not `test-support`, which carries Testcontainers and
  Bouncy Castle).
- Integration tests use Testcontainers with the official etcd and Redis images. Don't mock etcd
  in integration tests.
- Config worker changes must keep these cases green: etcd down at boot, etcd restart, leader
  change, compaction during watch, invalid change rejected, LKG restore.
- Security changes must keep the security suite green: JWT alg confusion, expired/wrong-issuer/
  wrong-audience tokens, cross-tenant access, smuggling, SSRF incl. DNS rebinding, Admin API
  privilege escalation, gateway etcd role cannot write, no secrets in logs or responses.
- If Docker, GraalVM, or hardware for a test is unavailable, mark it `BLOCKED: <reason>` in
  `PROGRESS.md`. Never report a mock, a JVM run, or an estimate as the real thing.

## Decisions and progress

- Significant choices get a short ADR in `docs/adr/NNNN-title.md`: context, options, decision,
  performance, memory, Native Image, and operational impact.
- `PROGRESS.md` holds the milestone checklist (M0–M10), what passed, and what is `BLOCKED`.
  Update it at the end of every milestone and whenever you stop.
- Commit at each green milestone. Don't commit generated binaries or benchmark raw data over 10 MB.

## Never

- Add APISIX, Kong, NGINX, Envoy, Spring Cloud Gateway, PostgreSQL, Kafka, RabbitMQ, or Maven.
- Add Spring Boot without an accepted ADR.
- Hardcode credentials, keys, or environment-specific hosts.
- Implement cryptographic primitives.
- Claim benchmark numbers you didn't run, or trade correctness/security for throughput.
- Leave TODOs or stubs in request-path, security, or config-worker code.
