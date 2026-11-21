package apigw.model;

import java.time.Instant;

/**
 * A cache invalidation ({@code policies/cache-purge/{id}}): every cached response of {@code routeId},
 * or every cached response tagged {@code tag}, stored before {@code issuedAt} is treated as a miss.
 * Exactly one of {@code routeId} and {@code tag} is set. Gateways apply it lazily on lookup, so a purge
 * costs one map lookup per cache hit and nothing else (ADR 0019).
 */
public record CachePurge(String id, String routeId, String tag, Instant issuedAt) {
}
