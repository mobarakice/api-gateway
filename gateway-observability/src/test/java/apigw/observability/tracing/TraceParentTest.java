package apigw.observability.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceParentTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @Test
    void parsesValidHeaders() {
        TraceParent p = TraceParent.parse("00-" + TRACE + "-" + SPAN + "-01");
        assertThat(p).isEqualTo(new TraceParent(TRACE, SPAN, true));
        assertThat(TraceParent.parse("00-" + TRACE + "-" + SPAN + "-00").sampled()).isFalse();
        // Flags other than "sampled" are ignored; a future version may append fields.
        assertThat(TraceParent.parse("00-" + TRACE + "-" + SPAN + "-03").sampled()).isTrue();
        assertThat(TraceParent.parse("01-" + TRACE + "-" + SPAN + "-01-extra")).isNotNull();
    }

    @Test
    void rejectsInvalidHeaders() {
        for (String bad : new String[] {
                null, "", "garbage",
                "00-" + TRACE + "-" + SPAN + "-01-extra",              // version 00 must be exactly 55 chars
                "ff-" + TRACE + "-" + SPAN + "-01",                    // forbidden version
                "00-" + TRACE.toUpperCase() + "-" + SPAN + "-01",      // upper case
                "00-00000000000000000000000000000000-" + SPAN + "-01", // zero trace ID
                "00-" + TRACE + "-0000000000000000-01",                // zero span ID
                "00-" + TRACE + "_" + SPAN + "-01",
                "01-" + TRACE + "-" + SPAN + "-01x",
        }) {
            assertThat(TraceParent.parse(bad)).as(String.valueOf(bad)).isNull();
        }
    }

    @Test
    void formatsAndGeneratesIds() {
        assertThat(TraceParent.format(TRACE, SPAN, true)).isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
        String t = TraceIds.traceId();
        String s = TraceIds.spanId();
        assertThat(t).matches("[0-9a-f]{32}").isNotEqualTo(TraceIds.traceId());
        assertThat(s).matches("[0-9a-f]{16}");
        assertThat(TraceParent.parse(TraceParent.format(t, s, false))).isEqualTo(new TraceParent(t, s, false));
    }

    @Test
    void samplerFollowsParentOrRatio() {
        Tracer always = tracer(1.0, true);
        Tracer never = tracer(0.0, true);
        Tracer ignoreParent = tracer(0.0, false);
        TraceParent sampled = new TraceParent(TRACE, SPAN, true);
        TraceParent unsampled = new TraceParent(TRACE, SPAN, false);
        assertThat(always.sample(null)).isTrue();
        assertThat(never.sample(null)).isFalse();
        assertThat(never.sample(sampled)).isTrue();
        assertThat(always.sample(unsampled)).isFalse();
        assertThat(ignoreParent.sample(sampled)).isFalse();
    }

    @Test
    void spansChainAndEndOnce() {
        java.util.List<Span> out = new java.util.ArrayList<>();
        Tracer t = new Tracer(config(1.0, true), out::add);
        long now = System.nanoTime();
        Span server = t.server(new TraceParent(TRACE, SPAN, true), "GET /x", now);
        Span client = t.client(server, "GET", now + 1000);
        t.end(client, now + 5000);
        t.end(server, now + 9000);
        t.end(server, now + 20_000);
        assertThat(out).containsExactly(client, server);
        assertThat(server.traceId()).isEqualTo(TRACE);
        assertThat(server.parentSpanId()).isEqualTo(SPAN);
        assertThat(client.parentSpanId()).isEqualTo(server.spanId());
        assertThat(client.endEpochNanos() - client.startEpochNanos()).isEqualTo(4000);
        long wall = java.time.Instant.now().toEpochMilli() * 1_000_000L;
        assertThat(Math.abs(server.startEpochNanos() - wall)).isLessThan(5_000_000_000L);
    }

    private static Tracer tracer(double ratio, boolean respectParent) {
        return new Tracer(config(ratio, respectParent), span -> { });
    }

    static TracingConfig config(double ratio, boolean respectParent) {
        return new TracingConfig(java.net.URI.create("http://127.0.0.1:4318"), ratio, respectParent,
                java.util.Map.of("service.name", "test"), 16, 4, java.time.Duration.ofSeconds(2));
    }
}
