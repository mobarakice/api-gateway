package apigw.core.plugin;

/** What a request-phase plugin decided. */
public enum Outcome {
    /** Run the next plugin (or proxy). */
    CONTINUE,
    /** The plugin has sent the response; the chain ends. */
    STOP,
    /** The plugin started async work and will call {@link Exchange#resume()} or respond later. */
    PENDING
}
