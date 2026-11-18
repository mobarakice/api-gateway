# 0007 Logging backend

- Status: Accepted (2026-11-18, M1)

## Context
Logging must be structured JSON via SLF4J, asynchronous with a bounded buffer so it never blocks an event loop, and native-image friendly. Secrets are never logged.

## Options
1. Logback with an async appender and a JSON encoder.
2. Log4j2 async loggers. These rely on reflection and plugin scanning.
3. A minimal SLF4J 2 provider that writes JSON lines through a bounded queue drained by one writer thread.

## What M1 showed
- With Logback, `nativeCompile` fails. Netty initialises some classes at build time, `io.netty.channel.nio.AbstractNioChannel` among them, and they cache an SLF4J logger, which puts a `ch.qos.logback.classic.Logger` into the image heap.
- Making Logback build-time initialised would parse its configuration at build time. That freezes `GW_LOG_LEVEL` and the appenders into the binary.
- The reachability-metadata route needs GraalVM 25.0.1 or newer (see `docs/environment.md`).

## Decision
Option 3, implemented in `gateway-observability` (`apigw.observability.logging`):
- `JsonLogger` holds only its name, so it is safe in the image heap. It is registered for build-time initialisation in that module's `native-image.properties`.
- Levels (`GW_LOG_LEVEL`) and the sink are runtime-initialised holders.
- `io.netty` and `io.vertx` loggers are held at WARN or above.
- The sink is an `ArrayBlockingQueue` of 8192 lines. `offer` never blocks: when the queue is full the line is dropped, and the drop is counted and reported by the writer.
- One daemon writer thread drains the queue to stdout and runs until the process halts, so shutdown-hook logs still reach stdout. `JsonLogging.flush()` drains on shutdown.
- MDC is a no-op, because thread-local context is wrong on shared event loops. Request context is passed explicitly.

## Impact
- **Performance:** rendering happens on the caller thread (one `StringBuilder`), followed by a lock-free-ish bounded enqueue. Per-request access logs are opt-in (M8).
- **Memory:** bounded by queue size multiplied by line size.
- **Native Image:** no reflection, no XML. Verified by `nativeTest`, and by `nativeIntegrationTest` checking the log lines of the native binary.
- **Operational:** one JSON object per line on stdout, with fields `ts`, `level`, `logger`, `thread`, `msg`, and `error`/`stack` when present.
