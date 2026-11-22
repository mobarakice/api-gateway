package apigw.observability.metrics;

/** Standard bucket layouts, in seconds. */
public final class Buckets {

    /** Request and upstream latency: 1 ms to 30 s, 15 buckets (+Inf). */
    public static final double[] LATENCY = {
        0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 30};

    /** Control-loop work (etcd calls, config propagation): 5 ms to 60 s. */
    public static final double[] CONTROL = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60};

    private Buckets() {
    }
}
