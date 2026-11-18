# Risk register

| # | Risk | Likelihood | Impact | Mitigation | Owner milestone |
|---|---|---|---|---|---|
| R1 | 100K RPS on the 2 vCPU / 4 GB reference host is unlikely for the full pipeline (most Netty-based proxies do roughly 25–50K RPS per 2 cores) | High | Target gap | Measure honestly, report the gap, report RPS per core, and show the vCPU count that reaches 100K | M10 |
| R2 | jetcd (gRPC + Netty + protobuf) fails or bloats under Native Image | Medium | etcd client choice | M1 spike against the HTTP/JSON gateway alternative; ADR 0009 | M1 |
| R3 | JSON Schema 2020-12 validator not native-friendly (reflection, resource loading) | Medium | Plugin validation | Verify in M2/M6; pre-load schemas at build time; reachability metadata | M2 |
| R4 | G1 is only available for Native Image on Linux with Oracle GraalVM; Serial GC can have worse tail latency under heavy allocation | Medium | p99.9 | Low-allocation hot path; measure Serial vs G1 on Linux; ADR 0008 | M10 |
| R5 | etcd watcher fan-out with large fleets (hundreds of pods) | Low now | etcd load | Document sizing; watch relay ADR if fleets exceed a few hundred pods | M3 |
| R6 | Redis in the request path adds latency and a failure mode | Medium | Latency, availability | Opt-in only, local token pre-fetch, strict timeouts, fail-open/closed per policy | M5 |
| R7 | Dev host is macOS arm64, not the Linux x86_64 reference | Certain | Benchmark validity | Label dev-host numbers; Linux binaries built in containers | M10 |
| R8 | Installed GraalVM is 25 GA (Sept 2025); security fixes in later 25.0.x updates missing | Medium | Security | Upgrade to the latest 25.0.x before M9 images; container build stage pins its own version | M9 |
| R9 | Netty/Vert.x native metadata gaps (e.g. native transports, ALPN) | Low | Native build | Vert.x ships native metadata; JDK ALPN only (no tcnative); nativeTest covers h2 | M1 |
| R10 | Logging backend allocates or blocks under load | Medium | Latency | Async bounded appender, drop + count on overflow; ADR 0007 | M8 |
| R11 | No Kubernetes cluster for multi-replica and drain tests | Certain | M9/M10 coverage | kind in Docker for functional checks; real cluster BLOCKED | M9 |
| R12 | Secrets leaking via logs or Admin API | Low | Security | Typed secret wrappers with redacted `toString`, write-only API fields, log-scan test | M4/M6 |
