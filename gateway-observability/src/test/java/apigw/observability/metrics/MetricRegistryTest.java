package apigw.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MetricRegistryTest {

    @Test
    void countersAreSharedPerLabelSetAndRendered() {
        MetricRegistry r = new MetricRegistry();
        Counter a = r.counter("gw_requests_total", "Requests.", "route", "a", "code", "2xx");
        assertThat(r.counter("gw_requests_total", "Requests.", "route", "a", "code", "2xx")).isSameAs(a);
        a.inc();
        a.add(2);
        r.counter("gw_requests_total", "Requests.", "route", "b", "code", "5xx").inc();
        assertThat(r.scrape()).isEqualTo("""
                # HELP gw_requests_total Requests.
                # TYPE gw_requests_total counter
                gw_requests_total{route="a",code="2xx"} 3
                gw_requests_total{route="b",code="5xx"} 1
                """);
    }

    @Test
    void histogramsAreCumulativeInSeconds() {
        MetricRegistry r = new MetricRegistry();
        Histogram h = r.histogram("lat_seconds", "Latency.", new double[] {0.001, 0.01, 0.1}, "route", "a");
        h.observeNanos(500_000);      // 0.5 ms
        h.observeNanos(1_000_000);    // exactly 1 ms: le="0.001" is inclusive
        h.observeNanos(50_000_000);   // 50 ms
        h.observeNanos(2_000_000_000L); // 2 s: +Inf only
        assertThat(r.scrape()).isEqualTo("""
                # HELP lat_seconds Latency.
                # TYPE lat_seconds histogram
                lat_seconds_bucket{route="a",le="0.001"} 2
                lat_seconds_bucket{route="a",le="0.01"} 2
                lat_seconds_bucket{route="a",le="0.1"} 3
                lat_seconds_bucket{route="a",le="+Inf"} 4
                lat_seconds_sum{route="a"} 2.0515
                lat_seconds_count{route="a"} 4
                """);
        assertThat(h.count()).isEqualTo(4);
    }

    @Test
    void gaugesAndDynamicFamiliesAreReadAtScrapeTime() {
        MetricRegistry r = new MetricRegistry();
        int[] v = {1};
        r.gauge("g", "A gauge.", () -> v[0]);
        r.gaugeFamily("inflight", "Per upstream.", List.of("upstream"), sink -> {
            sink.sample(3, "u1");
            sink.sample(0.5, "u\"2");
        });
        v[0] = 7;
        assertThat(r.scrape()).contains("g 7\n").contains("inflight{upstream=\"u1\"} 3\n")
                .contains("inflight{upstream=\"u\\\"2\"} 0.5\n");
    }

    @Test
    void escapesLabelValuesAndRemovesSeriesByLabel() {
        MetricRegistry r = new MetricRegistry();
        r.counter("c_total", "C.", "route", "a\\b\nc").inc();
        r.counter("c_total", "C.", "route", "keep").inc();
        assertThat(r.scrape()).contains("c_total{route=\"a\\\\b\\nc\"} 1");
        r.removeIf("c_total", "route", id -> !id.equals("keep"));
        assertThat(r.scrape()).doesNotContain("a\\\\b").contains("c_total{route=\"keep\"} 1");
        r.removeIf("c_total", "route", id -> true);
        assertThat(r.scrape()).isEmpty();
    }

    @Test
    void rejectsBadNamesAndTypeConflicts() {
        MetricRegistry r = new MetricRegistry();
        r.counter("x_total", "X.");
        assertThatThrownBy(() -> r.histogram("x_total", "X.", Buckets.LATENCY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.counter("bad-name", "B.")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.counter("y_total", "Y.", "le", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.counter("y_total", "Y.", "odd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processMetricsRegister() {
        MetricRegistry r = new MetricRegistry();
        ProcessMetrics.register(r, "gateway", "1.0");
        String text = r.scrape();
        assertThat(text).contains("apigw_build_info{component=\"gateway\",version=\"1.0\",runtime=\"")
                .contains("process_start_time_seconds ").contains("jvm_memory_used_bytes{area=\"heap\"} ")
                .contains("jvm_threads_live ");
        for (String line : text.split("\n")) {
            assertThat(line).matches("# (HELP|TYPE) .*|[a-zA-Z_:][a-zA-Z0-9_:]*(\\{.*\\})? \\S+");
        }
    }
}
