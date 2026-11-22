# 0023 In-house metrics registry and W3C/OTLP tracing

- Status: Accepted (2026-11-22). Supersedes [0006](0006-metrics-and-tracing.md).

## Context
ADR 0006 planned Micrometer for metrics and the OpenTelemetry SDK for traces. While implementing M8 we found:
- **Context model.** The OTel SDK propagates through a `ThreadLocal` `Context`. Our request runs as callbacks on one event loop, with the
  exchange object as its context (ADR 0011). Using the SDK would mean `makeCurrent()`/`close()` around every callback, or ignoring its context
  entirely, in which case we would use only the span data model and the exporter.
- **Cardinality and cost.** We want every per-route meter resolved at snapshot compile time (invariant 8) and pruned when a route is removed.
  Micrometer can do this, but its meter lookup, tags and `MeterFilter` sit on a path we don't need. Its Prometheus registry also
  pulls in `prometheus-metrics-*` (protobuf exposition).
- **Native Image.** Micrometer and the OTel SDK have reachability metadata (OTel 1.68.0, Micrometer 1.18.0 checked), but they add about 20 jars,
  ServiceLoader-based SPI (autoconfigure, exporters) and reflection that we would have to review on every upgrade.
- **What we need is small.** Counters, gauges, fixed-bucket histograms, Prometheus text format, `traceparent` parse/format, a ratio sampler,
  and an OTLP/HTTP JSON exporter. All of these are well-specified formats, not cryptography.

## Options
1. Micrometer + OTel SDK (ADR 0006).
2. OTel SDK for traces only, an in-house registry for metrics.
3. In-house registry and tracer in `gateway-observability`, no new dependencies.

## Decision
Option 3.
- **Metrics:**
  - `MetricRegistry` holds `LongAdder` counters, function gauges and fixed-bucket histograms (bounds in seconds; `LATENCY` 1 ms–30 s, `CONTROL` 5 ms–60 s).
  - It exposes Prometheus text format 0.0.4 on the status port's `/metrics`, rendered on a worker thread.
  - Names and labels are validated, label sets are fixed at registration, and the route ID is the only per-route label. The raw path never appears.
  - `RouteTelemetryFactory` (a `PluginServices` service) resolves a route's meters when the snapshot compiles. `SnapshotAware.retainOnly` drops meters for routes that are gone.
  - Status-class counters are created lazily per slot, so an unused code costs no series.
- **Tracing:**
  - W3C Trace Context (`traceparent`; `tracestate` is passed through untouched).
  - Parent-based sampling (respect the inbound sampled flag, configurable) plus a ratio for new traces.
  - One SERVER span per request and one CLIENT span per upstream attempt, using HTTP semantic-convention attribute names.
  - Export is OTLP/HTTP JSON to `{endpoint}/v1/traces` from one daemon thread through a bounded queue. Drops are counted, never blocking.
  - An unsampled request allocates no span. Its inbound `traceparent` is forwarded unchanged, and its trace ID is kept for the access log
    (about 100 B: two substrings and a small holder, only when tracing is on and the caller sent a `traceparent`).
- **Access log:** one JSON line per request through the existing bounded async `LogSink` (ADR 0007), switchable with `GW_ACCESS_LOG`.
- **Config telemetry:**
  - The control plane writes `publishedAt` into `history/head` in the publish transaction.
  - Gateways see that key on their prefix watch, so `gw_config_propagation_seconds` measures publish-to-applied without a second clock source.
  - Accuracy depends on clock sync between hosts (NTP), as documented.

## Impact
- **Performance:** per request, a counter increment, one histogram observation per completion and per attempt, and one `System.nanoTime()` pair.
  There are no map lookups or string building, and the meters do not allocate.
  `TelemetryBenchmark` (dev host, JMH `-prof gc`): route meters per request (attempt + completion) take 19 ns with 0 B/op, and 41 ns with 4 threads contending.
  Parsing an unsampled inbound `traceparent` plus the sampling decision takes 23 ns and 104 B/op. With no parent the decision takes 1.4 ns and 0 B/op. The access log formats one line on the event loop and enqueues it.
  `GW_ACCESS_LOG=off` removes that cost.
- **Memory:** series = routes × (status slots used + histogram buckets) + upstreams × outcomes. The span queue is bounded (`GW_TRACING_QUEUE_SIZE`, default 8192).
- **Native Image:** no new dependencies and no reflection. The JDK `HttpClient` is used for export, and `nativeTest` covers the observability module.
- **Operational:**
  - Prometheus, Grafana and alert rules ship in `deployment/observability/` and are validated by promtool and by `ObservabilityIT` against real Prometheus and Jaeger.
  - The OTLP output is standard, so any collector (OTel Collector, Jaeger, Tempo) works.
  - What we give up: OTel's metrics API, log bridging, baggage, and exporters other than OTLP/HTTP JSON. Revisit if those are needed.
