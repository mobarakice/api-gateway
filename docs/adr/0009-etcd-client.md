# 0009 etcd client

- Status: Accepted (2026-11-18, M1 spike)

## Context
The gateway needs:
- paginated range reads at one revision;
- watch with progress notifications and compaction detection;
- leases, for the node heartbeat.

The control plane also needs `txn` with `mod_revision` compares. Everything has to work over TLS with auth, and in Native Image.

## Options
1. **jetcd** (gRPC via Vert.x gRPC, protobuf).
2. **etcd's v3 HTTP/JSON gateway (grpc-gateway)** called through the Vert.x 5 HTTP client already in the gateway: `/v3/auth/authenticate`, `/v3/kv/range|put|txn`, `/v3/watch` (a chunked stream of newline-delimited JSON), `/v3/lease/*`.

## Spike results
**Option 1 was disqualified at the dependency level.** I did not build it.
- jetcd 0.8.8, the latest release (2026-11-09), depends on `io.vertx:vertx-grpc:4.5.32`, which needs Vert.x **4**.
- The gateway runs Vert.x **5.2.3**. Gradle would resolve one `vertx-core` for both, leaving a Vert.x 4 gRPC module running against Vert.x 5 core. That is binary-incompatible.
- The alternative is shading a second Vert.x/Netty stack into the native image.
- On top of that, jetcd has no entry in the GraalVM reachability metadata repository (checked 2026-11-18).

**Option 2 passes every spike criterion, on the JVM and in the native binary.** Evidence is in `integration-tests`: `EtcdClientIT` (7 tests) and `EtcdCheckIT` (3 tests). Both suites run with `integrationTest` (JVM) and `nativeIntegrationTest` (native binary), against the official `gcr.io/etcd-development/etcd:v3.7.3` image with server TLS and auth enabled. Covered:
- TLS + username/password auth. A wrong password is rejected and never echoed.
- Range read, count-only read, and prefix reads.
- The gateway role cannot write `routes/`. This is checked with a never-true compare txn that writes nothing. The role can write its own `nodes/` key, and cannot read outside its prefix.
- Watch from revision + 1 delivers PUT and DELETE events, and a client cancel ends the stream exactly once.
- A watch from a compacted revision is canceled with `compact_revision`, which the client maps to `OUT_OF_RANGE` so the worker knows to resync.
- An unreachable endpoint gives a transport error and the client rotates to the next endpoint.

## Decision
Option 2: `apigw.config.etcd.EtcdHttpClient` (fully async, Vert.x HTTP client, Jackson tree model).

## Impact
- **Performance:** etcd is never in the request path. JSON plus base64 costs more than protobuf, but only during config load and watch. A full range of 10K routes is parsed on the config worker's context, not on a traffic event loop. To be measured in M3/M10.
- **Memory:** no gRPC, protobuf or second Netty in the image. The native gateway binary is about 47 MB including the client.
- **Native Image:** needs no reflection metadata. The Jackson tree model is used only for etcd payloads.
- **Operational:**
  - grpc-gateway is enabled by default on etcd's client port.
  - Simple-token TTL expiry is handled by one re-authentication and retry.
  - In etcd 3.7, `--trusted-ca-file` enforces client certificates. mTLS to etcd (client certs) is supported by `EtcdClientConfig` and exercised in M9.
- **Limits to revisit in M3:**
  - Watch streams use HTTP/1.1, so each watch holds one connection.
  - The client relies on the worker's silence detection, not gRPC keepalive.
