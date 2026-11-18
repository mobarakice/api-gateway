package apigw.core.plugin;

/** Request lifecycle phases, in execution order. */
public enum Phase {
    /** Change the request before anything inspects it (path/header rewrites). */
    REWRITE,
    /** Admission: IP rules, authentication, authorization, rate limiting. */
    ACCESS,
    /** Last chance before proxying: request transforms, cache lookup, breaker check. */
    BEFORE_PROXY,
    /** Upstream response headers are known; body not yet streamed. */
    HEADER_FILTER,
    /** Exchange finished (successfully or not): metrics, logs, spans. */
    LOG
}
