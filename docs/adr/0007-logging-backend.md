# 0007 Logging backend

- Status: Proposed (accept after native verification in M1, load check in M8)

## Context
We need structured JSON logs via SLF4J, async with a bounded buffer so logging never blocks an event loop. The backend must be Native Image friendly. Secrets are never logged.

## Options
1. Logback with an async appender and a JSON encoder.
2. Log4j2 async loggers. These rely on reflection and plugin scanning, which is awkward in native builds.
3. A minimal SLF4J provider writing JSON lines through a bounded ring buffer on one writer thread.

## Decision (proposed)
Option 1 if Logback (configured programmatically, with no XML/Joran scanning in the native build) works under `nativeTest`. Otherwise, option 3.

The JSON encoder must not depend on reflection-heavy libraries. The JSON line is written with Jackson's streaming `JsonGenerator`.

Overflow policy:
- Drop DEBUG, INFO, and WARN when the queue is full, and count the drops in a metric.
- ERROR uses a bounded `offer` with a short timeout, and only on non-event-loop threads.

## Impact
- Performance: one bounded enqueue per log call. Request logs are opt-in per route.
- Memory: the queue size is fixed (default 8192 events).
- Native Image: verified in M1 by running the server's startup log line through the native binary.
- Operational: JSON to stdout, collected by the platform.
