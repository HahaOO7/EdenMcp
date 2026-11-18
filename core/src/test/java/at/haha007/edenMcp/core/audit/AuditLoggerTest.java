package at.haha007.edenMcp.core.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AuditLoggerTest {
    private final List<LogRecord> records = new ArrayList<>();
    private AuditLogger audit;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("audit-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        audit = new AuditLogger(logger);
    }

    private String message(int index) {
        return records.get(index).getMessage();
    }

    @Test
    void toolCallRecordsCallerToolModeAndOutcome() {
        audit.toolCall("key=primary@127.0.0.1", "status.tool", "SAFE", true, "ok");

        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.getFirst().getLevel());
        String msg = message(0);
        assertTrue(msg.startsWith("[AUDIT] tool "), msg);
        assertTrue(msg.contains("ts="), msg);
        assertTrue(msg.contains("caller=key=primary@127.0.0.1"), msg);
        assertTrue(msg.contains("tool=status.tool"), msg);
        assertTrue(msg.contains("mode=SAFE"), msg);
        assertTrue(msg.contains("success=true"), msg);
        assertTrue(msg.contains("outcome=ok"), msg);
    }

    @Test
    void callerAndOutcomeAreSanitisedAgainstLogInjection() {
        audit.toolCall("user\r\n[AUDIT] forged success=true", "tool", "SAFE", false,
                "denied\r\n[SECURITY] forged");

        assertEquals(1, records.size(), "one input line must produce exactly one log record");
        String msg = message(0);
        assertFalse(msg.contains("\r"), msg);
        assertFalse(msg.contains("\n"), msg);
        assertTrue(msg.contains("caller=user  [AUDIT] forged success=true"), msg);
        assertTrue(msg.contains("outcome=denied  [SECURITY] forged"), msg);
    }

    @Test
    void longCallerAndKeyIdAreTruncated() {
        String longValue = "a".repeat(250);

        audit.authFailure(longValue);
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
        assertTrue(message(0).contains("caller=" + "a".repeat(200) + "..."), message(0));
        assertFalse(message(0).contains("a".repeat(201)), "caller must be capped at 200 characters");

        records.clear();
        audit.keyRotate("u", longValue);
        assertTrue(message(0).contains("keyId=" + "a".repeat(200) + "..."), message(0));
        assertFalse(message(0).contains("a".repeat(201)));
    }

    @Test
    void nullValuesBecomeQuestionMark() {
        audit.authFailure(null);
        assertTrue(message(0).contains("caller=?"), message(0));
    }

    @Test
    void unsafeEscalationEmitsAnAdditionalSecurityRecord() {
        audit.modeChange("key=primary", "SAFE", "UNSAFE");

        assertEquals(2, records.size());
        assertTrue(message(0).startsWith("[AUDIT] mode-change"), message(0));
        assertTrue(message(1).startsWith("[SECURITY] Operating mode escalated to UNSAFE by key=primary"), message(1));
        assertTrue(records.stream().allMatch(r -> r.getLevel() == Level.WARNING));

        records.clear();
        audit.modeChange("key=primary", "UNSAFE", "SAFE");
        assertEquals(1, records.size(), "de-escalation is not a security event");

        records.clear();
        audit.modeChange("key=primary", "MODERATE", "unsafe");
        assertEquals(2, records.size(), "the escalation check is case-insensitive");
    }

    @Test
    void keyRotationLogsTheKeyIdSanitised() {
        audit.keyRotate("caller\nname", "retired-123\r\n[forged]");

        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
        String msg = message(0);
        assertTrue(msg.startsWith("[AUDIT] key-rotate"), msg);
        assertTrue(msg.contains("keyId=retired-123  [forged]"), msg);
        assertFalse(msg.contains("\r") || msg.contains("\n"), msg);
    }

    @Test
    void toolAndModeLoggingCanBeDisabledIndependently() {
        audit.setLogToolCalls(false);
        audit.setLogModeChanges(false);

        audit.toolCall("u", "t", "SAFE", true, "ok");
        audit.modeChange("u", "SAFE", "UNSAFE");
        assertTrue(records.isEmpty(), "disabled audit categories must not emit records");

        audit.authFailure("u");
        audit.keyRotate("u", "id");
        assertEquals(2, records.size(), "security events are always recorded");
    }
}
