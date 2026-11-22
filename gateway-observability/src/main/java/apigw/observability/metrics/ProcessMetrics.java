package apigw.observability.metrics;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Process and runtime gauges: CPU time, resident memory and open file descriptors (Linux {@code /proc}),
 * heap and non-heap use, GC counts and time, live threads, start time and build info. Works on the
 * JVM and in a native image (whose platform beans report what Substrate VM tracks).
 */
public final class ProcessMetrics {

    private static final Path STATUS = Path.of("/proc/self/status");
    private static final Path FDS = Path.of("/proc/self/fd");

    private ProcessMetrics() {
    }

    /** Registers the process families; {@code component} is e.g. {@code gateway} or {@code control-plane}. */
    public static void register(MetricRegistry r, String component, String version) {
        String runtime = "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode")) ? "native" : "jvm";
        r.gauge("apigw_build_info", "Build and runtime of this process (value is always 1).", () -> 1,
                "component", component, "version", version, "runtime", runtime);
        long startMillis = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli)
                .orElse(System.currentTimeMillis());
        r.gauge("process_start_time_seconds", "Start time of the process since the Unix epoch.",
                () -> startMillis / 1000.0);
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            r.counterFn("process_cpu_seconds_total", "User and system CPU time spent, in seconds.",
                    () -> Math.max(0, sun.getProcessCpuTime()) / 1e9);
        }
        r.gauge("process_cpu_count", "Processors available to the process.",
                () -> Runtime.getRuntime().availableProcessors());
        if (Files.isReadable(STATUS)) {
            r.gauge("process_resident_memory_bytes", "Resident set size, in bytes.", ProcessMetrics::rssBytes);
            r.gauge("process_open_fds", "Open file descriptors.", ProcessMetrics::openFds);
        }
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        r.gauge("jvm_memory_used_bytes", "Used memory by area.",
                () -> memory.getHeapMemoryUsage().getUsed(), "area", "heap");
        r.gauge("jvm_memory_committed_bytes", "Committed memory by area.",
                () -> memory.getHeapMemoryUsage().getCommitted(), "area", "heap");
        r.gauge("jvm_memory_max_bytes", "Maximum memory by area (-1 when undefined).",
                () -> memory.getHeapMemoryUsage().getMax(), "area", "heap");
        r.gauge("jvm_memory_used_bytes", "Used memory by area.",
                () -> memory.getNonHeapMemoryUsage().getUsed(), "area", "nonheap");
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcs) {
            r.counterFn("jvm_gc_collections_total", "Garbage collections.",
                    () -> Math.max(0, gc.getCollectionCount()), "gc", gc.getName());
            r.counterFn("jvm_gc_collection_seconds_total", "Time spent in garbage collection, in seconds.",
                    () -> Math.max(0, gc.getCollectionTime()) / 1000.0, "gc", gc.getName());
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        r.gauge("jvm_threads_live", "Live threads.", threads::getThreadCount);
    }

    /** VmRSS from /proc/self/status (kB), in bytes; NaN when unavailable. */
    static double rssBytes() {
        try {
            for (String line : Files.readAllLines(STATUS, StandardCharsets.US_ASCII)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.substring(6).trim().split("\\s+");
                    return Long.parseLong(parts[0]) * 1024.0;
                }
            }
        } catch (IOException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return Double.NaN;
        }
        return Double.NaN;
    }

    static double openFds() {
        int n = 0;
        try (DirectoryStream<Path> fds = Files.newDirectoryStream(FDS)) {
            for (Path ignored : fds) {
                n++;
            }
        } catch (IOException e) {
            return Double.NaN;
        }
        return n;
    }
}
