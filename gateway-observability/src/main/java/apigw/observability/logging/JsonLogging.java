package apigw.observability.logging;

import java.time.Duration;
import org.slf4j.LoggerFactory;

/** Application-facing controls for the JSON log pipeline. */
public final class JsonLogging {

    private JsonLogging() {
    }

    /**
     * Blocks until everything logged so far is on stdout, up to {@code timeout}. Call at the end of
     * shutdown, from a non-event-loop thread. A no-op if SLF4J is bound to another provider.
     */
    public static boolean flush(Duration timeout) {
        if (!(LoggerFactory.getILoggerFactory() instanceof JsonLoggerFactory)) {
            return true;
        }
        return LogSink.instance().awaitDrained(timeout.toMillis());
    }

    /**
     * Logs a structured event: an INFO line from {@code logger} with message {@code message} and the
     * JSON object {@code jsonObject} (already serialised, one line, no secrets) under {@code field}.
     * Falls back to a plain SLF4J INFO line when another provider is bound.
     */
    public static void event(String logger, String message, String field, String jsonObject) {
        if (!(LoggerFactory.getILoggerFactory() instanceof JsonLoggerFactory)) {
            LoggerFactory.getLogger(logger).info("{} {}={}", message, field, jsonObject);
            return;
        }
        if (org.slf4j.event.Level.INFO.toInt() >= LogLevels.threshold(logger)) {
            LogSink.instance().offer(JsonLines.renderEvent(System.currentTimeMillis(), logger,
                    Thread.currentThread().getName(), message, field, jsonObject));
        }
    }
}
