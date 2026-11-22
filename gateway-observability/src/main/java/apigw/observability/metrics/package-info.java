/**
 * Allocation-free counters and fixed-bucket histograms with Prometheus text exposition (ADR 0023).
 * Recording is a {@code LongAdder} increment; labels are rendered once when a series is created.
 */
package apigw.observability.metrics;
