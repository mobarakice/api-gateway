package apigw.app;

import apigw.observability.metrics.MetricRegistry;
import apigw.proxy.HttpForwarder;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Per-upstream gauges summed over event loops: in-flight requests and loops whose breaker is not closed. */
final class UpstreamGauges {

    private UpstreamGauges() {
    }

    static void register(MetricRegistry metrics, Upstreams upstreams) {
        metrics.gaugeFamily("gw_upstream_inflight", "Requests in flight to the upstream.", List.of("upstream"),
                sink -> collect(upstreams, false).forEach((id, v) -> sink.sample(v, id)));
        metrics.gaugeFamily("gw_upstream_breakers_open",
                "Event loops whose circuit breaker for the upstream is open or half-open (0 = closed everywhere).",
                List.of("upstream"), sink -> collect(upstreams, true).forEach((id, v) -> sink.sample(v, id)));
    }

    private static Map<String, Integer> collect(Upstreams upstreams, boolean breakers) {
        Map<String, Integer> out = new TreeMap<>();
        for (HttpForwarder f : upstreams.forwarders()) {
            f.sample((id, inflight, open) -> out.merge(id, breakers ? (open ? 1 : 0) : inflight, Integer::sum));
        }
        return out;
    }
}
