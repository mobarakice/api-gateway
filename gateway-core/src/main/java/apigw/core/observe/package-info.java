/**
 * Telemetry hooks the request path calls: per-route meters compiled into each route, a process-wide
 * request observer (metrics, access log, trace end) and a per-request {@link apigw.core.observe.Trace}.
 * Implementations live in {@code gateway-app}; with none configured every hook is a constant no-op.
 */
package apigw.core.observe;
