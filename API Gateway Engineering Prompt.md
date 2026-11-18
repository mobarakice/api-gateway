You are the lead engineer on a custom, production-oriented API gateway written in Java and compiled with GraalVM Native Image. You will design it, build it, test it, benchmark it, and document it in this repository. Deliver working, compiled, tested code, not recommendations or snippets.

The system has four parts in one repository:

1. **Gateway** (data plane): owns routing, authentication, authorization, rate limiting, caching, circuit breaking, load balancing, and proxying, all through a compiled-in plugin model.
2. **Control plane**: the Admin API. The only writer to etcd.
3. **Admin console**: a separate web frontend for managing routes, upstreams, consumers, and plugin configuration through the Admin API.
4. **etcd**: the single source of truth for configuration. Gateways learn about changes through an etcd watch worker, not a message broker.

The throughput goal is **100,000 RPS per documented hardware configuration**. It is a target to measure against, not a claim. You will report what you actually measure.

### Environment and targets

The requester fills this in. If a field is blank, use the default and record that you did.

| Item | Value            | Default if blank |
| --- |------------------| --- |
| Reference gateway host | 2 vCPU, 4 GB RAM | 8 vCPU, 16 GB RAM, Linux x86\_64 |
| Load generator host |                  | Separate host, same network, ≥ gateway's vCPU |
| Upstream |                  | Minimal native HTTP echo server on a third host, \~0 ms service time |
| etcd |                  | Dedicated 3-node cluster, latest stable 3.x, TLS + auth, SSD storage. Never the Kubernetes cluster's own etcd |
| Target throughput |                  | 100K RPS on the reference host, full pipeline minus TLS |
| Gateway-added latency |                  | p50 ≤ 0.5 ms, p99 ≤ 2 ms, p99.9 ≤ 5 ms at 70% of max sustainable RPS |
| Config propagation |                  | Committed change live on all gateways in ≤ 2 s (p99) |
| Native cold start |                  | ≤ 100 ms to serving with a last-known-good snapshot |
| Idle RSS (native) |                  | ≤ 64 MB |
| GraalVM distribution |                  | Oracle GraalVM (G1 + PGO available); fall back to CE and document |
| Admin console stack |                  | React + TypeScript + Vite, pnpm |
| Kubernetes target |                  | Any CNCF-conformant cluster ≥ 1.30 |

"Gateway-added latency" = latency through the gateway minus latency direct to the upstream, same load, same hosts.

### 2. How to work

1. **Inspect first.** Read the repo and `CLAUDE.md`. Check `java -version`, `native-image --version`, `./gradlew --version` (or `gradle --version` before the wrapper exists), `node --version`, `pnpm --version`, `docker info`, `kubectl version`, `etcdctl version`, network access. Write findings to `docs/environment.md`.
2. **Plan briefly, then build.** Produce the architecture overview, module list, ADR list, and risk register (M0), then start M1 in the same session. Don't wait for approval unless a decision is irreversible and genuinely ambiguous.
3. **Work in milestones** (section 12). Each ends with its exit checks run and passing, a commit, and an updated `PROGRESS.md`.
4. **Decide, then record.** When you choose between options, write a short ADR (`docs/adr/NNNN-title.md`): context, options, decision, performance, memory, Native Image impact, operational impact.
5. **Resume cleanly.** A new session starts by reading `CLAUDE.md` and `PROGRESS.md` and continues from the last green milestone.
6. **Verify versions live.** Use the latest stable, mutually compatible versions at implementation time. Confirm each Java dependency's Native Image support (official docs or the GraalVM Reachability Metadata repository) before adding it. Pin all versions in `gradle/libs.versions.toml`.
7. **Keep `CLAUDE.md` true.** When a command, module, or convention changes, update it in the same commit.

#### Environment gaps protocol

If something required isn't available (no GraalVM, no Docker for Testcontainers, no network, too little hardware for a benchmark tier):

- Stop that item, mark it `BLOCKED: <reason>` in `PROGRESS.md`, and say exactly what the requester must provide.
- Keep going on everything else that doesn't depend on it.
- Never substitute silently: no JVM run reported as native, no mocks reported as integration tests, no extrapolated numbers reported as measured.

### 3. Hard constraints

1. No APISIX, Kong, NGINX, Envoy, or Spring Cloud Gateway in the request path. No hybrid with them.
2. No Spring Boot unless an ADR proves a need that outweighs its startup and footprint cost.
3. No PostgreSQL, Kafka, or RabbitMQ. etcd is the only configuration store and the only change-distribution mechanism.
4. Gradle only (Kotlin DSL, wrapper committed). No Maven build files.
5. The data plane is non-blocking. Never block a Vert.x/Netty event loop. Blocking work runs on bounded worker pools, never per request.
6. No etcd calls in the request path. Requests read only the in-memory snapshot. Redis is allowed in the request path only for opt-in global rate limiting or L2 cache, with timeouts and a configured fail-open/fail-closed mode.
7. Only the control plane writes to etcd. Gateways have a read-only etcd role. The admin console never talks to etcd.
8. Plugins are compiled in. No runtime class loading, scripting engines, or classpath scanning in the gateway.
9. Native Image is a first-class target: the native binary is what ships and what is tested.
10. Never trust client-supplied identity or tenant headers. Strip and re-derive them.
11. Never log secrets, tokens, credentials, private keys, or bodies by default. Never return a stored secret through the Admin API.
12. Never expose upstream hostnames, IPs, or stack traces in public errors.
13. Never implement cryptographic primitives yourself.
14. Never claim a benchmark result you didn't run. Never trade correctness or security for a better number.
15. No TODOs or stubs in shipped critical paths. If something can't be done safely, say so and propose the alternative.

### 4. Stack

| Concern | Default | Notes |
| --- | --- | --- |
| Language / runtime | Java 25, GraalVM Native Image for JDK 25 | Confirm the native-image release matches the JDK |
| Networking | Vert.x 5 (on Netty) | Drop to raw Netty handlers only where a profile shows Vert.x overhead matters |
| Build | Gradle (Kotlin DSL), wrapper, `gradle/libs.versions.toml`, convention plugins in `build-logic/` | Java toolchains; configuration cache on; `org.graalvm.buildtools.native` for `nativeCompile` / `nativeTest` |
| Build variants | `run` (JVM dev), `jvm-prod` distribution, `nativeCompile` | Same code, same config for JVM vs native comparisons |
| Config store | etcd v3 cluster | Single source of truth |
| Config distribution | etcd watch, consumed by the config worker in each gateway | No broker |
| etcd client | jetcd if its Native Image support is verified in an M1 spike; otherwise etcd's v3 HTTP/JSON gateway via Vert.x WebClient | ADR required; both must support TLS, auth, range, txn, watch, leases |
| Global rate limit / L2 cache | Redis | Atomic Lua scripts |
| L1 cache | Caffeine | Check native metadata |
| JWT / JOSE | Nimbus JOSE+JWT or equivalent | Native-compatible, no custom crypto |
| Plugin config schemas | JSON Schema (2020-12) with a native-compatible validator | Same schemas served to the admin console |
| Metrics | Micrometer + Prometheus, or OpenTelemetry metrics | Pick one via ADR |
| Tracing | OpenTelemetry, W3C Trace Context | Sampling configurable, overhead bounded |
| Logging | SLF4J + a native-friendly backend, JSON output | Async, bounded buffer; ADR |
| Admin console | React + TypeScript (strict) + Vite, pnpm | TanStack Query for API state; form library that renders from JSON Schema |
| Tests | JUnit 5, Testcontainers (etcd, Redis), `nativeTest`; Vitest + Playwright for the console |  |
| Load | k6 (constant-arrival-rate) and/or wrk2 / Gatling | Coordinated-omission safe |
| Quality | Checkstyle, SpotBugs, dependency vulnerability scan, ESLint, `tsc --noEmit` | Fail the build on violations |

### 5. Architecture

**Deployables:** gateway (native binary), control plane (Admin API; native preferred), admin console (static assets in its own container), etcd cluster, Redis (optional, for global rate limits and L2 cache).

**Data plane components:** HTTP server and TLS (ALPN for h2, plus h2c), request validation, route resolution, plugin chain, upstream connection pools, streaming proxy, telemetry, config worker.

**Control plane modules:** routes, services/upstreams, consumers/tenants and credentials, plugin catalog and plugin configs, policies, certificates, validation, change sets, publish and rollback, audit, gateway status.

Suggested layout (merge or split with an ADR):

```
settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml  build-logic/
gateway-core/            pipeline, routing, snapshot model, plugin SPI
gateway-plugins/         built-in plugins (auth, authz, rate limit, cache, cors, transforms, ...)
gateway-proxy/           upstream pools, load balancing, breaker, retries, streaming proxy
gateway-config/          etcd client wrapper, config worker, snapshot compiler, LKG store
gateway-observability/
gateway-app/             main, wiring, native-image config
control-plane/           Admin API, validation, change sets, etcd writer, audit
config-model/            shared config DTOs + JSON Schemas (used by gateway and control plane)
admin-console/           frontend (pnpm workspace, not a Gradle project)
benchmarks/  integration-tests/
deployment/  performance/  docs/
```

### 6. Configuration in etcd

**Key layout** (prefix configurable, one prefix per environment):

```
/gw/{env}/routes/{id}
/gw/{env}/services/{id}
/gw/{env}/upstreams/{id}
/gw/{env}/consumers/{id}
/gw/{env}/credentials/{consumerId}/{id}
/gw/{env}/plugin-configs/{id}          reusable plugin config sets
/gw/{env}/global-plugins/{name}
/gw/{env}/policies/{type}/{id}
/gw/{env}/certificates/{id}
/gw/{env}/meta/schema-version
/gw/{env}/changesets/{id}              drafts, not watched by gateways
/gw/{env}/history/{seq}                published change sets, bounded to last N
/gw/{env}/nodes/{gatewayId}            leased heartbeat: applied revision, status
```

**Values:** JSON with `id`, `kind`, `schemaVersion`, `tenantId` (where scoped), `updatedAt`, `updatedBy`, and the object body. Keep each value well under etcd's request size limit; validate sizes before writing.

**Writes (control plane only):**

- Edits go into a change set (draft). Publishing applies the whole change set in one etcd transaction, so gateways never see half a change. Respect etcd's per-transaction operation limit; split only along boundaries that are safe to apply separately, and document how.
- Optimistic concurrency: each transaction compares `mod_revision` of every touched key. The Admin API exposes this as `ETag` / `If-Match`.
- Referential integrity (route → service → upstream, plugin config references) is checked by the control plane inside the publish transaction's compare set.
- Secrets: credentials are stored hashed; anything that must be recoverable is envelope-encrypted with a key from the secret manager before it reaches etcd.
- Rollback = publish the inverse of a recorded change set as a new transaction. Disaster recovery = etcd snapshot restore (section 10).
- Key-schema changes go through versioned migrations run by the control plane and recorded in `meta/schema-version`.

**Config worker (in every gateway, off the event loop):**

1. On boot, load the last-known-good snapshot (with its revision) from local disk and start serving from it if present.
2. Range-read the prefix at a single revision (paginated), build a full snapshot, validate, compile, swap, persist to disk.
3. Watch the prefix from revision + 1 with progress notifications on.
4. Batch events briefly (configurable debounce, default 100 ms), apply to a working copy, validate the whole result, compile it (route trie, plugin instances, policy indexes), then swap atomically. In-flight requests finish on the snapshot they started with.
5. If the result is invalid, keep the current snapshot, raise a metric and status flag, and log the offending keys. Never apply part of a batch.
6. On a compaction error, watch cancellation, or long silence without progress notifications, do a full resync from step 2.
7. Reconnect with exponential backoff and jitter. etcd being down never stops traffic.
8. Report applied revision and status in the leased `nodes/{gatewayId}` key so the control plane and console can show which gateways are behind.
9. Readiness = a valid snapshot is loaded. Never report ready with an empty config.

Gateways watch etcd directly by default. Document the expected watcher count and etcd sizing; if fleets may exceed a few hundred pods, write an ADR for a watch relay in the control plane.

### 7. Request pipeline

Each stage can short-circuit with a response. The per-route plugin chain is built when a snapshot is compiled; a disabled feature costs nothing per request.

1. Accept connection; enforce connection limits.
2. TLS handshake; ALPN to HTTP/1.1 or HTTP/2.
3. Validate method, header count and size, body size, protocol rules; reject request smuggling (conflicting `Content-Length`/`Transfer-Encoding`, obs-fold, invalid chunking).
4. Validate `Host`/`:authority` against configured hosts.
5. Resolve route (host, method, path, headers, query; priority-ordered).
6. Run the plugin chain by phase: `rewrite` → `access` (IP rules, authn, authz, tenant/consumer policy, rate limit) → `before-proxy` (request transforms, cache lookup, breaker check).
7. Select upstream target and proxy over a pooled connection. Stream bodies both ways with backpressure; buffer only when a plugin requires it and within a size cap.
8. `header-filter` and `body-filter` phases (response transforms, cache store).
9. Respond; `log` phase records metrics, logs, and trace span.

Proxy hygiene: remove hop-by-hop headers, set `Forwarded`/`X-Forwarded-*` and `Via` from trusted proxies only, strip inbound identity headers, propagate `traceparent`, apply per-route timeouts (connect, first byte, total, idle).

### 8. Plugin model

- A plugin declares name, version, phase(s), priority, a JSON Schema for its config, and a factory that turns validated config into an immutable, pre-compiled instance at snapshot build time.
- Registration is static (a generated or hand-written registry). No reflection-based discovery.
- Scopes: global, service, route, consumer. Precedence: consumer > route > service > global, documented with examples. A route can reference a shared plugin config set.
- All built-in features in section 9 ship as plugins.
- The control plane serves the plugin catalog (names, descriptions, schemas, defaults) so the admin console renders forms from it. The control plane validates every plugin config against the same schema the gateway uses.
- New plugins are added as code in `gateway-plugins/` and ship in the next build. Document how to write one.

### 9. Feature requirements

**Routing:** exact, prefix, and parameterized paths; method, host (incl. wildcard), header, and query matching; priorities; per-route plugins, timeouts, retries. Compiled trie or radix tree. Benchmark lookup at 10, 1K, and 10K routes.

**Authentication plugins:**

- JWT: pin allowed algorithms per issuer (reject `none` and HS/RS confusion), validate `iss`, `aud`, `exp`, `nbf`, `iat` with configurable clock skew.
- OIDC: discovery and JWKS fetched and refreshed off the hot path, cached, with refresh-on-unknown-`kid` rate limited.
- API keys stored as salted hashes; constant-time compare.
- Optional HMAC request signing with replay window.
- Revocation: short-lived tokens plus a `jti` deny-list distributed through etcd (bounded size, TTL via leases) or Redis, checked locally.

**Authorization plugin:** RBAC + ABAC with roles, permissions, resources, actions, user/consumer attributes, tenant isolation, explicit deny overriding allow, route-level policies. Policies compile into indexes at snapshot load; evaluation is local.

**Rate limiting plugin:** token bucket (default), fixed window, sliding window (ADR justifies). Keys: IP, API key, consumer, tenant, route, and combinations. Burst capacity. `429` with `Retry-After` and `RateLimit` headers. Modes: local per pod (default), and global via Redis with local token pre-fetch so not every request hits Redis. Document accuracy vs. cost and the Redis-down behavior.

**Cache plugin:** Caffeine L1, optional Redis L2. Keys include tenant, route, method, normalized path, query, and `Vary` headers. Honor `Cache-Control`. Coalesce concurrent misses. Stale-while-revalidate. Invalidation by route and tag (triggered through an etcd key the worker watches). Never cache responses with `Set-Cookie`, or authenticated or private responses, unless a route policy explicitly allows it and the key isolates the principal.

**Circuit breaker:** closed/open/half-open per upstream; failure-rate and slow-call thresholds over a sliding window; open duration; half-open concurrency limit; state-change metrics and events. Resilience4j only if its native support is verified; otherwise a small in-house implementation.

**Load balancing:** round robin, weighted round robin, least connections (or power-of-two-choices), consistent hashing where justified. Active and passive health checks; outlier ejection. Per-target connection pools, connection and pending-request limits, HTTP/2 to upstreams where supported.

**Retries:** only for idempotent methods or requests marked safe; bounded attempts with jittered backoff; a per-route retry budget (e.g. ≤ 20% of requests); never retry after a body has been partially streamed unless it was buffered.

**Transform plugins:** add/remove/rename headers on request and response, path and query rewrite, CORS, body-size caps, IP allow/deny using the real client IP derived from trusted proxies.

**Security:**

- TLS 1.2+ (1.3 preferred), modern ciphers, certificates loaded from etcd references and hot-reloaded, optional mTLS to upstreams.
- SSRF: validate upstream targets at publish time and re-check resolved IPs at connect time (DNS rebinding); deny private, link-local, and metadata ranges unless allow-listed.
- Admin API on its own deployment and port, authenticated (OIDC) and authorized with roles: viewer, editor, publisher, admin.
- etcd: TLS for client and peer traffic, authentication on, a read-only role for gateways and a write role for the control plane.
- Generic public error bodies with a request ID.

### 10. Admin API and admin console

**Admin API** (control plane, `/admin/v1`):

- CRUD for routes, services, upstreams, consumers, credentials (write-only secrets), plugin configs, global plugins, policies, certificates.
- `GET /plugins` and `GET /plugins/{name}/schema`: the catalog the console renders from.
- Change sets: create, edit, `POST /changesets/{id}/validate` (dry run), `GET /changesets/{id}/diff`, `POST /changesets/{id}/publish`, `POST /history/{seq}/rollback`.
- `GET /gateways`: each gateway's applied revision, staleness, and status from the node keys.
- `GET /audit`, `health`, `ready`.
- OpenAPI 3 spec in `docs/api/`; RFC 9457 problem details; `ETag`/`If-Match`; pagination; validation errors that name the field and the schema rule.

**Audit:** every Admin API mutation emits an append-only structured audit event (who, what, before/after hash, change set, time, source IP) to the log pipeline and a configurable external sink. A bounded recent window is kept in etcd for the console. ADR on retention, since etcd is not an audit archive.

**Admin console** (`admin-console/`):

- Separate app with its own build, tests, container (minimal static server, non-root), and Kubernetes Deployment. It is never in the gateway's request path.
- Talks only to the Admin API over HTTPS. OIDC authorization code + PKCE; tokens kept in memory, never `localStorage`.
- Screens: routes, services/upstreams, consumers and credentials, global and per-route plugins, policies, certificates, change sets (diff, validate, publish), history and rollback, gateway status, audit.
- Plugin config forms render from the plugin's JSON Schema. Client-side validation mirrors the schema; the server is authoritative and its field errors are shown inline.
- UI is role-aware (hide or disable what a role can't do); the API still enforces every permission.
- Publishing always shows a diff and requires confirmation. Secrets are write-only fields.
- Strict Content Security Policy, no inline scripts, all values rendered escaped.
- WCAG 2.2 AA. Vitest for units, Playwright end-to-end against the real control plane and etcd in Docker Compose.

### 11. Native Image, concurrency, performance

**Native Image:**

- Prefer build-time initialization where safe; avoid reflection; keep reachability metadata in the repo; use the tracing agent only to discover, then review its output.
- Run tests against the native binary (`./gradlew nativeTest`) and integration tests against the native container.
- State which GC you use and why: Serial is the CE default; G1 is Oracle-GraalVM-only. Measure tail latency with each available option. Try PGO if available.
- Measure and report: binary size, cold start to ready, idle RSS, peak RSS under load, CPU, GC pauses, throughput, latency percentiles. Compare against `jvm-prod` under the identical workload. Don't assume native wins.

**Concurrency:** event-loop model, one event loop per core by default. The config worker, JWKS refresh, health checks, and etcd/Redis clients run off the event loop or are fully async. Document thread ownership for every component. Virtual threads only for control-plane or blocking integration work, after confirming they behave in your native build. Bounded queues and backpressure everywhere.

**Performance discipline:** optimize only what a profile or benchmark shows. Avoid per-request allocations in routing and plugin checks, repeated parsing, and shared-lock contention. No `Unsafe`, custom allocators, or exotic lock-free structures without an ADR and a benchmark.

**Benchmark method:**

- Load generator on a separate host; record host specs, kernel, NIC, and tuning.
- Open model (constant arrival rate); tools must correct for coordinated omission.
- Warm-up, then a ≥ 5-minute steady phase; ≥ 3 runs; report median and spread.
- Always run a direct-to-upstream baseline and report gateway-added latency.
- Max sustainable RPS = highest rate with error rate < 0.1% and p99 within target.
- Scenarios: plain proxy; route matching at scale; JWT; JWT + authz; rate limiting (local, global); cache hit and miss; breaker open; TLS; HTTP/2; full pipeline; upstream failure; high connection count; 10K-route config; config churn under load (publish every second while at 70% load); multi-replica on Kubernetes.
- Also measure config propagation latency (publish to all gateways applied) at 3, 20, and 100 replicas where possible.
- Tiers 1K, 10K, 50K, 100K RPS as hardware allows; mark untested tiers `BLOCKED`.
- Store raw results and scripts under `performance/`.

### 12. Milestones and exit criteria

A milestone is done only when its checks pass. Record results in `PROGRESS.md`.

| # | Scope | Exit criteria |
| --- | --- | --- |
| M0 | Environment report, architecture, ADRs, risk register, roadmap | `docs/environment.md`, `docs/architecture/overview.md`, ADRs committed |
| M1 | Gradle multi-project with convention plugins and quality gates; Vert.x server with HTTP/1.1, h2, h2c; first native binary; etcd client native spike | `./gradlew build` green; native binary serves h1 and h2; native binary connects to etcd over TLS, reads, and watches a key; etcd-client ADR written |
| M2 | Snapshot model, route trie, plugin SPI and registry, upstreams, LB, pooled streaming proxy, timeouts, errors | End-to-end proxy tests pass on JVM and native, loading config from a file snapshot |
| M3 | Config worker against etcd: full load, watch, batching, validation, atomic swap, LKG on disk, compaction resync, node status keys | Testcontainers etcd suite passes, including etcd down at boot, etcd restart, compaction, invalid change rejected |
| M4 | Security plugins: JWT, OIDC/JWKS, API keys, RBAC/ABAC, tenant isolation | Security suite passes, incl. cross-tenant and alg-confusion cases |
| M5 | Traffic plugins: rate limit, cache, breaker, retries, transforms, CORS, IP rules | Unit + integration tests pass, incl. Redis-down and miss-coalescing cases |
| M6 | Control plane: key schema, Admin API, plugin catalog, change sets, publish txn, rollback, audit, OpenAPI | Change published through the API reaches 3 gateway replicas within target; concurrent edit conflict returns 412; rollback restores prior state |
| M7 | Admin console | Lint, type-check, Vitest, and Playwright e2e pass against the Compose stack; CSP verified |
| M8 | Observability: metrics, JSON logs, tracing, dashboards, alerts | Prometheus scrape works; Grafana dashboard JSON committed; trace propagation tested |
| M9 | Docker, Kubernetes (base + dev/staging/prod overlays) for all deployables and etcd, graceful shutdown, backups, hardening | Images run non-root with read-only rootfs; `kustomize build` + `kubeconform` pass for every overlay; rollout drain test drops zero requests; etcd backup and restore rehearsed |
| M10 | Benchmarks, profiling, optimization, JVM vs native report | `docs/performance/results.md` with measured numbers, environment, raw data, and limits |

### 13. Kubernetes and containers

- Multi-stage Dockerfiles: native build stage, minimal runtime (distroless or `scratch` with required libs), non-root UID, read-only root filesystem, no capabilities. One for the gateway, one for the control plane, one for the console.
- Kustomize base + dev/staging/prod overlays: Namespace, ServiceAccounts, ConfigMaps, Secret references, Deployments, Services, HPA, PDBs, NetworkPolicies (gateway → etcd read port only; console → control plane only; only the control plane may write to etcd), requests/limits, startup/readiness/liveness probes, `preStop` drain and `terminationGracePeriodSeconds`, topology spread, anti-affinity.
- etcd: 3-node StatefulSet (or an operator or managed etcd, chosen by ADR) with persistent SSD volumes, PDB, anti-affinity, TLS, auth, defragmentation schedule, and a CronJob for `etcdctl snapshot save` to object storage. Restore runbook in `docs/operations/`.
- Graceful shutdown: on SIGTERM, fail readiness, stop accepting, drain in-flight requests up to a deadline, then exit.
- HPA on CPU plus a custom metric (RPS or in-flight requests per pod). Document why CPU alone lags for latency-sensitive gateways.
- The LKG snapshot is written to an `emptyDir` (or optional PVC) so a restarted container can serve during an etcd outage.

### 14. Observability

**Metrics:** request count and rate, latency histograms, status codes, per-route traffic (route ID only, bounded cardinality), authn/authz failures, rate-limit rejections, cache hit ratio, breaker state, upstream latency and errors, active connections, pool usage, queue depth, CPU and memory. Config: applied revision, propagation lag, staleness, watch reconnects, resyncs, rejected snapshots, etcd request latency.

**Logs:** structured JSON with trace ID, request ID, route ID, upstream ID, method, status, duration. Async and bounded so logging can't block the event loop.

**Tracing:** OpenTelemetry, W3C Trace Context, configurable sampling.

Ship Prometheus scrape config, Grafana dashboards, and alert rules (error rate, p99, saturation, breaker open, config staleness, gateways behind on revision, etcd health and DB size).

### 15. Tests

- **Unit:** every plugin and module, config validation, snapshot compilation, plugin precedence.
- **Integration (Testcontainers):** etcd (official image), Redis, end-to-end proxy, config worker behavior, publish and rollback, HTTP/1.1, HTTP/2, TLS, upstream failure and recovery, native binary startup.
- **Security:** invalid/expired/wrong-issuer/wrong-audience JWT, alg confusion, missing auth, cross-tenant access, request smuggling, SSRF incl. DNS rebinding, header injection, oversized requests and headers, Admin API privilege escalation, gateway etcd role cannot write, secret leakage in logs, errors, and API responses.
- **Resilience:** etcd down at boot and at runtime, etcd leader change, compaction during watch, Redis down (both modes), slow upstream, upstream reset mid-stream, pod drain.
- **Frontend:** Vitest components and schema-form rendering; Playwright flows for route create, plugin configure, publish, conflict, rollback, and role restrictions.
- **Performance:** scripts and results per section 11.

### 16. Documentation

Architecture overview, ADRs, Mermaid diagrams (components, request flow, config publish-to-apply sequence), etcd key schema reference, plugin authoring guide, security architecture and threat model, Admin API reference, admin console user guide, local dev guide, native build guide, deployment and Kubernetes operations guide, etcd backup/restore runbook, monitoring and alerting guide, benchmark methodology and results, failure-handling runbook. README lists what works, what's blocked, and how to run everything.

### 17. Definition of done

- `./gradlew build` passes, including Checkstyle, SpotBugs, and the dependency scan.
- `./gradlew nativeCompile nativeTest` passes; integration, security, and resilience suites pass on JVM and against the native binary.
- Admin console: `pnpm lint`, `pnpm typecheck`, `pnpm test`, `pnpm e2e` pass.
- Docker images for gateway, control plane, and console build and run; HTTP/1.1 and HTTP/2 work.
- Every feature in sections 8–10 is implemented with no stubs in critical paths.
- A change made in the console reaches every gateway through etcd within target, survives etcd and control-plane outages, and can be rolled back.
- Kubernetes manifests validate for all overlays; etcd backup and restore are rehearsed.
- Benchmarks are reproducible from the repo; results report measured numbers against the section 1 targets, including any gap.
- Docs are complete; `CLAUDE.md` matches the repo; `PROGRESS.md` has no open items except those marked `BLOCKED` with a reason.

### 18. First action

Run the environment inspection, write `docs/environment.md`, deliver M0 (architecture, modules, ADRs, risks, roadmap), and begin M1 immediately. Report at the end of each milestone: milestone, what was done, checks run and results, blockers, next step.
