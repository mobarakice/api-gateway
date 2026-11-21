package apigw.plugins;

import apigw.core.plugin.PluginRegistry;
import apigw.plugins.authz.AuthzPluginFactory;
import apigw.plugins.cache.CachePluginFactory;
import apigw.plugins.ratelimit.RateLimitPluginFactory;
import apigw.plugins.cors.CorsPluginFactory;
import apigw.plugins.ip.IpRestrictionPluginFactory;
import apigw.plugins.size.RequestSizeLimitPluginFactory;
import apigw.plugins.transform.RequestTransformPluginFactory;
import apigw.plugins.headers.HeadersPluginFactory;
import apigw.plugins.jwt.JwtPluginFactory;
import apigw.plugins.keyauth.KeyAuthPluginFactory;
import java.util.List;

/** Every compiled-in plugin. Adding a plugin = adding its factory here; there is no discovery. */
public final class BuiltinPlugins {

    private BuiltinPlugins() {
    }

    public static PluginRegistry registry() {
        return new PluginRegistry(List.of(new HeadersPluginFactory(), new JwtPluginFactory(),
                new KeyAuthPluginFactory(), new AuthzPluginFactory(), new CorsPluginFactory(),
                new IpRestrictionPluginFactory(), new RequestSizeLimitPluginFactory(),
                new RequestTransformPluginFactory(), new RateLimitPluginFactory(), new CachePluginFactory()));
    }
}
