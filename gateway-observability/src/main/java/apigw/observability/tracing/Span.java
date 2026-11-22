package apigw.observability.tracing;

import java.util.Arrays;

/**
 * One span. Mutated only by the thread that owns the request, then handed to the exporter on
 * {@link Tracer#end}; the exporter's queue publishes it safely. Attributes are bounded.
 */
public final class Span {

    /** OTLP span kinds. */
    public static final int SERVER = 2;
    public static final int CLIENT = 3;
    /** OTLP status codes. */
    static final int STATUS_UNSET = 0;
    static final int STATUS_ERROR = 2;
    private static final int MAX_ATTRIBUTES = 16;

    final String traceId;
    final String spanId;
    final String parentSpanId;
    final int kind;
    final long startEpochNanos;
    String name;
    long endEpochNanos;
    int status = STATUS_UNSET;
    final String[] keys = new String[MAX_ATTRIBUTES];
    /** String or Long. */
    final Object[] values = new Object[MAX_ATTRIBUTES];
    int attributes;

    Span(String traceId, String spanId, String parentSpanId, int kind, String name, long startEpochNanos) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.kind = kind;
        this.name = name;
        this.startEpochNanos = startEpochNanos;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public Span name(String n) {
        this.name = n;
        return this;
    }

    public Span attr(String key, String value) {
        if (value != null) {
            put(key, value);
        }
        return this;
    }

    public Span attr(String key, long value) {
        put(key, value);
        return this;
    }

    /** Marks the span failed (OTLP status ERROR). */
    public Span error() {
        this.status = STATUS_ERROR;
        return this;
    }

    boolean ended() {
        return endEpochNanos != 0;
    }

    private void put(String key, Object value) {
        for (int i = 0; i < attributes; i++) {
            if (keys[i].equals(key)) {
                values[i] = value;
                return;
            }
        }
        if (attributes < MAX_ATTRIBUTES) {
            keys[attributes] = key;
            values[attributes++] = value;
        }
    }

    /** Attribute value by key, or null (tests). */
    public Object attribute(String key) {
        int i = Arrays.asList(keys).subList(0, attributes).indexOf(key);
        return i < 0 ? null : values[i];
    }

    public String name() {
        return name;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public int kind() {
        return kind;
    }

    public boolean failed() {
        return status == STATUS_ERROR;
    }

    public long startEpochNanos() {
        return startEpochNanos;
    }

    public long endEpochNanos() {
        return endEpochNanos;
    }
}
