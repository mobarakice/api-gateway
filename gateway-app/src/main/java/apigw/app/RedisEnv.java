package apigw.app;

import apigw.plugins.redis.RedisClient;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Redis settings from the environment (ADR 0018). Redis is optional: without {@code GW_REDIS_URL}
 * global rate limits use their fail mode and the cache has no L2. The password is only read from a
 * file ({@code GW_REDIS_PASSWORD_FILE}); a URL with credentials in it is refused.
 */
final class RedisEnv {

    static final String URL = "GW_REDIS_URL";

    private RedisEnv() {
    }

    static Optional<RedisClient.Settings> settings(Map<String, String> env) {
        String url = value(env, URL);
        if (url == null) {
            return Optional.empty();
        }
        URI u;
        try {
            u = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(URL + " is not a URI");
        }
        boolean tls = "rediss".equals(u.getScheme());
        if (!tls && !"redis".equals(u.getScheme()) || u.getHost() == null) {
            throw new IllegalArgumentException(URL + " must be redis://host[:port][/db] or rediss://...");
        }
        if (u.getUserInfo() != null) {
            throw new IllegalArgumentException(URL + " must not contain credentials; use GW_REDIS_USER and "
                    + "GW_REDIS_PASSWORD_FILE");
        }
        int db = 0;
        String path = u.getPath();
        if (path != null && path.length() > 1) {
            try {
                db = Integer.parseInt(path.substring(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(URL + " database must be a number");
            }
        }
        String passwordFile = value(env, "GW_REDIS_PASSWORD_FILE");
        String password = null;
        if (passwordFile != null) {
            try {
                password = Files.readString(Path.of(passwordFile), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalArgumentException("GW_REDIS_PASSWORD_FILE is not readable");
            }
        }
        long timeout = number(env, "GW_REDIS_TIMEOUT_MS", 50);
        long connect = number(env, "GW_REDIS_CONNECT_TIMEOUT_MS", 2000);
        return Optional.of(new RedisClient.Settings(u.getHost(), u.getPort() < 0 ? 6379 : u.getPort(), tls,
                Optional.ofNullable(value(env, "GW_REDIS_CA")).map(Path::of), value(env, "GW_REDIS_USER"), password,
                db, Duration.ofMillis(connect), Duration.ofMillis(timeout)));
    }

    static long number(Map<String, String> env, String var, long fallback) {
        String v = value(env, var);
        if (v == null) {
            return fallback;
        }
        try {
            long n = Long.parseLong(v);
            if (n <= 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(var + " must be a positive integer");
        }
    }

    private static String value(Map<String, String> env, String var) {
        String v = env.get(var);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
