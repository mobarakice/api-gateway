# 0002 Module layout

- Status: Accepted (2026-11-18)

## Context
The spec suggests a layout and allows merges or splits with an ADR. The gateway and control plane share config types and schemas. The console is a separate stack.

## Options
1. A single gateway module plus a control-plane module.
2. The suggested split: `config-model`, `gateway-core`, `gateway-plugins`, `gateway-proxy`, `gateway-config`, `gateway-observability`, `gateway-app`, `control-plane`, `integration-tests`, `benchmarks`, plus `admin-console` (pnpm).

## Decision
Option 2, with no merges. Shared build rules live in `build-logic/` convention plugins, and all versions live in `gradle/libs.versions.toml`.
- `config-model` is the only module shared by the gateway and the control plane, so both validate against the same schema files.
- `gateway-core` does not depend on `gateway-proxy`, `gateway-plugins`, or `gateway-config`. Wiring happens in `gateway-app` with constructors.

## Impact
- Performance: none at runtime. One native image is linked from all gateway modules.
- Memory: none.
- Native Image: metadata lives in `gateway-app/src/main/resources/META-INF/native-image/`. Library-specific metadata comes from the libraries or the reachability repository.
- Operational: modules are independently testable, and the dependency direction is enforced by the build graph.
