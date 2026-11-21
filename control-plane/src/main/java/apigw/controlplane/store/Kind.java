package apigw.controlplane.store;

import apigw.controlplane.auth.Role;
import java.util.Optional;

/**
 * Admin API resource collections and where they live in etcd (docs/architecture/etcd-keys.md). Keys
 * are relative to the environment prefix. {@link #CREDENTIALS} is nested under a consumer and
 * {@link #SETTINGS} is a singleton.
 */
public enum Kind {
    ROUTES("routes", "routes/", "Route", Role.EDITOR),
    SERVICES("services", "services/", "Service", Role.EDITOR),
    UPSTREAMS("upstreams", "upstreams/", "Upstream", Role.EDITOR),
    CONSUMERS("consumers", "consumers/", "Consumer", Role.EDITOR),
    PLUGIN_CONFIGS("plugin-configs", "plugin-configs/", "PluginConfig", Role.EDITOR),
    GLOBAL_PLUGINS("global-plugins", "global-plugins/", "GlobalPlugin", Role.EDITOR),
    AUTHZ_POLICIES("policies/authz", "policies/authz/", "AuthzPolicy", Role.EDITOR),
    REVOKED_TOKENS("policies/revoked-tokens", "policies/revoked-tokens/", "RevokedToken", Role.EDITOR),
    CACHE_PURGES("policies/cache-purge", "policies/cache-purge/", "CachePurge", Role.EDITOR),
    CERTIFICATES("certificates", "certificates/", "Certificate", Role.ADMIN),
    CREDENTIALS("credentials", "credentials/", "Credential", Role.EDITOR),
    SETTINGS("settings", "meta/settings", "Settings", Role.ADMIN);

    /** Collections addressed as {@code /admin/v1/{path}/{id}}. */
    static final Kind[] FLAT = {ROUTES, SERVICES, UPSTREAMS, CONSUMERS, PLUGIN_CONFIGS, GLOBAL_PLUGINS,
        AUTHZ_POLICIES, REVOKED_TOKENS, CACHE_PURGES, CERTIFICATES};

    private final String apiPath;
    private final String keyPrefix;
    private final String kind;
    private final Role writeRole;

    Kind(String apiPath, String keyPrefix, String kind, Role writeRole) {
        this.apiPath = apiPath;
        this.keyPrefix = keyPrefix;
        this.kind = kind;
        this.writeRole = writeRole;
    }

    public String apiPath() {
        return apiPath;
    }

    /** Relative key prefix ({@code routes/}); for {@link #SETTINGS} the whole key. */
    public String keyPrefix() {
        return keyPrefix;
    }

    /** Envelope {@code kind}. */
    public String kind() {
        return kind;
    }

    /** Lowest role allowed to stage changes to this collection. */
    public Role writeRole() {
        return writeRole;
    }

    /** Relative key of object {@code id} (not for credentials or settings). */
    public String key(String id) {
        return keyPrefix + id;
    }

    public static String credentialKey(String consumerId, String id) {
        return CREDENTIALS.keyPrefix + consumerId + "/" + id;
    }

    public static Optional<Kind> byApiPath(String path) {
        for (Kind k : FLAT) {
            if (k.apiPath.equals(path)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    /** The collection a relative key belongs to, if any. */
    public static Optional<Kind> ofKey(String rel) {
        if (SETTINGS.keyPrefix.equals(rel)) {
            return Optional.of(SETTINGS);
        }
        if (rel.startsWith(CREDENTIALS.keyPrefix)) {
            return Optional.of(CREDENTIALS);
        }
        for (Kind k : FLAT) {
            if (rel.startsWith(k.keyPrefix) && rel.indexOf('/', k.keyPrefix.length()) < 0
                    && rel.length() > k.keyPrefix.length()) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }
}
