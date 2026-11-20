package at.haha007.edenmcp.hooks.luckperms;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LpSupportTest {
    @Test
    void invalidUuidIsRejectedBeforeTouchingLuckPerms() {
        LpSupport.InvalidArgument error = assertThrows(
                LpSupport.InvalidArgument.class,
                () -> LpSupport.requireUser(null, Map.of("uuid", "not-a-uuid")));
        assertEquals("Invalid uuid", error.getMessage());
    }

    @Test
    void missingUserArgumentsAreRejected() {
        LpSupport.InvalidArgument error = assertThrows(
                LpSupport.InvalidArgument.class,
                () -> LpSupport.requireUser(null, Map.of()));
        assertEquals("Provide username or uuid", error.getMessage());
    }

    @Test
    void boundedLimitArgUsesDefaultAndRejectsOutOfRange() throws Exception {
        assertEquals(50, LpSupport.boundedLimitArg(Map.of(), 50, 200));
        assertEquals(7, LpSupport.boundedLimitArg(Map.of("limit", "7"), 50, 200));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boundedLimitArg(Map.of("limit", "0"), 50, 200));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boundedLimitArg(Map.of("limit", "201"), 50, 200));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boundedLimitArg(Map.of("limit", "abc"), 50, 200));
    }

    @Test
    void contextPairsAreNormalizedWithoutLuckPerms() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("contexts", Map.of("world", " world_nether ", "", "ignored"));

        assertEquals(Map.of("world", "world_nether"), LpSupport.contextPairs(args.get("contexts")));
        assertThrows(LpSupport.InvalidArgument.class, () -> LpSupport.contextPairs("not-an-object"));
    }

    @Test
    void jsonNumbersAreAcceptedAndWrongTypesRejected() throws Exception {
        assertEquals(7, LpSupport.boundedLimitArg(Map.of("limit", 7), 50, 200));
        assertEquals(7, LpSupport.boundedLimitArg(Map.of("limit", 7.0), 50, 200));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boundedLimitArg(Map.of("limit", 7.5), 50, 200));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.stringArg(Map.of("permission", 42), "permission"));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boolArg(Map.of("value", 1), "value", true));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.contextPairs(Map.of("world", 7)));
    }

    @Test
    void limitedListsReportTruncation() {
        assertEquals(List.of(1, 2), LpSupport.limited(List.of(1, 2, 3), 2));
        assertTrue(LpSupport.hasMore(List.of(1, 2, 3), 2));
        assertFalse(LpSupport.hasMore(List.of(1, 2), 2));
    }
}
