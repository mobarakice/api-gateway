# Writing a plugin

Plugins are compiled in (CLAUDE.md invariant 7). A plugin consists of:

| Part | Where |
|---|---|
| `PluginFactory`: name, version, phases, priority, schema resource, `create(JsonNode)` | `gateway-plugins/src/main/java/apigw/plugins/<name>/` |
| JSON Schema 2020-12 of its `config` | `config-model/src/main/resources/schemas/plugins/<name>.json` |
| Registration | one line in `apigw.plugins.BuiltinPlugins` |
| Tests | unit tests next to the code, a JVM + native integration test, a JMH benchmark if it runs on every request |

## Lifecycle
1. The control plane and the gateway validate `config` against the same schema file.
2. When a snapshot compiles, the factory's `create(config)` validates again in code and returns an
   **immutable, thread-safe** instance. Throw `PluginConfigException` with every error; paths are
   relative to `config` (`request.set.Host`). The compiler prefixes the scope path.
3. The instance is shared by every route with the same config at that scope, on every event loop.
   Never block, never do I/O except through async clients with timeouts (and only where an ADR allows
   it), never keep per-request state in fields.

## Phases and interfaces

| Phase | Interface | Runs |
|---|---|---|
| `REWRITE` | `RequestPlugin` | first; path/header rewrites |
| `ACCESS` | `RequestPlugin` | IP rules, authn, authz, rate limits |
| `BEFORE_PROXY` | `RequestPlugin` | request transforms, cache lookup |
| `HEADER_FILTER` | `ResponseHeadersPlugin` | upstream response headers known, body not yet sent |
| `LOG` | `LogPlugin` | once, after the exchange completed or failed |

An instance joins a phase only if the factory lists the phase **and** the instance implements its
interface. Return a narrower instance for a narrower config so unconfigured phases cost nothing.

`RequestPlugin.onRequest` returns:
- `CONTINUE`: next plugin, then proxy.
- `STOP`: you responded (use `exchange.respond(status, title)`: generic RFC 9457 body, request ID, no
  internal detail).
- `PENDING`: you started async work; call `exchange.resume()` or `respond(...)` later on the same
  event loop.

A plugin that throws fails only its request (500) in request phases, or is skipped in late phases; the
error is logged with route ID and request ID.

## Precedence and order
Scopes: consumer > route > service > global. A narrower scope replaces a wider one by plugin name; a
narrower entry with `"enabled": false` removes the plugin for that route. Within a phase, higher
`priority()` runs first; ties break by name.

| Plugin | Phases | Priority |
|---|---|---|
| `headers` | BEFORE_PROXY, HEADER_FILTER | 0 |

## Built-in: `headers`
```json
{"name": "headers", "config": {
  "request":  {"remove": ["x-internal"], "set": {"x-env": "prod"}, "add": {"x-tag": "a"}},
  "response": {"remove": ["server"], "set": {"cache-control": "no-store"}}}}
```
Per side: remove, then set, then add. Names are case-insensitive tokens; values are visible ASCII,
space or tab. `Host`, `Content-Length` and hop-by-hop headers cannot be edited. Request edits apply to
the headers sent upstream (after forwarding headers are computed), so a plugin can override
`x-forwarded-proto` deliberately but cannot change framing.
