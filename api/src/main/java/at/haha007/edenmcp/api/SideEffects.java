package at.haha007.edenmcp.api;

/** Declares what a contributed tool does, for auditing and client display. */
public enum SideEffects {
    NONE,
    OBSERVABLE,
    MUTATING,
    DANGEROUS
}
