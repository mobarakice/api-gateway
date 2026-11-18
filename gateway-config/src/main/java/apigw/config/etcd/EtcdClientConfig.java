package apigw.config.etcd;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Connection settings for the etcd v3 HTTP/JSON gateway.
 *
 * @param endpoints      one or more {@code https://host:port} (or {@code http://} for local dev) endpoints
 * @param caFile         PEM CA bundle used to verify the etcd server certificate
 * @param clientCert     optional PEM client certificate (mTLS)
 * @param clientKey      optional PKCS#8 PEM client key (mTLS)
 * @param username       etcd user; empty when etcd auth is disabled
 * @param password       etcd password; required when {@code username} is present
 * @param requestTimeout upper bound for a unary request, including connect
 */
public record EtcdClientConfig(
        List<URI> endpoints,
        Optional<Path> caFile,
        Optional<Path> clientCert,
        Optional<Path> clientKey,
        Optional<String> username,
        Optional<Password> password,
        Duration requestTimeout) {

    public EtcdClientConfig {
        endpoints = List.copyOf(endpoints);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one etcd endpoint is required");
        }
        for (URI e : endpoints) {
            if (!"https".equals(e.getScheme()) && !"http".equals(e.getScheme())) {
                throw new IllegalArgumentException("etcd endpoint must be http(s)://host:port: " + e);
            }
            if (e.getHost() == null || e.getPort() < 0) {
                throw new IllegalArgumentException("etcd endpoint needs an explicit host and port: " + e);
            }
        }
        if (clientCert.isPresent() != clientKey.isPresent()) {
            throw new IllegalArgumentException("etcd client certificate and key must be set together");
        }
        if (username.isPresent() != password.isPresent()) {
            throw new IllegalArgumentException("etcd username and password must be set together");
        }
        caFile.ifPresent(EtcdClientConfig::requireReadable);
        clientCert.ifPresent(EtcdClientConfig::requireReadable);
        clientKey.ifPresent(EtcdClientConfig::requireReadable);
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("etcd request timeout must be positive");
        }
    }

    private static void requireReadable(Path p) {
        if (!Files.isReadable(p)) {
            throw new IllegalArgumentException("not a readable file: " + p);
        }
    }
}
