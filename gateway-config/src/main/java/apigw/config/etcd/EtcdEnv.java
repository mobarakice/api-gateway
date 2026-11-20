package apigw.config.etcd;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * etcd connection settings from {@code GW_ETCD_*} variables, shared by {@code gateway check-etcd} and
 * the config worker. The password is only ever read from a file ({@code GW_ETCD_PASSWORD_FILE}),
 * never from an environment variable value.
 */
public final class EtcdEnv {

    public static final String ENDPOINTS = "GW_ETCD_ENDPOINTS";
    public static final String CA = "GW_ETCD_CA";
    public static final String USER = "GW_ETCD_USER";
    public static final String PASSWORD_FILE = "GW_ETCD_PASSWORD_FILE";
    public static final String PREFIX = "GW_ETCD_PREFIX";

    private EtcdEnv() {
    }

    public static boolean configured(Map<String, String> env) {
        return value(env, ENDPOINTS).isPresent();
    }

    public static EtcdClientConfig clientConfig(Map<String, String> env, Duration timeout) {
        List<URI> endpoints = Arrays.stream(required(env, ENDPOINTS).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(URI::create).toList();
        Optional<Path> ca = value(env, CA).map(Path::of);
        Optional<String> user = value(env, USER);
        Optional<Password> password = value(env, PASSWORD_FILE).map(f -> Password.of(readSecret(Path.of(f))));
        return new EtcdClientConfig(endpoints, ca, Optional.empty(), Optional.empty(), user, password, timeout);
    }

    /** Config prefix, normalised to end with '/'. Must start with '/'. */
    public static String prefix(Map<String, String> env) {
        String p = required(env, PREFIX);
        if (!p.startsWith("/")) {
            throw new IllegalArgumentException(PREFIX + " must start with '/'");
        }
        return p.endsWith("/") ? p : p + "/";
    }

    private static String readSecret(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read password file " + file, e);
        }
    }

    private static String required(Map<String, String> env, String var) {
        return value(env, var).orElseThrow(() -> new IllegalArgumentException(var + " is required"));
    }

    private static Optional<String> value(Map<String, String> env, String var) {
        return Optional.ofNullable(env.get(var)).filter(s -> !s.isBlank());
    }
}
