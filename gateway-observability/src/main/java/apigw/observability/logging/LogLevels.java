package apigw.observability.logging;

import java.util.Locale;
import org.slf4j.event.Level;

/**
 * Level thresholds, initialised at run time from {@code GW_LOG_LEVEL} (default INFO).
 * Netty and Vert.x internals are held at WARN or above to keep their chatter out of the logs.
 */
final class LogLevels {

    private static final int ROOT = parse(System.getenv("GW_LOG_LEVEL"));
    private static final int FRAMEWORK = Math.max(ROOT, Level.WARN.toInt());

    private LogLevels() {
    }

    static int threshold(String loggerName) {
        return loggerName.startsWith("io.netty") || loggerName.startsWith("io.vertx") ? FRAMEWORK : ROOT;
    }

    static int parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Level.INFO.toInt();
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE" -> Level.TRACE.toInt();
            case "DEBUG" -> Level.DEBUG.toInt();
            case "WARN" -> Level.WARN.toInt();
            case "ERROR" -> Level.ERROR.toInt();
            case "OFF" -> Integer.MAX_VALUE;
            default -> Level.INFO.toInt();
        };
    }
}
