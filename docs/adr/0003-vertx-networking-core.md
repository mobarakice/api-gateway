# 0003 Vert.x 5 as the networking core

- Status: Accepted (2026-11-18)

## Context
The data plane must be non-blocking, support HTTP/1.1, h2 (ALPN), and h2c, stream with backpressure, and compile to Native Image.

## Options
1. Raw Netty handlers.
2. Vert.x 5 (on Netty).
3. JDK `HttpServer` / Helidon Níma on virtual threads.

## Decision
Option 2.
- Vert.x provides the HTTP/1.1 and h2 server and client, connection pools, `Pipe` backpressure, timers, an async Redis client, and a WebClient.
- It ships Native Image metadata.
- Its event-loop-per-core model matches the latency targets.
- We drop to raw Netty handlers only where a profile shows Vert.x overhead matters, recorded with a new ADR.
- TLS uses JDK SSL with JDK ALPN. netty-tcnative (OpenSSL) is not used, to avoid native libraries in the image. This is revisited with a benchmark in M10 if TLS becomes the bottleneck.

## Impact
- Performance: a thin layer over Netty, so expect a few percent overhead versus raw Netty.
- Memory: pooled direct buffers. Allocator settings are tuned in M10.
- Native Image: supported upstream. `nativeTest` covers h1, h2, and h2c in M1.
- Operational: one framework for server, client, timers, and Redis.

## Amendment (2026-11-19, M2): Netty QUIC classes
- Vert.x 5.2's `ConnectionBase.remoteAddress()` does `instanceof io.netty.handler.codec.quic.QuicChannel`.
  With `netty-codec-classes-quic` excluded (M1), every `remoteAddress()` call throws
  `NoClassDefFoundError` on the JVM. M1 never called it; the M2 forwarding-header code does.
- So `netty-codec-classes-quic` stays on the classpath (pure Java, no native-image metadata). HTTP/3,
  native QUIC and tcnative stay excluded.
- In Native Image, `io.netty.handler.codec.quic` initialises at run time (`BoringSSL.<clinit>` needs the
  excluded native library), and so does `io.netty.handler.ssl.SslContext`, whose static `SecureRandom`
  would otherwise be seeded at build time and baked into the binary. Both flags are in
  `gw.native-conventions`.
