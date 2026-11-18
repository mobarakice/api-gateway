package apigw.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Process-level settings read once at startup from the environment. Routing and plugin
 * configuration never come from here; they come from snapshots (etcd or the last-known-good file).
 *
 * @param bindHost         address all listeners bind to
 * @param httpPort         plaintext listener (HTTP/1.1 and h2c); 0 picks a free port
 * @param httpsPort        TLS listener (HTTP/1.1 and h2 via ALPN); only opened when {@code tls} is present
 * @param statusPort       probe listener ({@code /healthz}, {@code /readyz}); never exposed publicly
 * @param eventLoops       Vert.x event loop threads; defaults to the number of available processors
 * @param tls              PEM certificate chain and PKCS#8 key for the TLS listener
 * @param shutdownGrace    how long in-flight requests may take to finish after SIGTERM
 */
public record BootstrapConfig(
        String bindHost,
        int httpPort,
        int httpsPort,
        int statusPort,
        int eventLoops,
        Optional<TlsFiles> tls,
        Duration shutdownGrace) {

    /** Certificate chain and private key files for a TLS listener. */
    public record TlsFiles(Path certChain, Path privateKey) {
        public TlsFiles {
            requireReadable(certChain, "GW_TLS_CERT");
            requireReadable(privateKey, "GW_TLS_KEY");
        }
    }

    public BootstrapConfig {
        requirePort(httpPort, "GW_HTTP_PORT");
        requirePort(httpsPort, "GW_HTTPS_PORT");
        requirePort(statusPort, "GW_STATUS_PORT");
        if (eventLoops < 1 || eventLoops > 1024) {
            throw new IllegalArgumentException("GW_EVENT_LOOPS must be between 1 and 1024");
        }
        if (shutdownGrace.isNegative()) {
            throw new IllegalArgumentException("GW_SHUTDOWN_GRACE_SECONDS must not be negative");
        }
    }

    /** Reads settings from environment variables, applying documented defaults. */
    public static BootstrapConfig fromEnvironment(Map<String, String> env) {
        Optional<String> cert = Optional.ofNullable(env.get("GW_TLS_CERT")).filter(s -> !s.isBlank());
        Optional<String> key = Optional.ofNullable(env.get("GW_TLS_KEY")).filter(s -> !s.isBlank());
        if (cert.isPresent() != key.isPresent()) {
            throw new IllegalArgumentException("GW_TLS_CERT and GW_TLS_KEY must be set together");
        }
        Optional<TlsFiles> tls = cert.map(c -> new TlsFiles(Path.of(c), Path.of(key.orElseThrow())));
        return new BootstrapConfig(
                env.getOrDefault("GW_BIND_HOST", "0.0.0.0"),
                intVar(env, "GW_HTTP_PORT", 8080),
                intVar(env, "GW_HTTPS_PORT", 8443),
                intVar(env, "GW_STATUS_PORT", 9901),
                intVar(env, "GW_EVENT_LOOPS", Runtime.getRuntime().availableProcessors()),
                tls,
                Duration.ofSeconds(intVar(env, "GW_SHUTDOWN_GRACE_SECONDS", 20)));
    }

    private static int intVar(Map<String, String> env, String name, int fallback) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static void requirePort(int port, String name) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException(name + " must be between 0 and 65535");
        }
    }

    private static void requireReadable(Path file, String name) {
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException(name + " is not a readable file: " + file);
        }
    }
}
