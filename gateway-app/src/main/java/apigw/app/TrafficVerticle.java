package apigw.app;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.PemKeyCertOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One instance per event loop. Opens the plaintext listener (HTTP/1.1 + h2c, both upgrade and prior
 * knowledge) and, when TLS files are configured, the TLS listener (HTTP/1.1 + h2 via ALPN).
 * Instances share listening sockets; the kernel accept queue is spread across event loops by Vert.x.
 */
final class TrafficVerticle extends VerticleBase {

    private final BootstrapConfig config;
    private final ListenerPorts ports;
    private final List<HttpServer> servers = new ArrayList<>(2);

    TrafficVerticle(BootstrapConfig config, ListenerPorts ports) {
        this.config = config;
        this.ports = ports;
    }

    @Override
    public Future<?> start() {
        TrafficHandler handler = new TrafficHandler();
        HttpServer plain = vertx.createHttpServer(baseOptions(config.httpPort())
                .setHttp2ClearTextEnabled(true))
                .requestHandler(handler);
        servers.add(plain);
        Future<HttpServer> plainListening = plain.listen().map(s -> {
            ports.http(s.actualPort());
            return s;
        });
        if (config.tls().isEmpty()) {
            return plainListening;
        }
        BootstrapConfig.TlsFiles tls = config.tls().get();
        HttpServer secure = vertx.createHttpServer(baseOptions(config.httpsPort())
                .setSsl(true)
                .setUseAlpn(true)
                .setAlpnVersions(List.of(HttpVersion.HTTP_2, HttpVersion.HTTP_1_1))
                .setKeyCertOptions(new PemKeyCertOptions()
                        .setCertPath(tls.certChain().toString())
                        .setKeyPath(tls.privateKey().toString())))
                .requestHandler(handler);
        servers.add(secure);
        Future<HttpServer> secureListening = secure.listen().map(s -> {
            ports.https(s.actualPort());
            return s;
        });
        return Future.all(plainListening, secureListening);
    }

    @Override
    public Future<?> stop() {
        Duration grace = config.shutdownGrace();
        List<Future<Void>> closing = new ArrayList<>(servers.size());
        for (HttpServer server : servers) {
            // Stops accepting, lets in-flight requests finish up to the grace period, then closes.
            closing.add(server.shutdown(grace));
        }
        return Future.all(closing);
    }

    private HttpServerOptions baseOptions(int port) {
        return new HttpServerOptions()
                .setHost(config.bindHost())
                .setPort(port)
                .setReuseAddress(true)
                .setTcpNoDelay(true)
                .setIdleTimeout(60)
                .setMaxInitialLineLength(8192)
                .setMaxHeaderSize(16_384);
    }
}
