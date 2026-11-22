package apigw.app;

import apigw.observability.metrics.MetricRegistry;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpConnection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/** Client connection counts per listener ({@code http}, {@code https}), summed over event loops. */
final class Connections {

    private final MetricRegistry metrics;
    private final Map<String, List<LongAdder>> active = new ConcurrentHashMap<>();

    Connections(MetricRegistry metrics) {
        this.metrics = metrics;
    }

    /** A connection handler for one listener on one event loop: counts opens and tracks open connections. */
    Handler<HttpConnection> listener(String name) {
        var opened = metrics.counter("gw_connections_total", "Client connections accepted.", "listener", name);
        LongAdder open = new LongAdder();
        active.computeIfAbsent(name, n -> {
            List<LongAdder> parts = new CopyOnWriteArrayList<>();
            metrics.gauge("gw_connections_active", "Open client connections.",
                    () -> parts.stream().mapToLong(LongAdder::sum).sum(), "listener", n);
            return parts;
        }).add(open);
        return conn -> {
            opened.inc();
            open.increment();
            conn.closeHandler(v -> open.decrement());
        };
    }
}
