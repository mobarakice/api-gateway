# Threat model (data plane, M4)

Scope: the gateway process. The control plane and console come in M6/M7.

| Threat | Mitigation | Test |
|---|---|---|
| Forged JWT: `alg: none`, HS/RS confusion, wrong key family, header-supplied keys (`jku`, `jwk`) | Algorithms pinned per issuer; `none` not configurable; HS* never mixed with public-key algs; key family must match alg; header key locations ignored | `JwtVerifierTest`, `SecurityPipelineTest`, `SecurityIT` |
| Expired, not-yet-valid, wrong-issuer, wrong-audience, replayed-after-revoke tokens | `exp` required, `nbf`/`iat`/max age with skew, exact `iss`, `aud` intersection, `jti` deny-list | same |
| Ambiguous token (duplicate claims, padding tricks, `crit`) | Strict base64url, duplicate-key detection, `crit` rejected | `JwtVerifierTest` |
| IdP amplification via random `kid` | Unknown-kid refresh at most every `minRefreshSeconds` per source | `SecurityPipelineTest.oidc…` |
| Malicious JWKS (private or symmetric keys, weak RSA) | Refused per key; HTTPS only, no redirects, 1 MiB cap, optional CA pin | `JwtVerifierTest`, factory tests |
| API key theft from config/etcd | Only salted SHA-256 hashes stored; secrets ≥ 22 chars of 256-bit randomness | `SecurityConfigTest` |
| API key enumeration or timing | One map probe by public key ID, constant-time compare, identical 401 for unknown ID and wrong secret | `SecurityPipelineTest` |
| Identity spoofing (`x-consumer-id` and friends) | Always stripped inbound; set only by authentication | `SecurityPipelineTest`, `SecurityIT` |
| Header injection via claims | Only visible ASCII ≤ 1 KiB becomes an identity header | `ExchangeImpl.safeHeaderValue` |
| Header injection via obs-fold | Unfolded into the previous value by the parser (RFC 9112 §5.2); never a separate header | `SecurityIT` |
| Cross-tenant access | Validator forbids cross-tenant references; tenant routes require auth; principal tenant must equal route tenant; tenant policies never match other tenants | `SecurityConfigTest`, `SecurityPipelineTest`, `SecurityIT` |
| Privilege escalation through authz | Deny overrides allow; default deny; policies compiled from validated config only | `SecurityPipelineTest` |
| Request smuggling (CL+TE, duplicate CL, TE variants, duplicate Host) | Rejected with 400 before routing | `HttpForwarderTest`, `SecurityIT` |
| Oversized requests and headers | 8 KiB request line, 16 KiB headers (431), body cap (413) | `SecurityIT`, `HttpForwarderTest` |
| SSRF to metadata or internal ranges, incl. IPv4-mapped/NAT64 forms | `AddressPolicy` deny list unless `upstreamAllowCidrs`; literals rejected at validation | `SecurityConfigTest`, `SsrfTest`, `SecurityIT` |
| DNS rebinding | Name resolved off-loop; all answers must pass; connection pinned to the checked IP; Host/SNI unchanged | `SsrfTest` |
| Secrets in logs or responses | No token, key, secret or body is logged; rejections at debug without values; generic problem bodies | `SecurityIT` (scans process output), `SecurityPipelineTest` |
| Gateway writes to config in etcd | Read-only role on config prefixes, writable `nodes/` only | `EtcdClientIT`, `EtcdCheckIT` |
| Admin API privilege escalation | Not in the data plane | M6 |

Residual risks:
- Distinct-token floods with ES256/EdDSA cost ~0.4 ms of event-loop time each (ADR 0015).
- DNS answers are cached for 30 s, not per record TTL.
- Consumer overrides can only modify plugins present in the route chain (ADR 0016).
