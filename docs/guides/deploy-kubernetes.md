# Deploying on Kubernetes

This guide walks you through deploying the gateway, control plane, console and a dedicated etcd cluster with the
Kustomize manifests in `deployment/kubernetes/`. The detailed reference is
[operations → Kubernetes](../operations/kubernetes.md): workloads, secrets, probes, resources, network policies
and placement. The etcd design is in [ADR 0024](../adr/0024-etcd-on-kubernetes.md).

## 1. Prerequisites

- Kubernetes 1.30 or later. The manifests are validated against 1.30 and 1.34.
- At least 3 schedulable nodes. etcd needs one member per node (required anti-affinity), and prod spreads
  gateways across zones.
- A StorageClass for the etcd PVCs. The prod overlay asks for SSD-backed storage.
- `kubectl` with Kustomize. A standalone `kustomize` binary is optional.
- Optional components:
  - Prometheus Operator, for the `components/monitoring` PodMonitors and PrometheusRule;
  - prometheus-adapter, for the RPS-based HPA metric.
- S3-compatible object storage for the etcd backup CronJob.
- An OIDC identity provider for Admin API and console sign-in.
- A container registry that your cluster can pull from.

## 2. Layout

```
deployment/kubernetes/
  base/                     gateway, control-plane, console, etcd (+ backup/defrag CronJobs), NetworkPolicies
  components/monitoring/    PodMonitors, generated PrometheusRule, prometheus-adapter rule
  overlays/dev|staging|prod namespace apigw-<env>, images, env ConfigMaps, replicas, patches
  validate.sh               kustomize build + kubeconform for every overlay
  scripts/                  kind cluster, dev secrets, token minting, seed, drain test, backup/restore rehearsal
```

| Overlay | Namespace | Gateways | Notes |
|---|---|---|---|
| `dev` | `apigw-dev` | 2 | Adds an `echo` upstream and an in-namespace S3 store. No RPS metric |
| `staging` | `apigw-staging` | HPA 3–10 | Registry images, monitoring |
| `prod` | `apigw-prod` | HPA 3–30 | Gateway Service `type: LoadBalancer`, hard zone spread, OIDC discovery |

## 3. Deploy step by step

### 3.1 Build and push images

Build each image on a native builder for every architecture you run. The build commands are in the
[Docker guide](deploy-docker.md#2-images). Push the images to your registry, then pin them by digest in the overlay:

```bash
cd deployment/kubernetes/overlays/prod
kustomize edit set image apigw/gateway=REGISTRY/apigw/gateway@sha256:...
kustomize edit set image apigw/control-plane=REGISTRY/apigw/control-plane@sha256:...
kustomize edit set image apigw/admin-console=REGISTRY/apigw/admin-console@sha256:...
kustomize edit set image apigw/etcd-tools=REGISTRY/apigw/etcd-tools@sha256:...
```

### 3.2 Replace the placeholders

Every `example.com` value in the overlay is a placeholder.

- `overlays/<env>/kustomization.yaml`, in the `control-plane-env` ConfigMap:
  - `CP_OIDC_ISSUER`;
  - `CP_OIDC_DISCOVERY`, or `CP_OIDC_JWKS_URI`;
  - `CP_CORS_ORIGINS`, set to the console's public origin.
- The other ConfigMaps in the same file:
  - `gateway-env`: `GW_TRACING_ENDPOINT`;
  - `etcd-ops-env`: `BACKUP_REMOTE`.
- `overlays/<env>/config.json`: `apiBase` (the public Admin API URL) and the `oidc` authority and client ID.
- `overlays/<env>/sws.toml`: the CSP `connect-src`, set to exactly the Admin API origin and the IdP origin.

The full variable lists are in [configuration](../operations/configuration.md#bootstrap-environment) and
[control plane](../operations/control-plane.md#environment).

### 3.3 Create the Secrets

Secrets are never committed. Create them with your secret manager (External Secrets, Sealed Secrets, Vault) in
the overlay's namespace. For the names, keys and certificate SANs, see
[operations → Kubernetes → Secrets](../operations/kubernetes.md#secrets).

| Secret | Purpose |
|---|---|
| `etcd-tls`, `etcd-ca` | etcd server/peer TLS and the CA that clients trust |
| `etcd-root`, `etcd-gateway`, `etcd-control-plane` | etcd user passwords. `etcd-root` is used only by the etcd-ops jobs |
| `control-plane-tls` | Admin API HTTPS certificate |
| `etcd-backup-s3` | rclone S3 remote for backups |

For a **dev** cluster only, `deployment/kubernetes/scripts/dev-secrets.sh apigw-dev` generates throwaway values.

### 3.4 Validate and apply

```bash
deployment/kubernetes/validate.sh                       # every overlay, strict schemas, PrometheusRule drift
kubectl kustomize deployment/kubernetes/overlays/prod   # review the rendered manifests
kubectl apply -k deployment/kubernetes/overlays/prod
```

### 3.5 Wait for bootstrap and rollouts

```bash
NS=apigw-prod
kubectl -n $NS rollout status statefulset/etcd
kubectl -n $NS wait --for=condition=Complete job/etcd-bootstrap-auth --timeout=300s   # users, roles, auth enable
kubectl -n $NS rollout status deployment/control-plane
kubectl -n $NS rollout status deployment/gateway
kubectl -n $NS rollout status deployment/console
kubectl -n $NS get pods
```

- `etcd-bootstrap-auth` is idempotent, so it is safe to re-run.
- The control plane becomes ready once the keyspace is migrated.
- Gateways become **ready only after a config is published**. A fresh install has no routes, so gateway pods stay
  NotReady until you publish. That is intended: a gateway never reports ready with an empty config. Do the next
  step right away.

### 3.6 Publish the first configuration

Use the Admin API through a port-forward or your Ingress.

```bash
kubectl -n $NS port-forward svc/control-plane 9443:9443
```

Then follow the [user guide](user-guide.md):

1. Settings → `upstreamAllowCidrs`. Set it to the cluster's pod and service CIDRs that your upstreams use.
   Kubernetes services resolve to private addresses, which the SSRF guard otherwise rejects
   ([ADR 0015](../adr/0015-authentication-and-ssrf.md)).
2. Create an upstream, a service and a route.
3. Publish.

`deployment/kubernetes/scripts/seed.sh` is a working example of these steps for the dev overlay: it creates
settings, the `echo` upstream, a service and the `/echo` route, and publishes them in one change set.

## 4. Exposing the components

| Component | Service | How to expose |
|---|---|---|
| Gateway | `gateway` (port 80 → 8080) | prod: `type: LoadBalancer` with `externalTrafficPolicy: Local`, which preserves client IPs. Alternatively, put your own Ingress or Gateway API route in front. List its addresses in `settings.trustedProxies` |
| Admin API | `control-plane` (9443, HTTPS) | Use your own Ingress with TLS passthrough or re-encrypt. In staging and prod the NetworkPolicy admits only the ingress-controller namespace |
| Console | `console` | Use your own Ingress. The console's NetworkPolicy allows egress only to the control plane |

The repository ships no Ingress objects, because ingress controllers differ too much between clusters. Add yours
in the overlay. The console's IdP client needs:

- redirect URI `{console}/callback`
- web origin `{console}`
- PKCE S256

Access tokens need `aud` = `CP_OIDC_AUDIENCE` and a roles claim. Details:
[admin console → Configuration (production)](../operations/admin-console.md#configuration-production).

## 5. Day-2 operations

- **Upgrades:**
  - Bump the image digests and `kubectl apply -k`.
  - Rollouts use `maxUnavailable: 0`.
  - Each old gateway pod waits 10 s in `preStop`, fails `/readyz`, then drains for up to 25 s.
  - `scripts/drain-test.sh` proves zero failed requests during rollouts.
  - Details: [Graceful shutdown and rollouts](../operations/kubernetes.md#graceful-shutdown-and-rollouts).
- **Scaling:**
  - The HPA scales on CPU (60%) and on requests per second per pod (20k, needs prometheus-adapter).
  - Gateways have no CPU limit on purpose.
  - Details: [Resources and autoscaling](../operations/kubernetes.md#resources-and-autoscaling).
- **Monitoring:**
  - `components/monitoring` adds the PodMonitors and alert rules.
  - Dashboards, alerts and runbooks: [observability](../operations/observability.md).
- **Backups:**
  - The hourly `etcd-backup` CronJob writes verified snapshots to S3.
  - `etcd-defrag` runs weekly.
  - Restore and member replacement: [etcd backup and restore](../operations/etcd-backup-restore.md).
- **Password rotation:**
  1. Run `etcdctl user passwd`.
  2. Update the Secret.
  3. Run `kubectl rollout restart` on the consumer.
- **Config rollback:** use **History → Roll back** in the console. You do not need to touch Kubernetes. See the
  [user guide](user-guide.md#6-roll-back).

## 6. Try it locally on kind

kind is for functional checks only; it is not a benchmark target.

```bash
kind create cluster --config deployment/kubernetes/scripts/kind-cluster.yaml        # 1 control plane + 3 workers
for i in gateway control-plane admin-console etcd-tools; do kind load docker-image --name apigw apigw/$i:0.9.0; done
deployment/kubernetes/scripts/dev-secrets.sh apigw-dev
kubectl apply -k deployment/kubernetes/overlays/dev
kubectl -n apigw-dev wait --for=condition=Complete job/etcd-bootstrap-auth --timeout=300s
deployment/kubernetes/scripts/seed.sh                 # route /echo through the Admin API (dev token)
kubectl -n apigw-dev port-forward svc/gateway 18080:80 &
curl -s http://localhost:18080/echo
```

The dev overlay signs Admin API tokens with a local key pair, and `scripts/mint-token.sh <role> apigw-dev`
mints one. That makes the API examples in the user guide runnable without an IdP. Optional rehearsals:

- `scripts/drain-test.sh`
- `scripts/backup-restore.sh`

## 7. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| etcd or gateway pods `Pending` | Fewer than 3 nodes or zones, combined with required anti-affinity or hard zone spread | Add nodes, or use the dev overlay on small clusters |
| `etcd-bootstrap-auth` fails | `etcd-root` or `etcd-tls` is missing or wrong, or the SANs don't cover `etcd-N.etcd` | Run `kubectl -n $NS logs job/etcd-bootstrap-auth`, fix the Secret, delete the job, and re-apply |
| Gateway pods NotReady | No config published yet, or etcd is unreachable with no LKG | Publish a change set. Check `/configz` on the status port (9901) |
| Gateway is ready but upstream calls fail with 502 | The upstream IP is not in `upstreamAllowCidrs`, or a NetworkPolicy blocks egress | Add the CIDR in Settings. Check the gateway egress policy |
| Console cannot reach the Admin API | CSP `connect-src`, `CP_CORS_ORIGINS` or `apiBase` don't match exactly | Make all three use the same origins |
| Admin API returns 401 for valid users | Token `iss` or `aud` mismatch, or a JWKS/discovery fetch is blocked | The control plane needs 443 egress to the IdP. Check `CP_OIDC_*` |
