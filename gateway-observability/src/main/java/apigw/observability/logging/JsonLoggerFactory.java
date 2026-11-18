package apigw.observability.logging;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/** One {@link JsonLogger} per name. */
public final class JsonLoggerFactory implements ILoggerFactory {

    private final ConcurrentMap<String, JsonLogger> loggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        return loggers.computeIfAbsent(name, JsonLogger::new);
    }
}
