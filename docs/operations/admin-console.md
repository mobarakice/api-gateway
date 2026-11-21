# Admin console

A web UI for the Admin API (ADR 0022). It never talks to etcd or the gateways.

## Run it locally

```bash
./gradlew :gateway-app:installDist :control-plane:installDist
docker compose -f deployment/docker/compose.yml up -d --build
open http://localhost:8280          # console
```

The first `up` generates a dev CA, certificates, etcd/Redis passwords and four Keycloak users in
`deployment/docker/.local/` (git-ignored). The users' passwords are in `.local/e2e/users.json`:

| User | Role | Can |
|---|---|---|
| `viewer` | viewer | read everything except audit |
| `editor` | editor | create change sets, stage edits, issue API keys, validate |
| `publisher` | publisher | everything an editor can do, plus publish and roll back |
| `admin` | admin | everything a publisher can do, plus settings, certificates and audit |

The browser will warn about the dev CA for `https://localhost:9443` (Admin API) and
`https://localhost:8543` (Keycloak). Trust `.local/certs/ca.pem` or accept the warnings once.

To reset everything, run `docker compose -f deployment/docker/compose.yml down -v && rm -rf deployment/docker/.local`.

Ports, all bound to 127.0.0.1:
- console: 8280
- gateways: 8080 and 8081
- Admin API: 9443
- Keycloak: 8543

## Day-to-day use

1. **Pick or create a change set** in the header. Every edit is staged there; nothing reaches the gateways until it is published.
2. **Edit objects.** Routes, services, upstreams and consumers have structured forms. Plugin configuration forms are generated from each plugin's JSON Schema, and the browser checks the same rules the server does. Fields without a form control (timeouts, retries, health checks, and so on) are under **Other fields (JSON)**.
3. **Issue API keys** on a consumer's page. The key is shown once; copy it then. Revoking a key is staged like any other change.
4. **Validate** the change set. This runs the gateway's own compiler over the result.
5. **Publish** (publisher). The dialog shows the full diff, and you must confirm you reviewed it. The change is applied in one transaction, and every gateway picks it up within seconds; **Gateways** shows any gateway that is behind.
6. **Roll back** from **History** (publisher). This publishes the inverse of an entry. It is refused if a later publish changed the same objects; roll that one back first.

Errors:
- **412:** someone else changed the object since you loaded it. Reload it and stage your edit again.
- **422:** each problem is shown on its field and in a summary at the top.

Sessions: tokens are kept in memory only. Reloading the page signs you in again through the identity provider, usually without a prompt. **Sign out** ends the IdP session too.

## Configuration (production)

The image serves static files only. Mount two files:

- `/public/config.json`:
  `{"apiBase": "https://admin-api.example.com", "oidc": {"authority": "https://idp.example.com/realms/x", "clientId": "admin-console", "scope": "openid"}}`.
  Both URLs must be https.
- `sws.toml` (`SERVER_CONFIG_FILE`): copy `deployment/docker/console/sws.toml`, and set the CSP's
  `connect-src` to exactly the Admin API origin and the IdP origin.

The IdP client must be:
- a public client using authorization code with PKCE S256;
- with redirect URI `{console}/callback`;
- with web origin `{console}`.

Its access tokens need:
- `aud` containing the control plane's `CP_OIDC_AUDIENCE`;
- a `roles` claim (or `CP_OIDC_ROLES_CLAIM`) with `viewer`, `editor`, `publisher` or `admin`.

The control plane must list the console origin in `CP_CORS_ORIGINS`.

## Development

```bash
cd admin-console
pnpm install
pnpm dev            # http://localhost:5173, proxies /admin to https://localhost:9443
pnpm lint && pnpm typecheck && pnpm test
pnpm e2e            # Playwright against the running Compose stack (first: pnpm exec playwright install chromium)
```
