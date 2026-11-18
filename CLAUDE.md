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
| `gateway-plugins/` | Built-in plugins: auth, authz, rate limit, cache, CORS, transforms, IP rules |
| `gateway-proxy/` | Upstream pools, load balancing, circuit breaker, retries, streaming proxy |
| `gateway-config/` | etcd client wrapper, config worker, snapshot compiler, last-known-good store |
| `gateway-observability/` | Metrics, structured logging, tracing |
| `gateway-app/` | `main`, wiring, Native Image metadata |
| `control-plane/` | Admin API, validation, change sets, etcd writer, audit |
| `admin-console/` | React + TypeScript frontend (pnpm; not a Gradle project) |
| `integration-tests/`, `benchmarks/` | Testcontainers suites; JMH and load scenarios |
| `deployment/` | Dockerfiles, Compose, Kustomize base and overlays |
| `performance/` | k6/wrk2 scripts and raw results |
| `docs/` | Architecture, ADRs (`docs/adr/`), API (OpenAPI), operations, performance |

## Commands

```bash
# Java (always use the wrapper)
./gradlew build                      # compile, unit tests, Checkstyle, SpotBugs, dependency scan
./gradlew :gateway-app:run           # gateway on the JVM (dev)
./gradlew :control-plane:run         # Admin API on the JVM (dev)
./gradlew :integration-tests:test    # Testcontainers suites (needs Docker)
./gradlew :gateway-app:nativeCompile # native binary -> gateway-app/build/native/nativeCompile/
./gradlew nativeTest                 # tests against native code

# Local stack: etcd, Redis, echo upstream, gateway, control plane, console
docker compose -f deployment/docker/compose.yml up -d

# Admin console
cd admin-console
pnpm install
pnpm dev          # dev server, proxies /admin to the local control plane
pnpm lint && pnpm typecheck && pnpm test
pnpm e2e          # Playwright against the Compose stack

# Kubernetes manifests
kustomize build deployment/kubernetes/overlays/<env> | kubeconform -strict -
```

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
- Validate value size and transaction op count before writing; etcd rejects oversized requests.
- Key-schema changes need a versioned migration in `control-plane` and a bump of `meta/schema-version`.
- Secrets: hash credentials; envelope-encrypt anything that must be recoverable. Never store plaintext.

## Plugins

- A plugin = name, version, phase(s), priority, JSON Schema in `config-model/`, and a factory that
  returns an immutable compiled instance. Register it in the static registry.
- Precedence: consumer > route > service > global.
- The gateway and the control plane validate against the same schema file. Change the schema and
  you must update: validation tests, the plugin authoring doc, and any console form overrides.
- Every new plugin needs unit tests, a JVM + native integration test, and a microbenchmark if it
  runs on every request.

## Native Image

- Prefer build-time initialization where safe. Avoid reflection; if unavoidable, add reviewed
  metadata under `gateway-app/src/main/resources/META-INF/native-image/`.
- Before adding a dependency, confirm Native Image support (official docs or the GraalVM
  Reachability Metadata repository) and note it in the PR/commit message.
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
- Logging: structured JSON via SLF4J. Never log tokens, credentials, keys, secrets, or bodies.
  Use route ID, never raw path, as a metric label.
- Frontend: TypeScript strict, no `any` without a comment explaining why. API state via TanStack
  Query. Plugin forms render from the schema served by `GET /admin/v1/plugins/{name}/schema`.
  Tokens in memory only, never `localStorage`. No inline scripts (strict CSP).

## Testing

- Unit tests next to the code (JUnit 5; Vitest for the console).
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
