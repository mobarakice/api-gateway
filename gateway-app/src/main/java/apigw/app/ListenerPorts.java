package apigw.app;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Actual bound ports. Every traffic verticle instance reports the port its listener got; all
 * instances must share one socket per listener, so a differing port is a startup failure.
 */
final class ListenerPorts {

    private final AtomicInteger http = new AtomicInteger(-1);
    private final AtomicInteger https = new AtomicInteger(-1);
    private final AtomicInteger status = new AtomicInteger(-1);

    void http(int port) {
        record(http, port, "http");
    }

    void https(int port) {
        record(https, port, "https");
    }

    void status(int port) {
        record(status, port, "status");
    }

    private static void record(AtomicInteger slot, int port, String listener) {
        if (!slot.compareAndSet(-1, port) && slot.get() != port) {
            throw new IllegalStateException(listener + " listener instances bound different ports: "
                    + slot.get() + " and " + port);
        }
    }

    int http() {
        return http.get();
    }

    int https() {
        return https.get();
    }

    int status() {
        return status.get();
    }
}
