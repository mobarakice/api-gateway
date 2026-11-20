package apigw.core.snapshot;

import apigw.model.Upstream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * An upstream ready for the proxy. {@link #definitionKey()} identifies the connection-relevant
 * definition: pools are kept across snapshot swaps while it is unchanged.
 */
public record CompiledUpstream(
        String id,
        Upstream.Scheme scheme,
        Optional<Path> caFile,
        List<Target> targets,
        Upstream.Balancer balancer,
        String hashHeader,
        Upstream.Pool pool,
        boolean http2,
        String definitionKey) {

    /**
     * One backend.
     *
     * @param authority precomputed {@code host:port} for the upstream Host header
     * @param literal true when {@code host} is an IP literal: checked against the SSRF rules at compile time
     *                instead of being resolved (and re-checked) per connection
     */
    public record Target(int index, String host, int port, int weight, String authority, boolean literal) {

        public Target(int index, String host, int port, int weight, String authority) {
            this(index, host, port, weight, authority, isLiteral(host));
        }

        private static boolean isLiteral(String host) {
            try {
                apigw.model.Cidr.literal(host);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    public CompiledUpstream {
        targets = List.copyOf(targets);
    }

    /** True when the consistent hash key is the client IP. */
    public boolean hashByIp() {
        return balancer == Upstream.Balancer.CONSISTENT_HASH && hashHeader == null;
    }
}
