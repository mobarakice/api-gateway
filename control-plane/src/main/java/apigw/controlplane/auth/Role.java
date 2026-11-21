package apigw.controlplane.auth;

/** Admin roles, ordered: each role includes everything the ones before it may do. */
public enum Role {
    /** Read configuration, change sets, history, gateway status and the plugin catalog. */
    VIEWER,
    /** Create and edit change sets; validate and diff them. */
    EDITOR,
    /** Publish change sets and roll back history. */
    PUBLISHER,
    /** Settings, certificates and the audit log. */
    ADMIN;

    public boolean includes(Role other) {
        return compareTo(other) >= 0;
    }

    public String claimName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
