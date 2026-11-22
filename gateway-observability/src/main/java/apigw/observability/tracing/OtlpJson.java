package apigw.observability.tracing;

import apigw.observability.JsonText;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OTLP {@code ExportTraceServiceRequest} in the protobuf JSON mapping: IDs as hex strings, enums and
 * 64-bit integers as numbers/strings as the OTLP spec prescribes.
 */
final class OtlpJson {

    static final String SCOPE = "apigw.gateway";

    private OtlpJson() {
    }

    static String encode(Map<String, String> resource, List<Span> spans) {
        StringBuilder b = new StringBuilder(256 + spans.size() * 512);
        b.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(resource).entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            attribute(b, e.getKey(), e.getValue());
        }
        b.append("]},\"scopeSpans\":[{\"scope\":{\"name\":\"").append(SCOPE).append("\"},\"spans\":[");
        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            span(b, spans.get(i));
        }
        return b.append("]}]}]}").toString();
    }

    private static void span(StringBuilder b, Span s) {
        b.append("{\"traceId\":\"").append(s.traceId).append("\",\"spanId\":\"").append(s.spanId).append('"');
        if (s.parentSpanId != null) {
            b.append(",\"parentSpanId\":\"").append(s.parentSpanId).append('"');
        }
        b.append(",\"name\":");
        JsonText.string(b, s.name);
        b.append(",\"kind\":").append(s.kind)
                .append(",\"startTimeUnixNano\":\"").append(s.startEpochNanos)
                .append("\",\"endTimeUnixNano\":\"").append(s.endEpochNanos).append("\",\"attributes\":[");
        for (int i = 0; i < s.attributes; i++) {
            if (i > 0) {
                b.append(',');
            }
            attribute(b, s.keys[i], s.values[i]);
        }
        b.append("],\"status\":{\"code\":").append(s.status).append("}}");
    }

    private static void attribute(StringBuilder b, String key, Object value) {
        b.append("{\"key\":");
        JsonText.string(b, key);
        if (value instanceof Long l) {
            b.append(",\"value\":{\"intValue\":\"").append(l).append("\"}}");
        } else {
            b.append(",\"value\":{\"stringValue\":");
            JsonText.string(b, String.valueOf(value));
            b.append("}}");
        }
    }
}
