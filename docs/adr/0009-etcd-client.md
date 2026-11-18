# 0009 etcd client

- Status: Proposed (decided by the M1 spike)

## Context
The gateway needs range (paginated, at a revision), watch (with progress notifications), and leases (node heartbeat). The control plane additionally needs txn with compares. Everything must work over TLS with auth, and in Native Image.

## Options
1. jetcd (gRPC-java + Netty + protobuf).
2. etcd's v3 gRPC-gateway HTTP/JSON API over the Vert.x HTTP client: `/v3/kv/range`, `/v3/kv/txn`, `/v3/watch` (a streamed chunked JSON response), `/v3/lease/*`, and `/v3/auth/authenticate` for a token.

## Spike criteria
- Connect over TLS with username/password auth.
- Range-read a key.
- Watch a key and receive an event.
- Get a permission error when writing with the read-only role.
- All of the above on the JVM and in a native binary.
- Compare native binary size and RSS delta, and API coverage (progress notify, compaction errors, lease keepalive).

## Decision
To be recorded after the spike.
