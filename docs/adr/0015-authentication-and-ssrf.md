# 0015 Authentication (JWT/OIDC, API keys), token revocation, and SSRF rules

- Status: Accepted (2026-11-21, M4)

## Context
The spec requires JWT validation (including OIDC discovery and JWKS), API keys, and revocation, and the
security suite requires resistance to alg confusion and SSRF (including DNS rebinding). CLAUDE.md forbids
implementing cryptographic primitives, storing plaintext credentials, logging secrets, and any etcd or
blocking work in the request path. Everything must work in the native image.

## Options
- JWT: (a) a JOSE library (nimbus-jose-jwt, jose4j, jjwt); (b) **a small verifier on JDK primitives
  (`java.security.Signature`, `javax.crypto.Mac`, `KeyFactory`)**. Libraries bring reflection-heavy JSON
  binding, header-driven key resolution (`jku`, `x5u`, `jwk`) that must be disabled, and native metadata
  to maintain. Verification is a narrow, well-specified operation (RFC 7515/7518/8037) over JDK crypto,
  which is what the libraries call anyway; no primitive is implemented here.
- API key storage: (a) bcrypt/argon2; (b) **salted SHA-256 of a 256-bit random secret, constant-time
  compare**. Slow KDFs protect low-entropy passwords; gateway-issued keys have 128+ bits of entropy, so
  a fast hash is not brute-forceable and keeps per-request cost at ~0.1 µs instead of ~50 ms.
- SSRF: (a) check targets at publish only; (b) **check literals at compile time and resolve names at
  connect time on a worker pool, require every resolved address to be allowed, then connect to the
  checked IP**.

## Decision
- **JWT plugin (`jwt`)**, priority 2100:
  - Compact JWS only, ≤ `maxTokenBytes` (8 KiB). Strict base64url, no padding. The header and claims are
    parsed by Jackson with duplicate-key detection, so duplicates are rejected.
  - `crit` → reject. `jku`/`x5u`/`jwk`/`x5c` are ignored: keys come only from configuration.
  - The issuer is selected by the unverified `iss`. Its **pinned algorithms** decide what is accepted.
    `none` cannot be configured. HS* and public-key algorithms cannot be mixed for one issuer. The key's
    family (kty/crv and JWK `alg`) must match the token's alg (no RSA key used as an HMAC secret).
  - Without `kid`, a key is used only if exactly one fits.
  - Claims: `exp` is required. `nbf` and `iat` are checked with skew (default 60 s), optional `maxAgeSeconds`.
    `aud` must intersect the configured audiences. `jti` is checked against `policies/revoked-tokens`.
  - Key sources (exactly one per issuer): inline `jwks`, `secretFile` (HS*, ≥ hash size), `jwksUri`, or
    OIDC `discovery`. Remote sets are fetched over HTTPS only, with no redirects, a 1 MiB cap, an
    optional `caFile`, and refresh every `refreshSeconds` with jitter. On an unknown `kid` there is one
    early refresh, at most every `minRefreshSeconds`. The plugin returns PENDING while that refresh
    runs and resumes on the request's event loop. Private (`d`), symmetric (`oct`), RSA < 2048 and
    `use: enc` keys in a JWKS are refused. A failed refresh keeps the previous keys.
  - Claims → principal: `sub`; roles from `rolesClaim` (dotted path, array or space-separated); tenant
    from `tenantClaim`; consumer from `consumerClaim` (`requireConsumer` optional). If the token
    tenant and the consumer tenant differ, the request gets 401.
  - Successful signature checks are cached (see Performance). Time, audience and revocation checks
    are never cached.
- **API key plugin (`key-auth`)**, priority 2050: the presented key is `<keyId>.<secret>`.
  `credentials/{consumerId}/{id}` holds `keyId` (public, globally unique) and
  `hash = sha256$<salt16+>$<digest>`, so lookup is one map probe and the compare is
  `MessageDigest.isEqual`. An unknown key ID and a wrong secret give the same 401. The header is removed
  before proxying (`hideCredentials`).
- **Revocation**: `policies/revoked-tokens/{id}` = `{jti, issuer?, expiresAt}`, at most 100,000 entries.
  Entries past `expiresAt` stop matching (the token itself has expired by then).
- **Identity upstream**: `x-consumer-id`, `x-consumer-username`, `x-tenant-id`, `x-authenticated-user`,
  `x-credential-identifier` are set only by `Exchange.authenticate`. They are always removed from
  client requests, whatever `settings.stripRequestHeaders` says. Claim values must be visible ASCII
  (≤ 1 KiB) to become a header.
- **SSRF** (`AddressPolicy`): targets in loopback, RFC 1918, CGNAT, link-local/metadata, ULA,
  multicast, broadcast, unspecified, documentation, benchmarking, IPv4-mapped/compatible and NAT64
  (by embedded IPv4) ranges are denied unless covered by `settings.upstreamAllowCidrs`.
  - Literal targets are checked by `ConfigValidator`, so the control plane and gateway reject them
    before publish.
  - Host names are resolved by `TargetResolver` with the platform resolver, on a 4-thread worker pool,
    cached 30 s (failures 5 s). **Every** returned address must be allowed. The proxy connects to the
    checked address (`RequestOptions.setServer`) and keeps the name for Host and SNI. A denial is a
    generic 502 and a warn log with route and upstream IDs.
  - The cache bounds rebinding: an answer that flips to an internal address is never connected to
    without being checked first.
  - JWKS/discovery URLs are operator configuration (HTTPS, optional CA pin). They are not subject to
    `upstreamAllowCidrs`.

## Performance
JMH `AuthBenchmark` measured on the **dev host** (macOS arm64, GraalVM JDK 25.0.4, JIT; not the
reference host). Time per verification:

| | first sight of a token | repeat token (verified-signature cache) |
|---|---|---|
| RS256 | 39.2 µs | 1.65 µs |
| ES256 | 359.6 µs | 1.27 µs |
| EdDSA | 390.8 µs | 1.29 µs |
| HS256 | 1.37 µs | 1.14 µs |
| API key | 0.13 µs | – |

The JDK's EC and EdDSA verification is pure Java and costs ~0.4 ms on the event loop. Clients reuse
tokens until expiry, so each compiled `jwt` instance keeps a `VerifiedCache`:
- the key is SHA-256(token), so tokens are never stored;
- the value is the verifying key object; a rotated key no longer matches;
- it holds 50,000 entries, is cleared when full, and only successes are inserted.

Floods of *distinct valid* ES256/EdDSA tokens still cost ~0.4 ms each. Prefer RS256 for very
high-rate clients without token reuse. Offloading verification to a worker pool is a later option if
the M10 benchmarks show it is needed.

## Memory
- `VerifiedCache` ≤ 50,000 × ~100 B ≈ 5 MB per `jwt` instance in the worst case.
- Revocation list ≤ 100,000 entries.
- DNS cache ≤ 10,000 names.

## Native Image
- JDK providers only. `SHA256withECDSAinP1363Format`, `RSASSA-PSS`, `Ed25519`, `HmacSHA*`, `KeyFactory`
  EC/RSA/Ed25519 and `SecureRandom` are verified in the native binary by `nativeTest` (the
  `SecurityPipelineTest` suite) and by `SecurityIT` under `nativeIntegrationTest`.
- No reflection was added, and no new dependency.

## Operational impact
- Upstreams on private addresses (most clusters) need `settings.upstreamAllowCidrs`, e.g. the pod/service
  CIDRs. The validator error names the field.
- The JWKS fetch failure log is rate-limited (first failure, then every 10th), and recovery is logged.
- Rejections are logged at debug only (they are attacker-driven volume) and never include the token.
- Cross-tenant attempts are logged at warn with route, request ID, method and credential ID.
