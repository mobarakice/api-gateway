# 0013 Request validation, path policy and forwarding headers

- Status: Accepted (2026-11-19, M2)

## Context
A proxy that interprets a request differently from the upstream enables request smuggling and
access-control bypass (path confusion). Forwarding headers decide which client IP plugins and
upstreams trust.

## Decision
**Framing (HTTP/1.x)**, checked before routing; violations get 400 (the connection closes if the body was not read):
- `Transfer-Encoding` together with `Content-Length`, more than one `Transfer-Encoding`, any coding other
  than exactly `chunked`, or `Transfer-Encoding` on HTTP/1.0.
- Several `Content-Length` values, or one that is not plain digits.
- Several `Host` headers, or none on HTTP/1.1. `CONNECT` gets 501.
- Netty's decoder already rejects malformed request lines and header syntax.
- A declared `Content-Length` above `settings.maxRequestBodyBytes` gets 413 before any upstream I/O;
  bodies without a declared length are counted while streaming and reset at the limit (413 if the
  response has not started).

**Path policy: refuse, don't normalise.** The gateway never decodes or rewrites a path, so the path it
routes on is byte-for-byte the path it forwards. It rejects (400) anything an upstream might resolve
differently: empty segments (`//`), `.` and `..` segments, backslash, `#`, non-ASCII or control
characters, malformed `%` escapes, and percent-encoded `/`, `\`, `.` or control bytes.

**Hop-by-hop headers** (RFC 9110 §7.6.1) are dropped in both directions: `Connection` and every header
it names, `Keep-Alive`, `Proxy-Connection`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`,
`Proxy-Authenticate`, `Proxy-Authorization`, `HTTP2-Settings`.

**Identity headers** listed in `settings.stripRequestHeaders` (default: `x-consumer-id`,
`x-consumer-username`, `x-tenant-id`, `x-authenticated-user`, `x-authenticated-scope`,
`x-credential-identifier`, `x-gateway-identity`) are always removed from client requests; only gateway
plugins may set them.

**Forwarding headers.** `settings.trustedProxies` (CIDRs, IP literals only) decides trust in the direct
peer:
- Untrusted peer: client-sent `X-Forwarded-For/-Proto/-Host` and `Forwarded` are discarded and
  replaced with values for the peer.
- Trusted peer: the chain is kept and the peer appended. The client IP is the rightmost address in
  `X-Forwarded-For` that is not a trusted proxy.
- `X-Request-Id` is always the gateway's own ID (in both directions), so logs and error bodies
  correlate. `Via` gets `<version> <viaName>` appended in both directions.
- `Host` upstream is the target's `host:port`, or the client's authority when the route sets
  `preserveHost`.

## Performance
All checks are single passes over strings already in memory; no regex, no decoding.

## Native Image
No impact.

## Operational impact
Some legitimate but unusual paths (encoded slashes in IDs) are refused; such APIs need a route-level
opt-in, which is not offered until someone needs it.
