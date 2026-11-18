# 0004 No Spring Boot

- Status: Accepted (2026-11-18)

## Context
The hard constraints forbid Spring Boot without an ADR that proves a need. The targets are a cold start of 100 ms or less and an idle RSS of 64 MB or less.

## Options
1. Spring Boot (with AOT) for the control plane.
2. Plain Vert.x and constructor wiring for both the gateway and the control plane.

## Decision
Option 2. The Admin API is a small REST surface, and Vert.x Web covers routing, body handling, and auth hooks. No need outweighs the startup, footprint, and Native Image cost of Spring.

## Impact
- Performance: faster startup, smaller footprint.
- Memory: lower RSS.
- Native Image: no Spring AOT step.
- Operational: wiring is explicit code in `gateway-app` and `control-plane`.
