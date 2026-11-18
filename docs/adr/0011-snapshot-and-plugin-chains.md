# 0011 Immutable snapshots and compile-time plugin chains

- Status: Accepted (2026-11-19, M2)

## Context
Invariants 3 and 8: requests read only an in-memory snapshot that is swapped atomically, and a disabled
feature must cost nothing per request. Plugins are compiled in (invariant 7).

## Options
1. Mutable registries updated in place (routes, plugin configs), read under locks or copy-on-write maps.
2. **One immutable `Snapshot` per config revision**, built and compiled off the event loop, published
   with a single volatile write; per-route plugin arrays resolved at compile time.

## Decision
Option 2.
- `SnapshotCompiler` validates the whole `GatewayConfig` (`ConfigValidator`), instantiates every enabled
  plugin through its `PluginFactory`, merges scopes (route > service > global; consumer scope arrives
  with M4), sorts by factory priority, and stores one array per phase in `CompiledRoute`.
- An instance joins a phase only if the factory declares the phase **and** the instance implements the
  phase's interface. A factory can return a narrower instance for a narrower config (the `headers`
  plugin returns a request-only instance when only request edits are configured).
- Identical plugin configs at a scope compile to one shared instance.
- Any error anywhere rejects the whole config with every error listed (`ConfigException`), so a
  partial snapshot can never be published.
- `SnapshotHolder` is one `volatile` field. A request reads it once and keeps that snapshot.
- `Snapshot.generation` increases per compile so per-event-loop state (upstream pools) can tell a newer
  snapshot from an older one still used by in-flight requests.

## Performance
Per request: one volatile read, then array iteration per phase. A route with no plugins has empty
arrays and runs no plugin code. Compile cost is paid once per config change, off the event loop.

## Memory
One snapshot live plus the previous one while in-flight requests finish. 10K routes compile in well
under a second on the dev host (unit test `scalesToManyRoutes`).

## Native Image
No reflection: config is decoded from the Jackson tree by hand (`ConfigDecoder`), plugins are listed
in `BuiltinPlugins`. Unit tests of `config-model`, `gateway-core` and `gateway-plugins` run under
`nativeTest`.

## Operational impact
A rejected config never changes traffic; the error list names each field path
(e.g. `routes[3].plugins[0].config.request.set.Host: header 'Host' cannot be edited`).
