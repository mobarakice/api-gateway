package apigw.proxy;

import apigw.core.snapshot.CompiledUpstream;
import apigw.model.Upstream;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import java.util.List;

/** HTTP client options for an upstream, shared by traffic pools and health probes so both speak alike. */
final class UpstreamClients {

    private UpstreamClients() {
    }

    static HttpClientOptions options(CompiledUpstream definition) {
        HttpClientOptions o = new HttpClientOptions()
                .setTcpNoDelay(true)
                .setKeepAlive(true)
                .setKeepAliveTimeout(definition.pool().keepAliveSeconds())
                .setHttp2KeepAliveTimeout(definition.pool().keepAliveSeconds())
                .setMaxInitialLineLength(8192)
                .setMaxHeaderSize(16_384)
                .setDecompressionSupported(false)
                .setMaxRedirects(0);
        if (definition.scheme() == Upstream.Scheme.HTTPS) {
            o.setSsl(true).setVerifyHost(true).setForceSni(true);
            definition.caFile().ifPresent(ca -> o.setTrustOptions(new PemTrustOptions().addCertPath(ca.toString())));
            if (definition.clientCert() != null) {
                // mTLS to the targets: the key stays in its mounted file; never part of the config.
                o.setKeyCertOptions(new PemKeyCertOptions()
                        .setCertPath(definition.clientCert().certFile().toString())
                        .setKeyPath(definition.clientCert().keyFile().toString()));
            }
            if (definition.http2()) {
                o.setUseAlpn(true).setProtocolVersion(HttpVersion.HTTP_2)
                        .setAlpnVersions(List.of(HttpVersion.HTTP_2, HttpVersion.HTTP_1_1));
            }
        } else if (definition.http2()) {
            o.setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false);
        }
        return o;
    }
}
