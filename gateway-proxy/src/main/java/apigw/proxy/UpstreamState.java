package apigw.proxy;

import apigw.core.snapshot.CompiledUpstream;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.net.PemTrustOptions;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** One upstream definition on one event loop: its HTTP client (one pool per target), balancer and counters. */
final class UpstreamState {

    final CompiledUpstream definition;
    final HttpClientAgent client;
    final Balancer balancer;
    final int[] inflight;

    UpstreamState(Vertx vertx, CompiledUpstream definition) {
        this.definition = definition;
        this.balancer = Balancer.of(definition);
        this.inflight = new int[definition.targets().size()];
        HttpClientOptions o = new HttpClientOptions()
                .setTcpNoDelay(true)
                .setKeepAlive(true)
                .setKeepAliveTimeout(definition.pool().keepAliveSeconds())
                .setHttp2KeepAliveTimeout(definition.pool().keepAliveSeconds())
                .setMaxInitialLineLength(8192)
                .setMaxHeaderSize(16_384)
                .setDecompressionSupported(false)
                .setMaxRedirects(0);
        if (definition.scheme() == apigw.model.Upstream.Scheme.HTTPS) {
            o.setSsl(true).setVerifyHost(true).setForceSni(true);
            definition.caFile().ifPresent(ca -> o.setTrustOptions(new PemTrustOptions().addCertPath(ca.toString())));
            if (definition.http2()) {
                o.setUseAlpn(true).setProtocolVersion(HttpVersion.HTTP_2)
                        .setAlpnVersions(List.of(HttpVersion.HTTP_2, HttpVersion.HTTP_1_1));
            }
        } else if (definition.http2()) {
            o.setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false);
        }
        PoolOptions pool = new PoolOptions()
                .setHttp1MaxSize(definition.pool().maxConnections())
                .setHttp2MaxSize(definition.pool().maxConnections())
                .setMaxWaitQueueSize(definition.pool().maxPending());
        this.client = vertx.createHttpClient(o, pool);
    }

    /** Lets in-flight requests on this pool finish (up to the grace), then closes its connections. */
    void retire() {
        client.shutdown(30, TimeUnit.SECONDS);
    }
}
