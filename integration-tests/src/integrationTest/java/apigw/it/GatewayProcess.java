package apigw.it;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The gateway as a separate OS process: the native binary ({@code -Dgw.it.mode=native}) or the
 * JVM build ({@code -Dgw.it.mode=jvm}). Same arguments and environment either way.
 */
final class GatewayProcess implements AutoCloseable {

    private final Process process;
    private final long startedNanos;
    private final List<String> output = new CopyOnWriteArrayList<>();
    private final Thread reader;

    private GatewayProcess(Process process, long startedNanos) {
        this.process = process;
        this.startedNanos = startedNanos;
        this.reader = new Thread(this::pump, "gateway-stdout");
        reader.setDaemon(true);
        reader.start();
    }

    static String mode() {
        return System.getProperty("gw.it.mode", "jvm");
    }

    static GatewayProcess start(List<String> args, Map<String, String> env) throws IOException {
        List<String> cmd = new ArrayList<>();
        if ("native".equals(mode())) {
            cmd.add(required("gw.it.binary"));
        } else {
            cmd.addAll(List.of(required("gw.it.java"), "-cp", required("gw.it.classpath"), "apigw.app.GatewayMain"));
        }
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("GW_"));
        pb.environment().putAll(env);
        long t0 = System.nanoTime();
        return new GatewayProcess(pb.start(), t0);
    }

    /** The control plane as a process, in the same mode as the gateway (JVM classpath or native binary). */
    static GatewayProcess controlPlane(Map<String, String> env) throws IOException {
        List<String> cmd = new ArrayList<>();
        if ("native".equals(mode())) {
            cmd.add(required("gw.it.cpBinary"));
        } else {
            cmd.addAll(List.of(required("gw.it.java"), "-cp", required("gw.it.cpClasspath"),
                    "apigw.controlplane.ControlPlaneMain"));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("GW_") || k.startsWith("CP_"));
        pb.environment().putAll(env);
        long t0 = System.nanoTime();
        return new GatewayProcess(pb.start(), t0);
    }

    private static String required(String prop) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("system property " + prop + " is not set; run via Gradle");
        }
        return v;
    }

    private void pump() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                output.add(line);
            }
        } catch (IOException e) {
            output.add("<<stdout closed: " + e.getMessage() + ">>");
        }
    }

    /** Waits for a matching output line; returns it, or fails with the output so far. */
    String awaitLine(Predicate<String> match, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int seen = 0;
        while (System.nanoTime() < deadline) {
            List<String> snapshot = List.copyOf(output);
            for (int i = seen; i < snapshot.size(); i++) {
                if (match.test(snapshot.get(i))) {
                    return snapshot.get(i);
                }
            }
            seen = snapshot.size();
            if (!process.isAlive() && seen == output.size()) {
                break;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no matching line within " + timeout + "; output:\n" + String.join("\n", output));
    }

    long millisSinceStart() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    List<String> output() {
        return List.copyOf(output);
    }

    long pid() {
        return process.pid();
    }

    /** Resident set size in KiB, via ps (Linux and macOS). */
    long rssKib() throws IOException, InterruptedException {
        Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(process.pid())).start();
        String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
        ps.waitFor(5, TimeUnit.SECONDS);
        return Long.parseLong(out);
    }

    /** Sends SIGTERM and waits for exit. Returns the exit code, or -1 on timeout. */
    int terminate(Duration timeout) throws InterruptedException {
        // ProcessHandle.destroy() only sends SIGTERM. Process.destroy() would also close our end of
        // the child's stdout, losing everything it logs while shutting down.
        process.toHandle().destroy();
        return waitForExit(timeout);
    }

    /** Waits for exit and for stdout to be fully read, so {@link #output()} is complete afterwards. */
    int waitForExit(Duration timeout) throws InterruptedException {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return -1;
        }
        reader.join(5_000);
        return process.exitValue();
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}
