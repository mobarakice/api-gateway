package apigw.core.pipeline;

/**
 * Sends an admitted request upstream and streams the response back. One instance per event loop;
 * implemented in {@code gateway-proxy}.
 */
public interface Forwarder {

    void forward(ProxyExchange exchange);
}
