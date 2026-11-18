package apigw.observability.logging;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * Holds only its name, so instances may live in the native image heap. Levels and the sink are
 * read from runtime-initialised holders ({@link LogLevels}, {@link LogSink}).
 */
public final class JsonLogger extends LegacyAbstractLogger {

    private static final long serialVersionUID = 1L;

    JsonLogger(String name) {
        this.name = name;
    }

    private boolean enabled(Level level) {
        return level.toInt() >= LogLevels.threshold(name);
    }

    @Override
    public boolean isTraceEnabled() {
        return enabled(Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
        return enabled(Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return enabled(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return enabled(Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return enabled(Level.ERROR);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern, Object[] arguments,
            Throwable throwable) {
        String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
        LogSink.instance().offer(JsonLines.render(System.currentTimeMillis(), level, name,
                Thread.currentThread().getName(), message, throwable));
    }
}
