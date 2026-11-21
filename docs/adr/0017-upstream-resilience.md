# 0017 Upstream resilience: retries, retry budget, circuit breaker, health checks

- Status: Accepted (2026-11-21, M5)

## Context
Spec §9 asks for:
- per-upstream circuit breakers (closed/open/half-open, failure-rate and slow-call thresholds over a
  sliding window, open duration, half-open concurrency);
- active and passive health checks with outlier ejection;
- retries only for idempotent or explicitly safe requests, bounded, jittered, with a per-route budget
  of at most 20%, never after a body has partially streamed.

Invariant 1 forbids blocking an event loop; invariant 8 makes a disabled feature cost nothing.

## Options
- Breaker: (a) Resilience4j; (b) **in-house, per upstream per event loop**. Resilience4j's core is
  plain Java, but its native-image story depends on its reflection-based config/registry layers, it is
  lock-based for multi-thread use, and we only need one small state machine. A breaker per event loop
  needs no synchronisation at all.
- Health state: (a) per loop for everything; (b) **passive per loop, active once per process**.
  Probing from every loop multiplies probe traffic by the loop count.

## Decision
- **Breaker** (`gateway-proxy/CircuitBreaker`, one per upstream definition per loop):
  - Window: `windowSeconds` one-second buckets with running totals, so recording is O(1).
  - Opens when there are ≥ `minCalls` calls and either failures ≥ `failureRatePercent` or slow calls
    (time to response head ≥ `slowCallMs`) ≥ `slowCallRatePercent`.
  - Open for `openMs`, then half-open with at most `halfOpenCalls` trials. Any failed trial reopens;
    `halfOpenCalls` successes close.
  - Permits are typed, so a late result from a "closed" call never counts as a trial.
  - Failures are connect errors, resets, timeouts and `failureStatuses` (default 502/503/504). Local
    pool overflow and SSRF denials are not failures.
  - Open → 503 for the client. Every state change is logged (metrics in M8).
- **Passive ejection** (`TargetGate`, per loop):
  - `consecutiveFailures` failed attempts (connect, reset, timeout, 502/503/504) eject a target for
    `ejectionMs × n`, where n is its ejection count, capped at 10×;
  - never more than `maxEjectionPercent` of targets.
- **Active checks** (`HealthChecker`, one per process):
  - `GET path` every `intervalMs` with `timeoutMs`, with the first probes spread over one interval;
    the verdict needs `healthyThreshold` / `unhealthyThreshold` consecutive results;
  - healthy statuses are `statuses`, default 2xx;
  - named targets go through the SSRF `TargetResolver`, like traffic;
  - verdicts are an `AtomicIntegerArray` read by all loops; targets start healthy;
  - probers are keyed by the upstream definition key and synced on every publish; removed upstreams
    stop probing.
- **Balancing with availability:** every balancer skips unavailable targets, and consistent hashing
  walks the ring so only the unavailable target's keys move. If no target is available, the upstream
  goes into **panic mode** and balances over all targets (warn log, rate-limited), because a wrong
  health signal must not black-hole traffic. Ungated upstreams keep the old path.
- **Retries** (`retries` on a route or service; the route wins; default none):
  - `attempts` 0–5, `on` = connect-failure | reset | timeout | 502 | 503 | 504, `methods` (default
    the idempotent set), jittered exponential backoff (`backoffMs`..`maxBackoffMs`, full jitter).
  - A retry prefers a different target.
  - A request with a body is retried only after a connect failure, when its body was never read
    (requests stay paused until connected). "Partially streamed" therefore never happens.
  - The timer for `timeouts.totalMs` spans all attempts.
- **Budget** (`RetryBudget`, per route per loop): over 10 s of 1 s buckets, retries are allowed while
  retries < `minRetriesPerSecond × 10 + budgetPercent% × requests` (default 20% + 3/s).
- mTLS to targets: `upstreams[].clientCert` {certFile, keyFile} (https only); the key stays in its file.

## Performance and memory
- Breaker, gate and budget are a few int arrays per upstream or route per loop; nothing is allocated
  per request on the healthy path.
- An upstream without these settings pays one null check per request; routes without retries never
  touch a budget.

## Native Image
- No new dependencies, no reflection. Probes reuse the Vert.x HTTP client.

## Operational impact
- New upstream fields: `healthCheck.active|passive`, `circuitBreaker`, `clientCert`. New route and
  service field: `retries`.
- Breaker and ejection state is per loop, so with N loops each needs its own evidence: a dead target
  is ejected by each loop after `consecutiveFailures` of *its* requests.
- Logs: "circuit breaker opened", "upstream target ejected", "upstream target unhealthy/healthy",
  "no available target ... (panic)".
