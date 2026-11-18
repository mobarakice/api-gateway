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
}
