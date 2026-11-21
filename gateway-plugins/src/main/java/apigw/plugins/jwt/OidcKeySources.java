package apigw.plugins.jwt;

import apigw.core.plugin.PluginServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemTrustOptions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote key sets for the {@code jwt} plugin: OIDC discovery ({@code <issuer>/.well-known/openid-configuration})
 * or a direct {@code jwks_uri}, fetched over HTTPS only, cached, refreshed periodically, and refreshed
 * early when a token names an unknown {@code kid} — at most once per {@code minRefreshInterval} per
 * source, so forged kids cannot turn the gateway into a request amplifier against the IdP.
 *
 * <p>All fetching runs on one dedicated Vert.x context; requests only read a volatile {@link KeySet}.
 * A failed refresh keeps the previous keys. Sources are shared by every snapshot that uses the same
 * spec, and closed once the published snapshot no longer uses them.
 */
public final class OidcKeySources implements PluginServices.SnapshotAware {

    private static final Logger LOG = LoggerFactory.getLogger(OidcKeySources.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    private final Vertx vertx;
    private final Context context;
    private final Duration timeout;
    private final Map<Spec, Source> sources = new ConcurrentHashMap<>();
    private final boolean fetch;

    /**
     * What to fetch.
     *
     * @param issuer   set for discovery: the document's {@code issuer} must equal it exactly
     * @param jwksUri  set for a direct JWKS URL (no discovery)
     * @param caFile   PEM trust anchors for the IdP; empty = the platform trust store
     */
    public record Spec(String issuer, URI jwksUri, Optional<Path> caFile, Duration refresh,
            Duration minRefreshInterval) {
    }

    public OidcKeySources(Vertx vertx, Duration timeout) {
        this(vertx, timeout, true);
    }

    private OidcKeySources(Vertx vertx, Duration timeout, boolean fetch) {
        this.vertx = vertx;
        this.context = vertx.getOrCreateContext();
        this.timeout = timeout;
        this.fetch = fetch;
    }

    /**
     * For config validation only (the control plane's dry run): sources are created but never fetch,
     * so validating a change never makes the validating process call an identity provider.
     */
    public static OidcKeySources validationOnly(Vertx vertx) {
        return new OidcKeySources(vertx, Duration.ofSeconds(1), false);
    }

    /** The shared source for {@code spec}, starting its first fetch if new. Safe from any thread. */
    public Source source(Spec spec) {
        return sources.computeIfAbsent(spec, s -> {
            Source src = new Source(s);
            if (fetch) {
                context.runOnContext(v -> src.fetch());
            }
            return src;
        });
    }

    @Override
    public void retainOnly(Set<Object> inUse) {
        sources.values().removeIf(s -> {
            if (!inUse.contains(s)) {
                s.close();
                return true;
            }
            return false;
        });
    }

    /** One remote key set. */
    public final class Source {

        private final Spec spec;
        private final HttpClient http;
        private volatile KeySet keys = KeySet.EMPTY;
        private volatile Future<Boolean> inFlight;
        private long lastFetchNanos;
        private long timer = -1;
        private int failures;
        private volatile boolean closed;

        Source(Spec spec) {
            this.spec = spec;
            HttpClientOptions o = new HttpClientOptions().setSsl(true).setVerifyHost(true)
                    .setProtocolVersion(HttpVersion.HTTP_1_1).setConnectTimeout((int) timeout.toMillis())
                    .setIdleTimeout((int) Math.max(1, timeout.toSeconds())).setKeepAlive(false);
            spec.caFile().ifPresent(ca -> o.setTrustOptions(new PemTrustOptions().addCertPath(ca.toString())));
            this.http = vertx.createHttpClient(o);
        }

        public KeySet keys() {
            return keys;
        }

        /** Completes when the first fetch attempt is done (true if it produced keys). */
        public Future<Boolean> ready() {
            Future<Boolean> f = inFlight;
            return f != null ? f : Future.succeededFuture(!keys.all().isEmpty());
        }

        /**
         * Refreshes now unless the last fetch started less than {@code minRefreshInterval} ago. Completes
         * with true if a fetch ran and changed nothing fatal; false if rate-limited or failed.
         */
        public Future<Boolean> refreshForUnknownKey() {
            Promise<Boolean> p = Promise.promise();
            context.runOnContext(v -> {
                Future<Boolean> running = inFlight;
                if (running != null) {
                    running.onComplete(p);
                    return;
                }
                if (System.nanoTime() - lastFetchNanos < spec.minRefreshInterval().toNanos()) {
                    p.complete(false);
                    return;
                }
                fetch().onComplete(p);
            });
            return p.future();
        }

        /** Runs on {@link #context}. */
        private Future<Boolean> fetch() {
            if (closed) {
                return Future.succeededFuture(false);
            }
            if (inFlight != null) {
                return inFlight;
            }
            lastFetchNanos = System.nanoTime();
            Future<URI> jwksUri = spec.jwksUri() != null ? Future.succeededFuture(spec.jwksUri()) : discover();
            Future<Boolean> f = jwksUri.compose(this::get).map(doc -> {
                Jwks.Parsed parsed = Jwks.parse(doc);
                if (parsed.keys().all().isEmpty()) {
                    throw new IllegalStateException("JWKS has no usable signing keys " + parsed.problems());
                }
                if (!parsed.problems().isEmpty()) {
                    LOG.warn("JWKS keys skipped source={} problems={}", describe(), parsed.problems());
                }
                keys = parsed.keys();
                if (failures > 0) {
                    LOG.info("JWKS fetch recovered source={} keys={}", describe(), parsed.keys().all().size());
                }
                failures = 0;
                return true;
            }).recover(e -> {
                failures++;
                if (failures == 1 || failures % 10 == 0) {
                    LOG.warn("JWKS fetch failed, keeping previous keys source={} failures={} cause={}", describe(),
                            failures, e.getMessage());
                }
                return Future.succeededFuture(false);
            }).onComplete(ar -> {
                inFlight = null;
                schedule();
            });
            inFlight = f;
            return f;
        }

        private void schedule() {
            if (closed) {
                return;
            }
            if (timer >= 0) {
                vertx.cancelTimer(timer);
            }
            long base = failures > 0 ? Math.min(spec.refresh().toMillis(), 5_000L << Math.min(failures, 6))
                    : spec.refresh().toMillis();
            long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, base / 10));
            timer = vertx.setTimer(base + jitter, id -> {
                timer = -1;
                fetch();
            });
        }

        private Future<URI> discover() {
            String base = spec.issuer().endsWith("/") ? spec.issuer().substring(0, spec.issuer().length() - 1)
                    : spec.issuer();
            return get(URI.create(base + "/.well-known/openid-configuration")).map(doc -> {
                if (!spec.issuer().equals(doc.path("issuer").asText(null))) {
                    throw new IllegalStateException("discovery document issuer does not match the configured issuer");
                }
                String uri = doc.path("jwks_uri").asText("");
                URI u = URI.create(uri);
                if (!"https".equals(u.getScheme())) {
                    throw new IllegalStateException("jwks_uri must be https");
                }
                return u;
            });
        }

        private Future<JsonNode> get(URI uri) {
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path = path + "?" + uri.getRawQuery();
            }
            RequestOptions ro = new RequestOptions().setMethod(HttpMethod.GET).setHost(uri.getHost()).setPort(port)
                    .setURI(path).setTimeout(timeout.toMillis()).setFollowRedirects(false)
                    .putHeader("accept", "application/json");
            return http.request(ro).compose(req -> req.send()).compose(resp -> {
                if (resp.statusCode() != 200) {
                    resp.end();
                    return Future.failedFuture(new IllegalStateException("HTTP " + resp.statusCode() + " from "
                            + uri.getHost()));
                }
                String len = resp.getHeader("content-length");
                if (len != null && parseLong(len) > MAX_DOCUMENT_BYTES) {
                    return Future.failedFuture(new IllegalStateException("document too large"));
                }
                return resp.body();
            }).map((Buffer b) -> {
                if (b.length() > MAX_DOCUMENT_BYTES) {
                    throw new IllegalStateException("document too large");
                }
                try {
                    return MAPPER.readTree(b.getBytes());
                } catch (IOException e) {
                    throw new IllegalStateException("not JSON", e);
                }
            });
        }

        private String describe() {
            return spec.jwksUri() != null ? spec.jwksUri().getHost() + spec.jwksUri().getPath() : spec.issuer();
        }

        void close() {
            closed = true;
            context.runOnContext(v -> {
                if (timer >= 0) {
                    vertx.cancelTimer(timer);
                    timer = -1;
                }
                http.close();
            });
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(spec);
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
