package at.haha007.edenMcp.core.auth;

/**
 * Identity of the key that authenticated a request. Never contains key material;
 * safe to carry into audit records, tool contexts, and sessions.
 */
public record Principal(String id, String label) {
    public static final String PRIMARY_ID = "primary";

    public static Principal primary() {
        return new Principal(PRIMARY_ID, PRIMARY_ID);
    }

    /** Short, log-safe representation used in caller ids. */
    public String display() {
        return id.equals(label) ? id : id + ":" + label;
    }
}
