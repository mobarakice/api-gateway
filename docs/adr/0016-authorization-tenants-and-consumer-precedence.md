# 0016 Authorization (RBAC/ABAC), tenant isolation, and consumer-scope plugin precedence

- Status: Accepted (2026-11-21, M4)

## Context
The spec requires RBAC and ABAC, strict tenant isolation, and plugin precedence consumer > route >
service > global. Invariant 8 says a feature that is off costs nothing per request. The consumer is
known only after authentication, at run time, whereas route/service/global chains are compiled.

## Options
- Authz: (a) an embedded policy engine (OPA/Rego, CEL); (b) **a small declarative policy model compiled
  to evaluator trees**. Engines bring interpreters, reflection and scripting, which invariant 7 rules
  out.
- Consumer precedence: (a) compile one chain per (route, consumer); (b) **per consumer, an array of
  override instances indexed by plugin ordinal, swapped into the route's chain slot when the consumer
  authenticates**. Option (a) multiplies memory by consumers × routes.

## Decision
- **Policy** (`policies/authz/{id}`, kind `AuthzPolicy`):
  - fields: `effect` allow|deny, `routes` (empty = any), `methods` (empty = any), `subjects`
    (`roles`, `consumers`, `anyAuthenticated`; absent = anyone) and an optional `condition` tree;
  - the condition tree uses `all`, `any`, `not`, and comparisons `{op, attr, value|values|ref}`;
  - ops: `eq`, `ne`, `in`, `prefix`, `contains`, `exists`;
  - attributes: `principal.subject|tenant|consumer|roles|attr.<claim>`, `request.method|path|header.<name>|param.<name>`,
    `route.id|tenant`. Nesting depth is ≤ 16.
- **`authz` plugin**, priority 1000:
  - it references policy IDs, which are compiled into rules indexed by route ID plus a route-agnostic
    list;
  - per request, the route's rules and the agnostic rules are evaluated, with no allocation;
  - **any matching deny wins**; otherwise any matching allow permits; otherwise 403, or 401 when
    nobody authenticated;
  - a tenant-scoped policy only matches routes of the same tenant.
- **Tenants**: `tenantId` on routes, services, upstreams, consumers, credentials and policies.
  - The validator rejects references to another tenant's object, and rejects a shared object that
    depends on a tenant's object.
  - A tenant route must have an authenticating plugin (`PluginFactory.authenticates()`) in its merged
    chain; otherwise the snapshot does not compile.
  - At run time, `Exchange.authenticate` refuses a principal whose tenant differs from the route's
    tenant (403, warn log). A global consumer is also refused on a tenant route.
  - A tenant route that reaches the proxy without a principal (e.g. `optional` auth) gets 401.
- **Consumer precedence**:
  - `consumers/{id}.plugins` compile to `Plugin[] overrides`, indexed by registry ordinal, with
    `DISABLED` for `enabled: false`;
  - after authentication, each later slot in the route's request, header and log chains uses the
    consumer's instance if one exists;
  - authenticating plugins cannot be set per consumer;
  - an override replaces only a plugin that the route's merged chain already contains. Adding a
    plugin only for some consumers needs a route-level plugin that the consumer then overrides or
    disables. This is documented in the authoring guide.
- **`plugin-configs/{id}`** (kind `PluginConfig`, `{plugin, config}`): any scope may say
  `{"name": X, "configRef": id}` instead of `config`. The ref must exist and be for the same plugin.

## Performance
- Authz is one map probe by route ID plus a linear pass over that route's rules; condition attributes
  are pre-parsed.
- Consumer overrides cost one array read per chain slot after authentication. Routes without auth
  pay nothing.

## Memory
Override arrays are `registry.size()` references per consumer that has plugin overrides. Consumers
without overrides share none (null).

## Native Image
Plain records and sealed interfaces; no reflection.

## Operational impact
- A tenant route without authentication is a config error, and its message names the route.
- Authz denials are logged at debug with the policy ID. Clients only see 401/403 problem bodies.
