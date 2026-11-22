package apigw.observability.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import org.slf4j.event.Level;

/** Renders one log event as a single JSON line. */
final class JsonLines {

    private JsonLines() {
    }

    static String render(long epochMillis, Level level, String logger, String thread, String message,
            Throwable throwable) {
        StringBuilder b = new StringBuilder(160 + (message == null ? 0 : message.length()));
        b.append("{\"ts\":\"").append(Instant.ofEpochMilli(epochMillis)).append("\",\"level\":\"")
                .append(level.name()).append("\",\"logger\":");
        string(b, logger);
        b.append(",\"thread\":");
        string(b, thread);
        b.append(",\"msg\":");
        string(b, message == null ? "" : message);
        if (throwable != null) {
            StringWriter trace = new StringWriter();
            throwable.printStackTrace(new PrintWriter(trace));
            b.append(",\"error\":");
            string(b, throwable.getClass().getName());
            b.append(",\"stack\":");
            string(b, trace.toString());
        }
        return b.append("}\n").toString();
    }

    /** An INFO line carrying a pre-rendered JSON object under {@code field} (audit events). */
    static String renderEvent(long epochMillis, String logger, String thread, String message, String field,
            String jsonObject) {
        String line = render(epochMillis, Level.INFO, logger, thread, message, null);
        StringBuilder b = new StringBuilder(line.length() + field.length() + jsonObject.length() + 4);
        b.append(line, 0, line.length() - 2).append(',');
        string(b, field);
        return b.append(':').append(jsonObject).append("}\n").toString();
    }

    /** Appends {@code s} as a JSON string literal (RFC 8259 escaping). */
    static void string(StringBuilder b, String s) {
        apigw.observability.JsonText.string(b, s);
    }
}
