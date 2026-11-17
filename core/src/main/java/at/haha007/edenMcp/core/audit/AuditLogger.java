package at.haha007.edenMcp.core.audit;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

public final class AuditLogger {
    private final Logger logger;
    private volatile boolean logToolCalls = true;
    private volatile boolean logModeChanges = true;

    public AuditLogger(Logger logger) {
        this.logger = logger;
    }

    public void setLogToolCalls(boolean logToolCalls) { this.logToolCalls = logToolCalls; }
    public void setLogModeChanges(boolean logModeChanges) { this.logModeChanges = logModeChanges; }

    public void toolCall(String caller, String tool, String mode, boolean success, String outcome) {
        if (!logToolCalls) return;
        logger.info("[AUDIT] tool ts=" + Instant.now() + " caller=" + safe(caller) + " tool=" + tool
                + " mode=" + mode + " success=" + success + " outcome=" + safe(outcome));
    }

    public void modeChange(String caller, String previous, String next) {
        if (!logModeChanges) return;
        logger.warning("[AUDIT] mode-change ts=" + Instant.now() + " caller=" + safe(caller)
                + " previous=" + previous + " next=" + next);
        if ("UNSAFE".equalsIgnoreCase(next)) {
            logger.warning("[SECURITY] Operating mode escalated to UNSAFE by " + safe(caller));
        }
    }

    public void authFailure(String caller) {
        logger.warning("[AUDIT] auth-failure ts=" + Instant.now() + " caller=" + safe(caller));
    }

    private String safe(String s) {
        if (s == null) return "?";
        String cleaned = s.replaceAll("[\r\n]", " ");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }
}
