package at.haha007.edenmcp.core.audit;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.logging.Logger;

public final class AuditLogger {
    private static final int DEFAULT_MAX_VALUE_LENGTH = 200;
    private final Logger logger;
    private final int maxValueLength;
    private volatile boolean logToolCalls = true;
    private volatile boolean logModeChanges = true;

    public AuditLogger(Logger logger) {
        this(logger, DEFAULT_MAX_VALUE_LENGTH);
    }

    public AuditLogger(Logger logger, int maxValueLength) {
        this.logger = logger;
        this.maxValueLength = Math.max(1, maxValueLength);
    }

    public void setLogToolCalls(boolean logToolCalls) { this.logToolCalls = logToolCalls; }
    public void setLogModeChanges(boolean logModeChanges) { this.logModeChanges = logModeChanges; }

    public void toolCall(@Nullable String caller, String tool, String mode, boolean success,
                         @Nullable String outcome) {
        if (!logToolCalls) return;
        logger.info("[AUDIT] tool ts=" + Instant.now() + " caller=" + safe(caller) + " tool=" + safe(tool)
                + " mode=" + safe(mode) + " success=" + success + " outcome=" + safe(outcome));
    }

    /** Resource reads are audited under the same toggle as tool calls. */
    public void resourceRead(@Nullable String caller, @Nullable String uri, String mode, boolean success,
                             @Nullable String outcome) {
        if (!logToolCalls) return;
        logger.info("[AUDIT] resource ts=" + Instant.now() + " caller=" + safe(caller) + " uri=" + safe(uri)
                + " mode=" + safe(mode) + " success=" + success + " outcome=" + safe(outcome));
    }

    public void modeChange(@Nullable String caller, String previous, @Nullable String next) {
        if (!logModeChanges) return;
        logger.warning("[AUDIT] mode-change ts=" + Instant.now() + " caller=" + safe(caller)
                + " previous=" + safe(previous) + " next=" + safe(next));
        if ("UNSAFE".equalsIgnoreCase(next)) {
            logger.warning("[SECURITY] Operating mode escalated to UNSAFE by " + safe(caller));
        }
    }

    public void authFailure(@Nullable String caller) {
        logger.warning("[AUDIT] auth-failure ts=" + Instant.now() + " caller=" + safe(caller));
    }

    /** Records a file mutation by path only; file content must never reach the log. */
    public void fileWrite(@Nullable String caller, @Nullable String path, String mode) {
        logger.warning("[AUDIT] file-write ts=" + Instant.now() + " caller=" + safe(caller)
                + " path=" + safe(path) + " mode=" + safe(mode));
    }

    /** Records a key rotation by id only; key material must never reach the log. */
    public void keyRotate(@Nullable String caller, @Nullable String keyId) {
        logger.warning("[AUDIT] key-rotate ts=" + Instant.now() + " caller=" + safe(caller)
                + " keyId=" + safe(keyId));
    }

    /**
     * Makes any caller-derived value safe for a single-line audit record: control characters
     * (including CR/LF) become spaces and the value is length-bounded. Public so transports can
     * sanitize user-supplied identifiers before writing their own log lines.
     */
    public String safe(@Nullable String s) {
        if (s == null) return "?";
        StringBuilder cleaned = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            cleaned.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return cleaned.length() > maxValueLength
                ? cleaned.substring(0, maxValueLength) + "..." : cleaned.toString();
    }
}
