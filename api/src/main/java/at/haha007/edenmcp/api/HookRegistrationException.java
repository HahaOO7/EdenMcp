package at.haha007.edenmcp.api;

/** Thrown for registration-time validation errors; contribution failures are reported via state. */
public class HookRegistrationException extends RuntimeException {
    public enum Reason {
        API_UNAVAILABLE,
        INVALID_ID,
        INVALID_NAME,
        INVALID_URI,
        DUPLICATE_ID,
        NAME_COLLISION,
        LEVEL_NOT_ALLOWED,
        SIDE_EFFECT_NOT_ALLOWED,
        NOT_OWNER
    }

    private final Reason reason;

    public HookRegistrationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public HookRegistrationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    @SuppressWarnings("unused") // public API for consumers
    public Reason reason() {
        return reason;
    }
}
