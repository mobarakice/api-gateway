/**
 * Request routing: host table + per-host segment radix trie. Lookups do not allocate (segments are
 * compared in place with {@code regionMatches}; parameters are recorded as offsets in a caller-owned
 * {@link apigw.core.routing.RouteMatch}). See ADR 0012.
 */
package apigw.core.routing;
