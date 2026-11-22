package apigw.observability.tracing;

/** Receives ended spans. {@link #offer} is called on event loops: it must never block. */
public interface SpanExporter {

    void offer(Span span);
}
