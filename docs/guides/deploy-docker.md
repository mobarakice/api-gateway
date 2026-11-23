# Deploying with Docker

This guide takes you from a clean checkout to a running gateway stack in Docker. It also explains how to run
the same images outside Compose. Reference material lives in the operations docs and is linked rather than copied:

- [gateway configuration](../operations/configuration.md)
- [control plane](../operations/control-plane.md)
- [admin console](../operations/admin-console.md)
- [observability](../operations/observability.md)

When the stack is running, continue with the [user guide](user-guide.md).

## 1. Prerequisites

- Docker Engine with Compose v2 (`docker compose version`). Give Docker at least 8 GB of RAM, because each
  native image build runs a GraalVM compiler.
- About 10 GB of free disk space for the build stages and images.
- Build on the architecture you will run on (arm64 or amd64). A cross-architecture build runs the GraalVM
  builder under emulation, which is very slow.
- For JVM images only: the GraalVM 25 JDK on the host (see [`docs/environment.md`](../environment.md)).

## 2. Images

| Image | Dockerfile | Notes |
|---|---|---|
| `apigw/gateway` | `deployment/docker/gateway.native.Dockerfile` | Native binary on distroless, uid 10001 |
| `apigw/control-plane` | `deployment/docker/control-plane.native.Dockerfile` | Same base |
| `apigw/admin-console` | `admin-console/Dockerfile` | static-web-server, static files only |
| `apigw/etcd-tools` | `deployment/docker/etcd-tools/Dockerfile` | etcdctl/etcdutl + rclone, for backup/restore |

Compose builds these images for you. To build them yourself, run from the repository root:

```bash
docker build -f deployment/docker/gateway.native.Dockerfile -t apigw/gateway:0.9.0 .
docker build -f deployment/docker/control-plane.native.Dockerfile -t apigw/control-plane:0.9.0 .
docker build -t apigw/admin-console:0.9.0 admin-console
docker build -f deployment/docker/etcd-tools/Dockerfile -t apigw/etcd-tools:0.9.0 deployment/docker/etcd-tools
```

- Garbage collector: Serial by default. Add `--build-arg NATIVE_GC=G1` to the gateway or control-plane build
  to use G1 instead ([ADR 0008](../adr/0008-native-gc.md)).
- JVM images (`*.jvm.Dockerfile`) package a distribution that you build on the host first. They are meant for
  comparisons and debugging. Native images are the default.

## 3. Start the local stack

`deployment/docker/compose.yml` starts a complete development environment:

- etcd with TLS and auth
- Redis with an ACL user
- an `echo` upstream
- two gateways
- the control plane
- Keycloak
- the console
- Prometheus, Grafana and Jaeger

```bash
docker compose -f deployment/docker/compose.yml up -d --build    # first build takes ~5 min per native image
docker compose -f deployment/docker/compose.yml ps
```

To run JVM images instead, build the distributions on the host first:

```bash
./gradlew :gateway-app:installDist :control-plane:installDist
docker compose -f deployment/docker/compose.yml -f deployment/docker/compose.jvm.yml up -d --build
```

### What the first start generates

The one-shot `init` service writes everything secret into `deployment/docker/.local/`. That directory is
git-ignored and nothing in it is committed.

| Path | Content |
|---|---|
| `.local/certs/ca.pem` | Development CA. It signs the etcd, Admin API and Keycloak certificates |
| `.local/secrets/` | etcd passwords for the `gateway` and `control-plane` users, the Redis password, the Grafana admin password |
| `.local/e2e/users.json` | Passwords of the four Keycloak users (`viewer`, `editor`, `publisher`, `admin`) |

Next, `etcd-auth` creates the etcd users and roles. The gateway gets read-only config access and can write only
its own `nodes/` heartbeat. Only then do the gateways and the control plane start.

### Ports

Every port is bound to `127.0.0.1`.

| URL | Service |
|---|---|
| http://localhost:8280 | Admin console |
| http://localhost:8080, http://localhost:8081 | gateway-1, gateway-2 (traffic) |
| https://localhost:9443 | Admin API (`/admin/v1`) |
| https://localhost:8543 | Keycloak (realm `gateway`) |
| http://localhost:3000 | Grafana (`admin` / `.local/secrets/grafana-admin`) |
| http://localhost:9090 | Prometheus |
| http://localhost:16686 | Jaeger |

Your browser will warn about the dev CA on 9443 and 8543. You can import `.local/certs/ca.pem` into your
browser or OS trust store, or open both URLs once and accept the warning. If you skip this, the console cannot
reach the Admin API.

### Check that it is up

```bash
CA=deployment/docker/.local/certs/ca.pem
curl -s --cacert $CA https://localhost:9443/admin/v1/ready      # control plane: keyspace migrated and readable
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/  # 503 until the first config is published
```

- A fresh stack answers 503 on the gateway. This is expected: a gateway never reports ready with an empty
  config. Publish your first route ([user guide §4](user-guide.md#4-walkthrough-your-first-api)) and it
  answers 404 for unknown paths and proxies the paths you configured.
- The gateways' status port (9901: `/healthz`, `/readyz`, `/configz`, `/metrics`) is not published to the host.
  Read it from inside the Compose network, for example through Prometheus at http://localhost:9090/targets.

### Stop, reset

```bash
docker compose -f deployment/docker/compose.yml down                                   # keep data
docker compose -f deployment/docker/compose.yml down -v && rm -rf deployment/docker/.local   # wipe everything
```

`down -v` deletes etcd data, the audit file and the gateways' last-known-good snapshots.

## 4. Running the images in your own environment

The Compose file is a dev stack: it uses a throwaway CA, Keycloak in dev mode and a single etcd member. In
staging or production, run the same images under your own orchestrator, and keep the hardening that
Compose applies (the `x-hardened` anchor):

```bash
--read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true --user 10001:10001
```

### Gateway

The full variable list is in [configuration → Bootstrap](../operations/configuration.md#bootstrap-environment).
This is the minimum for etcd-backed config:

```bash
docker run -d --name gateway --read-only --tmpfs /tmp --cap-drop ALL \
  --security-opt no-new-privileges:true --user 10001:10001 \
  -p 8080:8080 \
  -e GW_GATEWAY_ID=gw-a1 \
  -e GW_ETCD_ENDPOINTS=https://etcd-1:2379,https://etcd-2:2379,https://etcd-3:2379 \
  -e GW_ETCD_CA=/certs/etcd-ca.pem \
  -e GW_ETCD_USER=gateway -e GW_ETCD_PASSWORD_FILE=/secrets/etcd-gateway \
  -e GW_ETCD_PREFIX=/gw/prod/ \
  -e GW_LKG_DIR=/var/lib/gateway/lkg \
  -v /srv/apigw/etcd-ca.pem:/certs/etcd-ca.pem:ro \
  -v /srv/apigw/secrets:/secrets:ro \
  -v gw-lkg:/var/lib/gateway/lkg \
  apigw/gateway:0.9.0
```

- Passwords are always read from files (`*_PASSWORD_FILE`), never from environment values.
- Mount a persistent `GW_LKG_DIR`. Without it, a gateway that restarts while etcd is down has nothing to serve.
- Keep the status port (`GW_STATUS_PORT`, 9901) internal. Use `/readyz` for load-balancer health checks and
  scrape `/metrics` from Prometheus.
- Optional settings:
  - `GW_REDIS_URL`, `GW_REDIS_USER` and `GW_REDIS_PASSWORD_FILE`: Redis for global rate limits and the L2 cache.
  - `GW_TLS_CERT` and `GW_TLS_KEY`: a TLS listener on 8443.
  - `GW_TRACING_ENDPOINT`: an OTLP collector.
- Stopping: SIGTERM drains for `GW_SHUTDOWN_GRACE_SECONDS` (default 20 s). Give the container a stop timeout
  longer than that, for example `docker stop -t 30`.

### Control plane

Variables: [control plane → Environment](../operations/control-plane.md#environment). The control plane needs:

- etcd credentials for the `control-plane` role
- an HTTPS certificate (`CP_TLS_CERT`, `CP_TLS_KEY`)
- your IdP:
  - `CP_OIDC_ISSUER` and `CP_OIDC_AUDIENCE`;
  - exactly one of `CP_OIDC_DISCOVERY`, `CP_OIDC_JWKS_URI` or `CP_OIDC_JWKS_FILE`;
  - `CP_OIDC_ROLES_CLAIM` if your IdP uses a different claim name.
- `CP_CORS_ORIGINS` set to the console origin.
- `CP_AUDIT_FILE` on a persistent volume, if you want an audit trail on disk.

The control plane is stateless, so run two or more replicas behind a load balancer.

### Console

The console image serves static files. Mount two files:

- `/public/config.json`, with `apiBase` and the `oidc` authority, client ID and scope;
- `sws.toml`, with the CSP `connect-src` set to exactly the Admin API origin and the IdP origin.

How to set up the IdP client: [admin console → Configuration (production)](../operations/admin-console.md#configuration-production).

### etcd

Run a 3-member etcd cluster with TLS and auth, and start it with `--max-txn-ops=128`. That value must match
`CP_ETCD_MAX_TXN_OPS`. Create:

- the `gateway` user: read on config prefixes, write on `nodes/` only;
- the `control-plane` user: write on config prefixes, no access to `nodes/`.

`deployment/docker/init/init.sh` (`etcd-auth`) shows the exact role grants. Back up etcd with the
`etcd-tools` image: [backup and restore runbook](../operations/etcd-backup-restore.md).

## 5. Production checklist

- [ ] Your own CA and certificates for etcd, the Admin API and the gateway TLS listener. Never use `.local/`.
- [ ] A real IdP client. It must use authorization code with PKCE S256, issue tokens with
      `aud` = `CP_OIDC_AUDIENCE`, and include a roles claim.
- [ ] etcd: 3 members, auth enabled, both role users created, `--max-txn-ops` aligned with the control plane.
- [ ] `settings.upstreamAllowCidrs` lists only the private ranges your upstreams actually use. Never use `0.0.0.0/0`.
- [ ] Redis, if used, has a dedicated ACL user and a password file. Choose fail-open or fail-closed per plugin.
- [ ] A persistent LKG volume per gateway.
- [ ] Status and metrics ports are reachable only from monitoring.
- [ ] Scheduled etcd backups and a rehearsed restore.

## 6. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| Gateway answers 503 on every path | No config published yet | Publish a change set. `/configz` `state` shows `NO_CONFIG` |
| Console shows a network error after sign-in | The browser does not trust the dev CA for `:9443` | Open https://localhost:9443/admin/v1/health once and accept the warning, or trust `ca.pem` |
| Admin API returns 401 | Token expired (the dev realm issues 5-minute tokens) or wrong audience | Sign in again. In production, check `aud` and `CP_OIDC_ISSUER` |
| Admin API returns 403 | Your role is too low (for example, an editor trying to publish) | See the role table in the [user guide](user-guide.md#2-sign-in) |
| Gateway logs `etcd` auth errors | A password file changed without a restart, or the etcd user is missing | Re-run `etcd-auth` (`docker compose up etcd-auth`) or fix the secret, then restart the gateway |
| `config change rejected` / `INVALID_CONFIG` | A published change could not be compiled on that gateway (for example, a missing `secretFile`) | The gateway keeps serving its previous config. Check **Gateways** in the console and `GET /admin/v1/gateways` |
| Gateway is slow to stop | Long-lived streams draining | Expected, up to `GW_SHUTDOWN_GRACE_SECONDS` |
