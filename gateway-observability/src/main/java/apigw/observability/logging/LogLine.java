package apigw.observability.logging;

import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * A structured INFO line with top-level fields, for high-volume events such as the access log:
 * {@code {"ts":..,"level":"INFO","logger":..,"thread":..,"msg":..,"field":value,...}}. Goes through
 * the same bounded, non-blocking sink as every other line. Never put secrets or bodies in a field.
 */
public final class LogLine {

    private final String logger;
    private final StringBuilder b;

    private LogLine(String logger, String message) {
        this.logger = logger;
        String head = JsonLines.render(System.currentTimeMillis(), Level.INFO, logger,
                Thread.currentThread().getName(), message, null);
        this.b = new StringBuilder(head.length() + 256);
        b.append(head, 0, head.length() - 2);
    }

    /** True when INFO lines of {@code logger} are written (check before building a line). */
    public static boolean enabled(String logger) {
        return Level.INFO.toInt() >= LogLevels.threshold(logger);
    }

    public static LogLine info(String logger, String message) {
        return new LogLine(logger, message);
    }

    /** Adds a string field; null values are left out. */
    public LogLine field(String name, String value) {
        if (value != null) {
            b.append(',');
            JsonLines.string(b, name);
            b.append(':');
            JsonLines.string(b, value);
        }
        return this;
    }

    public LogLine field(String name, long value) {
        b.append(',');
        JsonLines.string(b, name);
        b.append(':').append(value);
        return this;
    }

    /** Fixed-point milliseconds with microsecond precision, e.g. {@code 12.345}. */
    public LogLine millis(String name, long nanos) {
        long micros = Math.max(0, nanos) / 1000;
        b.append(',');
        JsonLines.string(b, name);
        b.append(':').append(micros / 1000).append('.');
        long frac = micros % 1000;
        if (frac < 100) {
            b.append('0');
        }
        if (frac < 10) {
            b.append('0');
        }
        b.append(frac);
        return this;
    }

    /** The rendered line so far, without the closing brace (tests). */
    String text() {
        return b + "}";
    }

    /** Queues the line (dropped and counted if the log queue is full). */
    public void emit() {
        String line = b.append("}\n").toString();
        if (LoggerFactory.getILoggerFactory() instanceof JsonLoggerFactory) {
            LogSink.instance().offer(line);
        } else {
            LoggerFactory.getLogger(logger).info(line.trim());
        }
    }

    /** Lines dropped because the log queue was full, since start. */
    public static long dropped() {
        return LoggerFactory.getILoggerFactory() instanceof JsonLoggerFactory ? LogSink.instance().droppedCount() : 0;
    }

    /** Lines waiting to be written. */
    public static int queued() {
        return LoggerFactory.getILoggerFactory() instanceof JsonLoggerFactory ? LogSink.instance().size() : 0;
    }
}
