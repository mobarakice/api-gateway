# API Gateway

A high-performance API gateway in Java 25, compiled with GraalVM Native Image.

- Configuration lives in etcd and is published atomically through an Admin API with change sets, validation,
  history and rollback.
- Admins work in a schema-driven web console.
- Built-in plugins cover JWT/OIDC, API keys, authorization, rate limiting, caching, CORS, header and request
  transforms, and IP rules.

## Quick start (local Docker stack)

```bash
docker compose -f deployment/docker/compose.yml up -d --build   # first build: ~5 min per native image
open http://localhost:8280                                      # console; users/passwords: deployment/docker/.local/e2e/users.json
curl -i http://localhost:8080/                                  # gateway (503 until the first config is published)
```

## Guides

| Guide | For |
|---|---|
| [Deploying with Docker](docs/guides/deploy-docker.md) | The Compose dev stack, images, and running containers in your own environment |
| [Deploying on Kubernetes](docs/guides/deploy-kubernetes.md) | Kustomize overlays, secrets, first config, exposure, day-2 operations, kind |
| [User guide](docs/guides/user-guide.md) | Sign-in, change sets, and configuring upstreams, services, routes, plugins and consumers (console + Admin API) |

## Reference

- Architecture: [`docs/architecture/`](docs/architecture/overview.md), decisions in [`docs/adr/`](docs/adr/)
- Operations:
  - [gateway configuration](docs/operations/configuration.md)
  - [control plane](docs/operations/control-plane.md)
  - [admin console](docs/operations/admin-console.md)
  - [Kubernetes](docs/operations/kubernetes.md)
  - [observability](docs/operations/observability.md)
  - [etcd backup/restore](docs/operations/etcd-backup-restore.md)
- Admin API: [`docs/api/admin-v1.yaml`](docs/api/admin-v1.yaml) (OpenAPI)
- Plugins: [`docs/plugins/authoring.md`](docs/plugins/authoring.md)
- Security: [`docs/security/threat-model.md`](docs/security/threat-model.md)
- Contributing / build commands: [`CLAUDE.md`](CLAUDE.md), status in [`PROGRESS.md`](PROGRESS.md)
