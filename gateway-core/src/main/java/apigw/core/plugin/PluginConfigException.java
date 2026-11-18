package apigw.core.plugin;

import apigw.model.ConfigError;
import java.util.List;

/** A plugin rejected its configuration. Paths are relative to the plugin's {@code config} object. */
public final class PluginConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient List<ConfigError> errors;

    public PluginConfigException(List<ConfigError> errors) {
        super(errors.toString());
        this.errors = List.copyOf(errors);
    }

    public PluginConfigException(String path, String message) {
        this(List.of(new ConfigError(path, message)));
    }

    public List<ConfigError> errors() {
        return errors;
    }
}
