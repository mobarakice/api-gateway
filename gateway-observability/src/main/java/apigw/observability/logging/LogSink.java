package apigw.observability.logging;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.event.Level;

/**
 * Bounded queue of rendered lines drained to stdout by one daemon thread. {@link #offer} never
 * blocks: when the queue is full the line is dropped and counted, and the writer reports the
 * count. Initialised lazily at run time on the first log call. The writer runs until the process
 * halts, so lines logged from shutdown hooks are still written; {@link #awaitDrained} waits for them.
 */
final class LogSink {

    static final int CAPACITY = 8192;
    private static final int BATCH = 256;

    private final BlockingQueue<String> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong flushed = new AtomicLong();
    private final OutputStream out;

    private static final class Holder {
        private static final LogSink INSTANCE = LogSink.start(
                new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 64 * 1024), CAPACITY);
    }

    LogSink(OutputStream out, int capacity) {
        this.out = out;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    static LogSink instance() {
        return Holder.INSTANCE;
    }

    static LogSink start(OutputStream out, int capacity) {
        LogSink sink = new LogSink(out, capacity);
        Thread writer = new Thread(sink::drainLoop, "gw-log-writer");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(sink::flushOnExit, "gw-log-flush"));
        return sink;
    }

    void offer(String line) {
        if (queue.offer(line)) {
            accepted.incrementAndGet();
        } else {
            dropped.incrementAndGet();
        }
    }

    /**
     * Waits until every line accepted so far has been written and flushed, up to {@code timeoutMillis}.
     * The writer keeps running during JVM shutdown (it is a daemon thread, and the JVM halts only after
     * all shutdown hooks finish), so lines logged by other hooks are still written.
     *
     * @return true when fully drained
     */
    boolean awaitDrained(long timeoutMillis) {
        long target = accepted.get();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (flushed.get() < target) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    long droppedCount() {
        return dropped.get();
    }

    int size() {
        return queue.size();
    }

    private void drainLoop() {
        List<String> batch = new ArrayList<>(BATCH);
        long reportedDrops = 0;
        long written = 0;
        while (true) {
            try {
                String first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, BATCH - 1);
                    write(batch);
                    written += batch.size();
                    batch.clear();
                }
                long drops = dropped.get();
                if (drops != reportedDrops) {
                    writeLine(JsonLines.render(System.currentTimeMillis(), Level.WARN, LogSink.class.getName(),
                            "gw-log-writer", "log queue full, dropped " + (drops - reportedDrops) + " events", null));
                    reportedDrops = drops;
                }
                if (queue.isEmpty()) {
                    out.flush();
                    flushed.set(written);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                // stdout is gone (closed pipe); there is nowhere left to report this. Keep draining
                // so callers never block, and count everything from here on as dropped.
                int lost = queue.size();
                queue.clear();
                dropped.addAndGet(lost);
                written += batch.size() + lost;
                batch.clear();
                flushed.set(written);
            }
        }
    }

    /** Shutdown hook: give queued lines up to two seconds to reach stdout. Never stops the writer. */
    void flushOnExit() {
        awaitDrained(2_000);
    }

    /** Synchronous drain for tests that construct a sink without starting its writer thread. */
    void drainNowForTest() throws IOException {
        List<String> rest = new ArrayList<>(queue.size());
        queue.drainTo(rest);
        write(rest);
        out.flush();
    }

    private void write(List<String> lines) throws IOException {
        for (String line : lines) {
            writeLine(line);
        }
    }

    private void writeLine(String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
    }
}
