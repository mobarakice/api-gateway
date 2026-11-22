# Observability

How the gateway and the control plane expose metrics, logs and traces, and how to respond to each shipped alert.
Design: [ADR 0023](../adr/0023-in-house-metrics-and-tracing.md). Assets: `deployment/observability/`.

| Asset | Path |
|---|---|
| Prometheus scrape config (Compose) | `deployment/observability/prometheus/prometheus.yml` |
| Recording + alert rules, promtool unit tests | `deployment/observability/prometheus/rules/apigw-alerts{,.test}.yml` |
| Grafana dashboard "API Gateway" (uid `apigw-gateway`) | `deployment/observability/grafana/dashboards/apigw-gateway.json` |
| Grafana provisioning (Prometheus + Jaeger datasources) | `deployment/observability/grafana/provisioning/` |

Local stack: `docker compose -f deployment/docker/compose.yml up -d` starts Prometheus (http://localhost:9090), Grafana
(http://localhost:3000, user `admin`, password in `deployment/docker/.local/secrets/grafana-admin`) and Jaeger (http://localhost:16686).

Validate rules after editing:

```bash
docker run --rm -v "$PWD/deployment/observability/prometheus/rules:/r" -w /r --entrypoint promtool prom/prometheus:v3.16.0 check rules apigw-alerts.yml
docker run --rm -v "$PWD/deployment/observability/prometheus/rules:/r" -w /r --entrypoint promtool prom/prometheus:v3.16.0 test rules apigw-alerts.test.yml
./gradlew :integration-tests:integrationTest --tests '*ObservabilityIT'   # real Prometheus + Jaeger, every dashboard query
```

## Endpoints

| Process | Listener | Path | Notes |
|---|---|---|---|
| Gateway | `GW_STATUS_PORT` (9901) | `/metrics` | Prometheus text 0.0.4. Shares the port with `/healthz`, `/readyz`, `/configz`. Internal only |
| Control plane | `CP_METRICS_PORT` (9902, `0` = off) | `/metrics` | Plain HTTP, separate from the TLS Admin API. Internal only |
| etcd | `--listen-metrics-urls` | `/metrics` | etcd's own metrics (DB size, leader, proposals) |

Neither metrics listener requires authentication. Restrict it with a NetworkPolicy or a firewall. Metrics contain route, upstream and
service IDs, never paths, hosts, IPs, consumers or credentials.

### Kubernetes
Scrape the status port by pod with a `PodMonitor` (Prometheus Operator) or the `prometheus.io/scrape` annotations, using
`job="apigw-gateway"` for gateways and `job="apigw-control-plane"` for the control plane. The rules and the dashboard select on those job names.
Keep `instance` as the pod. `GatewayBehindOnRevision` compares gateways within a job.

## Metrics

Per-request metrics are resolved at snapshot compile time and pruned when a route or upstream is removed. `code` is a status class
(`1xx`–`5xx`) except for the individually tracked `401 403 404 408 413 429 499 502 503 504`.

### Traffic (gateway)
| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `gw_requests_total` | counter | `route`, `code` | Completed requests on a matched route |
| `gw_local_responses_total` | counter | `route`, `code` | Responses produced by the gateway (plugins, errors), not by the upstream. Authn/authz failures are `code="401"` / `"403"`, rate-limit rejections `"429"` |
| `gw_request_duration_seconds` | histogram | `route` | Time from request start to the last response byte |
| `gw_unrouted_requests_total` | counter | `code` | Rejected before routing (404, 400, 413, 431, ...) |
| `gw_connections_total`, `gw_connections_active` | counter, gauge | `listener` | Accepted and currently open client connections |

### Upstreams (gateway)
| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `gw_upstream_attempts_total` | counter | `upstream`, `outcome` | One per attempt. `outcome` = status class, or `connect_error`, `reset`, `timeout`, `pool_full`, `denied` (SSRF), `circuit_open` |
| `gw_upstream_response_seconds` | histogram | `upstream` | Attempt start to response headers |
| `gw_upstream_retries_total` | counter | `upstream` | Attempts after the first (ADR 0017) |
| `gw_upstream_inflight` | gauge | `upstream` | Requests currently using the pool (pool usage) |
| `gw_upstream_breakers_open` | gauge | `upstream` | Targets whose circuit breaker is open or half-open |

### Plugins and caches (gateway)
| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `gw_cache_lookups_total` | counter | `result` = `hit`, `stale`, `miss`, `bypass` | Response cache (ADR 0019). Hit ratio = hit / (hit + miss + stale) |
| `gw_cache_l1_bytes` | gauge | – | L1 cache size |
| `gw_redis_commands_total` | counter | `result` = `ok`, `failed` | Redis calls (global rate limit, L2 cache) |
| `gw_redis_command_seconds_total` | counter | – | Total time of successful Redis commands (mean = this / ok count) |
| `gw_redis_connected` | gauge | – | 1 while the Redis connection is up |

### Configuration (gateway)
| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `gw_ready` | gauge | – | 1 when `/readyz` is 200 |
| `gw_config_state` | gauge | `state` | 1 for the config worker's current state: `SYNCING`, `SERVING`, `SERVING_STALE`, `INVALID_CONFIG`, `NO_CONFIG` |
| `gw_config_applied_revision` | gauge | – | etcd revision of the snapshot serving traffic |
| `gw_config_etcd_revision` | gauge | – | Newest etcd revision seen |
| `gw_config_published_revision` | gauge | – | Revision of the newest control-plane publish seen (`history/head`) |
| `gw_config_propagation_seconds` | histogram | – | Control-plane publish (`publishedAt`) to snapshot applied. Needs NTP-synced clocks |
| `gw_config_etcd_last_success_timestamp_seconds` | gauge | – | Last successful etcd call. Staleness = now − this |
| `gw_config_snapshots_applied_total`, `gw_config_rejected_total` | counter | – | Snapshots swapped in, and snapshots rejected by validation |
| `gw_config_resyncs_total`, `gw_config_watch_restarts_total` | counter | – | Full resyncs (compaction, errors) and watch reconnects |
| `gw_etcd_request_seconds`, `gw_etcd_request_errors_total` | histogram, counter | `op` | etcd unary request latency and errors: `op` = `range`, `txn`, `put`, `grant`, `keepalive`, `revoke` (the watch stream is covered by `gw_config_watch_restarts_total`) |
| `gw_snapshot_routes`, `gw_snapshot_revision` | gauge | – | Size and revision of the live snapshot |

### Runtime (both processes)
`apigw_build_info{component,version,runtime}`, `process_start_time_seconds`, `process_cpu_seconds_total`, `process_cpu_count`,
`process_resident_memory_bytes` and `process_open_fds` (Linux only), `jvm_memory_used_bytes` / `jvm_memory_max_bytes{area}`, `jvm_gc_*`,
`jvm_threads_live`, `gw_log_queue_size`, `gw_log_dropped_total`, `gw_tracing_spans_total{result=exported|dropped|failed}`, `gw_tracing_queue_size`.
In the native binary `runtime="native"`; the JVM pool metrics report what Substrate VM exposes.

### Control plane
| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `cp_http_requests_total` | counter | `endpoint`, `method`, `code` | Admin API requests. `endpoint` is a fixed name (e.g. `changesets.publish`), never the path |
| `cp_http_request_duration_seconds` | histogram | `endpoint` | Admin API latency |
| `cp_etcd_request_seconds`, `cp_etcd_request_errors_total` | histogram, counter | `op` | etcd latency and errors from the control plane |

## Logs
Structured JSON Lines on stdout (ADR 0007), written asynchronously through a bounded queue. When the queue is full, lines are dropped and counted
in `gw_log_dropped_total`. Nothing blocks.

**Access log** (`"logger":"access"`, `GW_ACCESS_LOG=all|errors|off`; `errors` keeps 5xx and gateway-generated 4xx/5xx). Fields:
`requestId`, `traceId` (present when a trace context exists), `routeId`, `serviceId`, `upstreamId`, `consumerId`, `tenantId`, `method`, `protocol`,
`status`, `source` (`upstream` or `gateway`), `bytesSent` and `durationMs`.
Paths, query strings, headers, bodies, tokens and client IPs are never logged.

## Tracing
| Variable | Default | Meaning |
|---|---|---|
| `GW_TRACING_ENDPOINT` | unset (off) | OTLP/HTTP base URL. Spans are POSTed to `{endpoint}/v1/traces` as OTLP JSON |
| `GW_TRACING_SAMPLE_RATIO` | `0.01` | Share of new traces (requests without a parent) to record |
| `GW_TRACING_RESPECT_PARENT` | `true` | Follow the inbound `traceparent` sampled flag. `false` = apply the ratio to every request |
| `GW_TRACING_SERVICE_NAME` | `apigw-gateway` | `service.name` resource attribute |
| `GW_TRACING_QUEUE_SIZE` | `8192` | Spans buffered for export. Overflow is dropped and counted |

Each sampled request produces:
- a SERVER span `"{METHOD} {route path}"` with `http.route`, `apigw.route.id`, `apigw.upstream.id`, `apigw.request.id`,
  `http.request.method` and `http.response.status_code`;
- a CLIENT span per upstream attempt, whose ID is sent upstream in `traceparent`.

When a request is not sampled, the inbound `traceparent` is forwarded unchanged and no span is allocated.

## Alert runbooks
Thresholds are in the rules file. Every alert links to its section here.

### GatewayHighErrorRate
More than 5% of requests on a gateway job return 5xx for 5 minutes (with more than 1 req/s).
- Check the "Status codes" and "Upstream outcomes" panels. If the 5xx come from the upstream, escalate to its owner.
- If they come from the gateway (`gw_local_responses_total{code=~"5.."}`, i.e. 502/503/504), check breakers, pool saturation and recent publishes (`history/`).
### GatewayRouteHighErrorRate
A single route is above 10% 5xx for 10 minutes, at more than 0.5 req/s. Same steps, scoped to the route.
- Roll back the change set that introduced the route if the errors started at its publish.
### GatewayHighLatencyP99
p99 above 1 s for 10 minutes (with more than 1 req/s).
- Compare `gw_upstream_response_seconds` with `gw_request_duration_seconds`. If the upstream accounts for the difference, the upstream is slow.
- Otherwise check CPU saturation, Redis latency (rate limit and cache plugins) and the log queue.
### GatewayUpstreamPoolSaturated
More than 0.1/s of attempts fail with `pool_full`: the connection pool and its wait queue are full, and clients get 503. Compare `gw_upstream_inflight` with the pool size.
- Scale the upstream, raise `pool.maxConnections` / `pool.maxPending` in the upstream definition, or look for a slow upstream holding connections.
### GatewayCircuitBreakerOpen
Some upstream targets have open breakers and are getting no traffic. Check the health of those targets.
- Breakers half-open and close by themselves after the configured cool-down. No action is needed once the upstream recovers.
### GatewayCpuSaturated
The process is using more than 85% of its CPUs for 10 minutes. Scale out (gateways are stateless) or check for a traffic spike in "Request rate by route".
### GatewayHeapNearlyFull
Heap is above 90% of its maximum. Check cache size (`GW_CACHE_MAX_BYTES`), rate-limit keys (`GW_RATELIMIT_MAX_KEYS`) and route count.
- Raise the memory limit or `-Xmx` (or `-R:MaxHeapSize` for native).
### GatewayLogLinesDropped
The log queue overflowed. stdout is slow (a node logging agent is backpressuring), or `GW_ACCESS_LOG=all` is too much at this rate.
- Fix the log pipeline, or switch to `GW_ACCESS_LOG=errors`.
### GatewayTraceExportFailing
Spans are failing to export or being dropped. Check that the collector at `GW_TRACING_ENDPOINT` is reachable and healthy, and look for `trace export failed` in the logs.
- Traffic is unaffected.
### GatewayDown
Prometheus can't scrape a gateway. Check the pod or container status and the events (OOMKilled, crash loop), then the startup logs.
### GatewayNotReady
A gateway is up but not ready: it has no snapshot. This happens at first boot with etcd unreachable and no LKG.
- Check `/configz` and the logs. Read [configuration](configuration.md#etcd-source-m3).
### GatewayConfigStale
A gateway hasn't reached etcd for more than 2 minutes (state `SERVING_STALE`). It keeps serving its current or LKG snapshot.
- Check etcd health, the network and the gateway's etcd credentials (`gw_etcd_request_errors_total`).
### GatewayConfigRejected
A gateway rejected a snapshot (state `INVALID_CONFIG` or `gw_config_rejected_total` increased). The previous snapshot is still serving.
- Logs show the validation error. The control plane's dry-run should have caught this; check that the gateway and control-plane versions match.
### GatewayBehindOnRevision
A gateway's applied revision has been behind the newest publish any gateway in its job has seen, for 5 minutes.
- Check that gateway's watch (`gw_config_watch_restarts_total`, `gw_config_state`) and its etcd connectivity.
- Restarting it forces a resync from etcd.
### ConfigPropagationSlow
p99 publish-to-applied over 30 minutes is above 5 s.
- Check etcd latency (`gw_etcd_request_seconds`) and gateway CPU. Check clock skew between control plane and gateway hosts: the metric uses wall clocks.
### ControlPlaneDown
The Admin API's metrics endpoint can't be scraped. Gateways keep serving, but publishes and rollbacks are unavailable.
### ControlPlaneErrors
The Admin API is returning 5xx. Check `cp_etcd_request_errors_total` and the control-plane logs (problem details carry a request ID).
### EtcdDown
An etcd member can't be scraped. With a 3-member cluster one member down is tolerated. Restore it before a second one fails.
### EtcdNoLeader
A member reports no leader. Writes (publishes) fail and gateways keep serving their snapshots.
- Check network partitions and disk latency (`etcd_disk_wal_fsync_duration_seconds`).
### EtcdFrequentLeaderChanges
More than 3 leader changes in 15 minutes. This usually means disk or network latency on the members.
### EtcdDatabaseNearQuota
The DB is above 80% of `--quota-backend-bytes`. Compact and defragment (`etcdctl compact`, `etcdctl defrag`, one member at a time).
- Check `history/` and `audit/` retention (ADR 0021). If etcd raises the NOSPACE alarm, it rejects writes.
