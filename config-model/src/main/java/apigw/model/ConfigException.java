package apigw.model;

import java.util.List;

/** A configuration was rejected. Carries every error found, not just the first. */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;
    private static final int MAX_IN_MESSAGE = 20;

    private final transient List<ConfigError> errors;

    public ConfigException(List<ConfigError> errors) {
        super(summary(errors));
        this.errors = List.copyOf(errors);
    }

    public List<ConfigError> errors() {
        return errors;
    }

    private static String summary(List<ConfigError> errors) {
        StringBuilder b = new StringBuilder(errors.size() + " configuration error(s): ");
        for (int i = 0; i < Math.min(errors.size(), MAX_IN_MESSAGE); i++) {
            if (i > 0) {
                b.append("; ");
            }
            b.append(errors.get(i));
        }
        if (errors.size() > MAX_IN_MESSAGE) {
            b.append("; ...");
        }
        return b.toString();
    }
}
