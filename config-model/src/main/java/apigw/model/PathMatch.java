package apigw.model;

/** How a route's {@code path} is matched. */
public enum PathMatch {
    /** The request path equals the route path. */
    EXACT,
    /** The request path is the route path or continues it at a segment boundary. */
    PREFIX,
    /** Segment template: {@code /users/{id}/orders}; {@code {name}} matches exactly one non-empty segment. */
    TEMPLATE
}
