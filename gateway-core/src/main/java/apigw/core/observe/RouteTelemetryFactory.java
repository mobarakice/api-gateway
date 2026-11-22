package apigw.core.observe;

/**
 * Creates a route's meters at snapshot compile time. Registered as a plugin service; when absent,
 * routes get {@link RouteTelemetry#NONE}. Returns the same instance for the same route and upstream
 * across snapshots, so counters survive config changes.
 */
public interface RouteTelemetryFactory {

    RouteTelemetry forRoute(String routeId, String upstreamId);
}
