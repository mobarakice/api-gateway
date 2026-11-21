package apigw.controlplane.api;

import apigw.model.ConfigError;
import java.util.List;

/**
 * An Admin API failure rendered as RFC 9457 problem details. {@code detail} and {@code errors} must
 * never contain secrets, tokens or stack traces; config errors name the field (path) and the rule.
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String title;
    private final transient List<ConfigError> errors;
    private final transient List<Conflict> conflicts;

    /** A key whose live {@code mod_revision} differs from what a change was based on. */
    public record Conflict(String key, long expectedRevision, long actualRevision) {
    }

    public ApiException(int status, String title, String detail) {
        this(status, title, detail, List.of(), List.of());
    }

    public ApiException(int status, String title, String detail, List<ConfigError> errors,
            List<Conflict> conflicts) {
        super(detail, null, false, false);
        this.status = status;
        this.title = title;
        this.errors = List.copyOf(errors);
        this.conflicts = List.copyOf(conflicts);
    }

    public static ApiException invalid(List<ConfigError> errors) {
        return new ApiException(422, "Validation failed", "the change is not valid; see errors", errors, List.of());
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(400, "Bad request", detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(404, "Not found", detail);
    }

    public static ApiException conflict(List<Conflict> conflicts) {
        return new ApiException(412, "Precondition failed",
                "the object changed since it was read; reload it and stage the change again", List.of(), conflicts);
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public List<ConfigError> errors() {
        return errors;
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }
}
