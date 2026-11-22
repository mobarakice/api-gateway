# Kubernetes deployment

Manifests live in `deployment/kubernetes/`: a Kustomize `base`, the `dev`, `staging` and `prod` overlays, and an optional
`components/monitoring` (Prometheus Operator). They target Kubernetes 1.30 or later and are validated against
1.30 and 1.34 with kubeconform in strict mode.
etcd design: [ADR 0024](../adr/0024-etcd-on-kubernetes.md). Backup and restore: [runbook](etcd-backup-restore.md).

```bash
deployment/kubernetes/validate.sh                         # kustomize build + kubeconform -strict for every overlay
kubectl kustomize deployment/kubernetes/overlays/prod     # render
kubectl apply -k deployment/kubernetes/overlays/prod      # after creating the Secrets below
```

## What gets deployed
| Workload | Kind | Replicas (dev/staging/prod) | Notes |
|---|---|---|---|
| `gateway` | Deployment + HPA + PDB | 2 / 3–10 / 3–30 | native image. LKG in an `emptyDir`. HPA on CPU and RPS per pod |
| `control-plane` | Deployment + PDB | 1 / 2 / 2 | native image, HTTPS Admin API, metrics on 9902 |
| `console` | Deployment + PDB | 1 / 2 / 2 | static-web-server. CSP and `config.json` per overlay |
| `etcd` | StatefulSet (3) + PDB | 3 / 3 / 3 | dedicated cluster, TLS + auth, PVC per member |
| `etcd-bootstrap-auth` | Job | – | users and roles, then auth enable (idempotent) |
| `etcd-backup` | CronJob (hourly :17) | – | snapshot to S3-compatible storage, verified and pruned |
| `etcd-defrag` | CronJob (Sun 03:43) | – | one member at a time, leader last |

The dev overlay adds an `echo` upstream and a `rclone serve s3` object store, used for the rehearsals.

## Images
| Image | Dockerfile | Base | Size (arm64) |
|---|---|---|---|
| `apigw/gateway` | `deployment/docker/gateway.native.Dockerfile` | distroless `base-debian13:nonroot` (glibc only) | 88.7 MB |
| `apigw/control-plane` | `deployment/docker/control-plane.native.Dockerfile` | same | 85.7 MB |
| `apigw/admin-console` | `admin-console/Dockerfile` | static-web-server (scratch) | – |
| `apigw/etcd-tools` | `deployment/docker/etcd-tools/Dockerfile` | official rclone (Alpine) + etcdctl/etcdutl from the official etcd image | – |

- **Native builds** run Oracle GraalVM 25.0.4 (`container-registry.oracle.com/graalvm/native-image`) with Gradle from the official
  image. `-H:+StaticExecutableWithDynamicLibC` links everything but glibc statically, so the binary runs on distroless `base`.
- **GC:** Serial by default; pass `--build-arg NATIVE_GC=G1` for G1. The per-profile choice is made in M10 (ADR 0008).
- **Architectures:** build for each architecture on a native builder. Cross-arch builds run the builder under emulation and are slow.
- **Runtime hardening:** every container runs as UID 10001 with a read-only root filesystem, `capabilities: drop [ALL]`,
  `allowPrivilegeEscalation: false` and the `RuntimeDefault` seccomp profile. The namespace enforces Pod Security `restricted`.
  Writable paths are `emptyDir` mounts only (LKG, `/tmp`).
- **Registries:** staging and prod point at `registry.example.com/apigw/*`. CI sets the real registry and pins digests:

```bash
cd deployment/kubernetes/overlays/prod
kustomize edit set image apigw/gateway=REGISTRY/apigw/gateway@sha256:...
```

## Secrets
Secrets are never committed. Create them with your secret manager (External Secrets, Sealed Secrets, Vault) under these names.
The dev overlay's `scripts/dev-secrets.sh` generates throwaway values for a dev cluster only.

| Secret | Keys | Used by |
|---|---|---|
| `etcd-tls` | `tls.crt`, `tls.key`, `ca.crt` | etcd (client + peer). SANs: `etcd-N.etcd`, `*.etcd.<ns>.svc[.cluster.local]` |
| `etcd-ca` | `ca.crt` | gateway, control plane, etcd-ops |
| `etcd-root` | `password` | etcd-ops jobs only |
| `etcd-gateway` | `password` | gateway (`gateway-read` role) |
| `etcd-control-plane` | `password` | control plane |
| `control-plane-tls` | `tls.crt`, `tls.key` | Admin API HTTPS |
| `etcd-backup-s3` | `RCLONE_CONFIG_BACKUP_TYPE=s3`, `_PROVIDER`, `_ENDPOINT`/`_REGION`, `_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY` (or `_ENV_AUTH=true` with workload identity) | etcd-backup |
| `control-plane-oidc` (dev only) | `jwks.json` | dev Admin API signing keys |

Rotating a password:
1. Change it in etcd with `etcdctl user passwd`.
2. Update the Secret.
3. Restart the consumer with `kubectl rollout restart`.

## Graceful shutdown and rollouts
A gateway pod being replaced:
1. `preStop` sleeps 10 s while the pod keeps serving. Meanwhile the endpoint controller, kube-proxy and the load balancers
   remove it from rotation.
2. SIGTERM makes `/readyz` fail at once, stops accepting connections, and drains in-flight requests for up to
   `GW_SHUTDOWN_GRACE_SECONDS` (25 s). HTTP/1.1 keep-alive connections are closed after their current response, and HTTP/2 gets GOAWAY.
3. `terminationGracePeriodSeconds: 45` covers both phases with margin.

Rolling updates use `maxUnavailable: 0`, so capacity never drops below the replica count.
The **rollout drain test** (`scripts/drain-test.sh`) runs open-model load through the gateway Service during three
rollouts and fails on any error. Results are in `PROGRESS.md` (M9).

## Probes
| Workload | startup | readiness | liveness |
|---|---|---|---|
| gateway | `/healthz` (≤ 60 s) | `/readyz`: a valid snapshot is serving, current or last-known-good. It stays ready during an etcd outage | `/healthz` |
| control plane | `/admin/v1/health` | `/admin/v1/ready`: keyspace migrated and readable | `/admin/v1/health` |
| etcd | `/livez` on the metrics port | `/readyz` | `/livez` |

## Resources and autoscaling
- **Gateway:** requests 1 CPU / 384 Mi, memory limit 512 Mi, native heap `-Xmx320m`. There is **no CPU limit**: CFS quota
  throttling stalls event loops mid-request and shows up directly as p99/p99.9 latency.
- **HPA:**
  - It scales on CPU (60%) and on `gw_requests_per_second` per pod (20k), served by prometheus-adapter with the rule in
    `components/monitoring/prometheus-adapter-rules.yaml`.
  - CPU alone lags for a latency-sensitive gateway. Utilisation is averaged over the metrics window and rises only once
    event loops are already queueing, so tail latency degrades before scale-up starts. RPS tracks offered load directly.
  - Scale-up is immediate (up to 100% per 30 s). Scale-down waits 5 minutes and removes one pod per minute.
  - The dev overlay drops the RPS metric because it has no adapter.

## Network policies
The namespace denies everything by default and DNS is allowed for all pods. Component policies:

| From → To | Allowed |
|---|---|
| anywhere → gateway | 8080 |
| monitoring namespace → gateway / control plane / etcd | 9901 / 9902 / 2381 |
| gateway → etcd | **2379 only**. The peer port 2380 is accepted only from etcd members |
| gateway → upstreams, Redis, OTLP | any address except `169.254.169.254` (cloud metadata) |
| control plane → etcd | 2379, plus 443 egress for OIDC discovery/JWKS |
| console → | **control plane 9443 only** |
| anywhere (staging/prod: the ingress controller namespace) → control plane | 9443 |
| etcd-ops jobs → etcd, object storage | 2379, 443 (dev: the in-namespace store on 9000) |

- Only the control plane can write configuration, and that is enforced by etcd RBAC. The gateway's `gateway-read` role can write only
  `nodes/`. The network can't tell reads from writes; RBAC can.
- Upstream targets are further restricted by `settings.upstreamAllowCidrs` and the SSRF resolver (ADR 0015).

## Placement
- **Gateway:** topology spread over zones and nodes, plus preferred node anti-affinity. Prod makes zone spread hard (`DoNotSchedule`).
- **etcd:** required node anti-affinity (one member per node) and zone spread.
- **PDBs:**
  - gateway: `maxUnavailable: 1`; prod uses `minAvailable: 66%`;
  - control plane and console: `maxUnavailable: 1`;
  - etcd: `minAvailable: 2`.

## Monitoring
`components/monitoring` adds PodMonitors with job labels `apigw-gateway`, `apigw-control-plane` and `etcd`, and a PrometheusRule
generated from `deployment/observability/prometheus/rules/apigw-alerts.yml`. `validate.sh` fails if the generated rule has drifted.
Dashboards and runbooks: [observability](observability.md).

## Local rehearsal on kind
kind provides functional checks only. It is not a benchmark target, and its numbers are not reference numbers.

```bash
kind create cluster --config deployment/kubernetes/scripts/kind-cluster.yaml        # 1 control plane + 3 workers
for i in gateway control-plane admin-console etcd-tools; do kind load docker-image --name apigw apigw/$i:0.9.0; done
deployment/kubernetes/scripts/dev-secrets.sh apigw-dev
kubectl apply -k deployment/kubernetes/overlays/dev
kubectl -n apigw-dev wait --for=condition=Complete job/etcd-bootstrap-auth --timeout=300s
deployment/kubernetes/scripts/seed.sh                 # route /echo through the Admin API (dev token)
deployment/kubernetes/scripts/drain-test.sh           # rollout drain test
deployment/kubernetes/scripts/backup-restore.sh       # etcd backup + total-loss restore rehearsal
```
