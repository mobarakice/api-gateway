# 0006 Metrics: Micrometer + Prometheus; tracing: OpenTelemetry

- Status: Superseded by [0023](0023-in-house-metrics-and-tracing.md) (2026-11-22)

## Context
We need Prometheus-scrapable metrics with bounded cardinality, low per-request overhead, and W3C trace context propagation, and both must work in Native Image.

## Options
1. OpenTelemetry for metrics and traces.
2. Micrometer (Prometheus registry) for metrics, OpenTelemetry SDK for traces.

## Decision
Option 2.
- Micrometer's meters are cheap to record on the hot path: pre-registered per route at snapshot compile time, so there is no per-request lookup.
- The Prometheus exposition is mature and Micrometer has Native Image support.
- Tracing uses the OpenTelemetry SDK with W3C `traceparent`, a configurable ratio sampler, and an OTLP exporter with a bounded batch queue.
- Metric labels use the route ID, never the raw path.

## Impact
- Performance: one counter increment and one histogram record per request, using precomputed meter references. Tracing is not allocated for unsampled requests beyond context propagation.
- Memory: bounded by route count multiplied by the number of meters.
- Native Image: both are verified in M8 with `nativeTest`.
- Operational: standard Prometheus scrape, OTLP to a collector.
