# Environment report

Inspected 2026-11-18 on the development host. Re-run the commands at the bottom when the host changes.

## Host

| Item | Value |
|---|---|
| Machine | macOS 26.6.2 (Darwin 25.6.0), Apple Silicon arm64, 8 CPU, 16 GB RAM |
| Role | **Development host only.** It is not the reference benchmark host. |
| Docker | Engine 29.8.1, Linux VM arm64, 8 CPU, ~11.7 GB allotted |
| Kubernetes | `kubectl` v1.32.3 client (built-in Kustomize v5.5.0); **no cluster context configured** |
| Network | Maven Central and npm registries reachable |

## Toolchain

| Tool | Found | Use |
|---|---|---|
| JDK (default on PATH) | OpenJDK 25.0.2+10 | Not used by the build; Gradle toolchains select GraalVM |
| GraalVM | Oracle GraalVM 25+37.1 (`native-image 25 2025-09-16`, Serial GC default, G1 available on Linux only) at `~/Library/Java/JavaVirtualMachines/graalvm-jdk-25` — not on PATH | Build toolchain for JVM and native. Registered via `org.gradle.java.installations.paths` / `GRAALVM_HOME` |
| Gradle | 9.7.0 (system) | Only to generate the wrapper; always `./gradlew` afterwards |
| Node.js / pnpm | v22.13.0 / 10.2.1 | Admin console |
| git | 2.54.0 | Repository initialised locally on `main`, no remote |
| etcdctl | not installed | Run from the official etcd image (`docker run --rm gcr.io/etcd-development/etcd:<ver> etcdctl ...`) |
| kustomize | not installed standalone | `kubectl kustomize` (v5.5.0) |
| kubeconform | not installed | `ghcr.io/yannh/kubeconform` container |
| k6 / wrk2 | not installed | `grafana/k6` container; wrk2 built in a container if needed |

## Spec "Environment and targets" table — resolved values

| Item | Value used | Source |
|---|---|---|
| Reference gateway host | 2 vCPU, 4 GB RAM (Linux x86_64 assumed) | Requester |
| Load generator host | Separate host, same network, >= gateway vCPU | Default |
| Upstream | Minimal native HTTP echo server on a third host, ~0 ms service time | Default |
| etcd | Dedicated 3-node cluster, latest stable 3.x, TLS + auth, SSD | Default |
| Target throughput | 100K RPS on the reference host, full pipeline minus TLS | Default |
| Gateway-added latency | p50 <= 0.5 ms, p99 <= 2 ms, p99.9 <= 5 ms at 70 % of max sustainable RPS | Default |
| Config propagation | <= 2 s p99, committed change live on all gateways | Default |
| Native cold start | <= 100 ms to serving with an LKG snapshot | Default |
| Idle RSS (native) | <= 64 MB | Default |
| GraalVM distribution | Oracle GraalVM (G1 + PGO available) | Default; installed |
| Admin console stack | React + TypeScript + Vite, pnpm | Default |
| Kubernetes target | Any CNCF-conformant cluster >= 1.30 | Default |

## Consequences

- Numbers measured on this Mac are labelled **dev host** and are never reported as reference results.
- The native binary built here is a macOS/arm64 binary. Shipping images are Linux binaries built inside
  a Docker build stage (`deployment/docker/`), so container tests use those, not the macOS binary.
- G1 for Native Image is Linux-only; GC comparison (ADR 0008) runs in Linux containers / hosts.
- The installed GraalVM is 25 GA (25+37). Moving to the latest 25.0.x update is tracked in the risk register.
- Blockers recorded in `PROGRESS.md`: reference hosts for M10, a Kubernetes cluster for multi-replica tests.

## Commands used

```bash
java -version
~/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home/bin/native-image --version
gradle --version
node --version; pnpm --version
docker info; docker version
kubectl version --client; kubectl config get-contexts
etcdctl version; kustomize version; kubeconform -v; k6 version; wrk -v   # all: not found
sw_vers; uname -mr; sysctl -n hw.ncpu hw.memsize
curl -sI https://repo1.maven.org/maven2/
```
