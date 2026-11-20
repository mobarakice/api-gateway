/**
 * The {@code jwt} plugin: JWS compact tokens verified with JDK primitives only (no JOSE library),
 * algorithms pinned per issuer, keys from inline JWKS, a secret file (HMAC) or OIDC discovery/JWKS
 * fetched and refreshed off the hot path. See ADR 0015.
 */
package apigw.plugins.jwt;
