package apigw.controlplane;

import apigw.config.etcd.EtcdClientConfig;
import apigw.config.etcd.Password;
import apigw.controlplane.auth.AdminAuth;
import apigw.controlplane.changes.Limits;
import apigw.model.Cidr;
import apigw.plugins.jwt.JwtAlgorithm;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Control-plane settings from {@code CP_*} environment variables (docs/operations/control-plane.md).
 * Secrets come from files, never from variable values.
 */
public record CpConfig(String bindHost, int port, Optional<Path> tlsCert, Optional<Path> tlsKey,
        EtcdClientConfig etcd, String prefix, AdminAuth.Settings oidc, Optional<Path> jwksFile,
        Optional<URI> jwksUri, boolean discovery, Optional<Path> oidcCa, List<Cidr> trustedProxies,
        Optional<Path> auditFile, int auditWindow, Limits limits) {

    public static CpConfig fromEnvironment(Map<String, String> env) {
        String host = opt(env, "CP_BIND_HOST").orElse("0.0.0.0");
        int port = integer(env, "CP_HTTP_PORT", 9080, 1, 65535);
        Optional<Path> cert = opt(env, "CP_TLS_CERT").map(Path::of);
        Optional<Path> key = opt(env, "CP_TLS_KEY").map(Path::of);
        if (cert.isPresent() != key.isPresent()) {
            throw new IllegalArgumentException("set both CP_TLS_CERT and CP_TLS_KEY, or neither");
        }
        List<URI> endpoints = Arrays.stream(required(env, "CP_ETCD_ENDPOINTS").split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).map(URI::create).toList();
        Optional<String> user = opt(env, "CP_ETCD_USER");
        Optional<Password> password = opt(env, "CP_ETCD_PASSWORD_FILE").map(f -> Password.of(secret(Path.of(f))));
        EtcdClientConfig etcd = new EtcdClientConfig(endpoints, opt(env, "CP_ETCD_CA").map(Path::of), Optional.empty(),
                Optional.empty(), user, password, Duration.ofMillis(integer(env, "CP_ETCD_TIMEOUT_MS", 5000, 100,
                        60_000)));
        String prefix = required(env, "CP_ETCD_PREFIX");
        if (!prefix.startsWith("/")) {
            throw new IllegalArgumentException("CP_ETCD_PREFIX must start with '/'");
        }
        prefix = prefix.endsWith("/") ? prefix : prefix + "/";
        Set<JwtAlgorithm> algs = EnumSet.noneOf(JwtAlgorithm.class);
        for (String a : opt(env, "CP_OIDC_ALGORITHMS").orElse("RS256,ES256").split(",")) {
            JwtAlgorithm alg = JwtAlgorithm.fromJose(a.strip())
                    .orElseThrow(() -> new IllegalArgumentException("unknown CP_OIDC_ALGORITHMS entry: " + a));
            if (alg.family() == JwtAlgorithm.Family.HMAC) {
                throw new IllegalArgumentException("CP_OIDC_ALGORITHMS: HMAC algorithms are not allowed for admin "
                        + "tokens (shared secrets); use an asymmetric algorithm");
            }
            algs.add(alg);
        }
        Set<String> aud = java.util.Arrays.stream(required(env, "CP_OIDC_AUDIENCE").split(",")).map(String::strip)
                .filter(a -> !a.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (aud.isEmpty()) {
            throw new IllegalArgumentException("CP_OIDC_AUDIENCE must name at least one audience");
        }
        AdminAuth.Settings oidc = new AdminAuth.Settings(required(env, "CP_OIDC_ISSUER"),
                aud.stream().map(String::strip).collect(java.util.stream.Collectors.toSet()), algs,
                opt(env, "CP_OIDC_ROLES_CLAIM").orElse("roles"), opt(env, "CP_OIDC_ROLE_PREFIX").orElse(""),
                integer(env, "CP_OIDC_SKEW_SECONDS", 60, 0, 600));
        Optional<Path> jwksFile = opt(env, "CP_OIDC_JWKS_FILE").map(Path::of);
        Optional<URI> jwksUri = opt(env, "CP_OIDC_JWKS_URI").map(URI::create);
        boolean discovery = Boolean.parseBoolean(opt(env, "CP_OIDC_DISCOVERY").orElse("false"));
        int sources = (jwksFile.isPresent() ? 1 : 0) + (jwksUri.isPresent() ? 1 : 0) + (discovery ? 1 : 0);
        if (sources != 1) {
            throw new IllegalArgumentException("set exactly one of CP_OIDC_JWKS_FILE, CP_OIDC_JWKS_URI, "
                    + "CP_OIDC_DISCOVERY=true");
        }
        jwksUri.ifPresent(u -> {
            if (!"https".equals(u.getScheme())) {
                throw new IllegalArgumentException("CP_OIDC_JWKS_URI must be https");
            }
        });
        if (discovery && !oidc.issuer().startsWith("https://")) {
            throw new IllegalArgumentException("CP_OIDC_DISCOVERY needs an https CP_OIDC_ISSUER");
        }
        List<Cidr> proxies = new ArrayList<>();
        for (String c : opt(env, "CP_TRUSTED_PROXIES").orElse("").split(",")) {
            if (!c.isBlank()) {
                proxies.add(Cidr.parse(c.strip()));
            }
        }
        Limits limits = new Limits(integer(env, "CP_ETCD_MAX_TXN_OPS", 128, 8, 100_000),
                integer(env, "CP_MAX_TXN_BYTES", 1_000_000, 4096, 64 << 20),
                integer(env, "CP_MAX_VALUE_BYTES", 256 * 1024, 1024, 16 << 20),
                integer(env, "CP_HISTORY_LIMIT", 100, 1, 10_000));
        return new CpConfig(host, port, cert, key, etcd, prefix, oidc, jwksFile, jwksUri, discovery,
                opt(env, "CP_OIDC_CA").map(Path::of), List.copyOf(proxies), opt(env, "CP_AUDIT_FILE").map(Path::of),
                integer(env, "CP_AUDIT_WINDOW", 1000, 10, 100_000), limits);
    }

    private static Optional<String> opt(Map<String, String> env, String name) {
        return Optional.ofNullable(env.get(name)).map(String::strip).filter(s -> !s.isEmpty());
    }

    private static String required(Map<String, String> env, String name) {
        return opt(env, name).orElseThrow(() -> new IllegalArgumentException(name + " is required"));
    }

    private static int integer(Map<String, String> env, String name, int def, int min, int max) {
        Optional<String> v = opt(env, name);
        if (v.isEmpty()) {
            return def;
        }
        try {
            int i = Integer.parseInt(v.get());
            if (i < min || i > max) {
                throw new NumberFormatException();
            }
            return i;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer in " + min + ".." + max);
        }
    }

    private static String secret(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read secret file " + file, e);
        }
    }
}
