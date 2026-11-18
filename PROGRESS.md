# PROGRESS

Resume rule: read `CLAUDE.md` and this file, then continue from the last green milestone.

| # | Scope | Status |
|---|---|---|
| M0 | Environment report, architecture, ADRs, risk register, roadmap | done (2026-11-18) |
| M1 | Gradle multi-project, quality gates, Vert.x h1/h2/h2c, first native binary, etcd native spike | not started |
| M2 | Snapshot model, route trie, plugin SPI/registry, upstreams, LB, streaming proxy | not started |
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

## Known blockers

- BLOCKED: reference benchmark host. The spec's reference host (2 vCPU / 4 GB, Linux) and a separate
  load-generator host are not available; the only host is a macOS arm64 laptop (8 CPU, 16 GB).
  Requester must provide two Linux hosts (gateway + load generator) and a third for the upstream.
- BLOCKED: Kubernetes cluster for multi-replica benchmarks and the rollout drain test. Requester must
  provide a CNCF-conformant cluster >= 1.30 (kind in Docker can cover functional checks only).
- No external OIDC provider: OIDC tests use a containerized IdP or a local JWKS fixture; each test
  states which.
