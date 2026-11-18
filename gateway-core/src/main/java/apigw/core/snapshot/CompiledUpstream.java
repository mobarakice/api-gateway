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
     */
    public record Target(int index, String host, int port, int weight, String authority) {
    }

    public CompiledUpstream {
        targets = List.copyOf(targets);
    }

    /** True when the consistent hash key is the client IP. */
    public boolean hashByIp() {
        return balancer == Upstream.Balancer.CONSISTENT_HASH && hashHeader == null;
    }
}
