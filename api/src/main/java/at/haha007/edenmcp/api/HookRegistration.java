package at.haha007.edenmcp.api;

/** Registration handle for a full {@link Hook}. */
public interface HookRegistration extends Registration {
    Hook hook();

    HookInfo info();

    /** Admin-controlled enable/disable; re-running enable rebuilds the contribution. */
    void setEnabled(boolean enabled);

    boolean isEnabled();
}
