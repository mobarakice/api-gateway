package apigw.core.plugin;

/**
 * Service marker: the compile is a dry run in another process (the control plane, ADR 0020). Factories
 * must not touch gateway-local resources (files, network) then; they check what they can (for files:
 * the path's shape) and leave the rest to the gateway's own compile, whose failure keeps the previous
 * snapshot serving and is reported in the node heartbeat.
 */
public final class ValidationOnly {

    public static final ValidationOnly INSTANCE = new ValidationOnly();

    private ValidationOnly() {
    }
}
