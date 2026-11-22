package apigw.observability.metrics;

/** Receives the series of a dynamic gauge family at scrape time. */
public interface GaugeSink {

    /** One series; {@code labelValues} follow the family's label names in order. */
    void sample(double value, String... labelValues);
}
