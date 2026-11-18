package apigw.model;

/**
 * One problem in a configuration document.
 *
 * @param path    JSON-path-like location, e.g. {@code routes[2].path}
 * @param message what is wrong, phrased for an operator; never contains secret values
 */
public record ConfigError(String path, String message) {

    @Override
    public String toString() {
        return path + ": " + message;
    }
}
