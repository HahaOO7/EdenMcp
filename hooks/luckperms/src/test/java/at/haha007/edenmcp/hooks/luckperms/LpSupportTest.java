package at.haha007.edenmcp.hooks.luckperms;

import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PermissionNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings({"unused", "SameParameterValue", "IfCanBeSwitch", "TestFailedLine"})
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
        assertEquals(7, LpSupport.boundedLimitArg(Map.of("limit", 7), 50, 200));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boundedLimitArg(Map.of("limit", 0), 50, 200));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boundedLimitArg(Map.of("limit", 201), 50, 200));
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
    void contextPairsAreCappedAndRejectControlCharacters() {
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i <= LpSupport.MAX_CONTEXT_PAIRS; i++) {
            tooMany.put("key" + i, "value");
        }
        assertThrows(LpSupport.InvalidArgument.class, () -> LpSupport.contextPairs(tooMany));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.contextPairs(Map.of("key", "x".repeat(LpSupport.MAX_CONTEXT_VALUE_LENGTH + 1))));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.contextPairs(Map.of("x".repeat(LpSupport.MAX_CONTEXT_KEY_LENGTH + 1), "value")));
        assertThrows(LpSupport.InvalidArgument.class, () -> LpSupport.contextPairs(Map.of("key", "line\nbreak")));
        assertThrows(LpSupport.InvalidArgument.class, () -> LpSupport.contextPairs(Map.of("ke\ty", "value")));
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
    void numericAndBooleanStringsAreNotCoerced() throws Exception {
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boundedLimitArg(Map.of("limit", "7"), 50, 200));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boolArg(Map.of("value", "true"), "value", true));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.boolArg(Map.of("value", "false"), "value", false));
        assertTrue(LpSupport.boolArg(Map.of("value", true), "value", false));
        assertFalse(LpSupport.boolArg(Map.of("value", false), "value", true));
    }

    @Test
    void displayValuesAllowLongerTextButNotControlCharacters() throws Exception {
        assertEquals("§cAdmin", LpSupport.displayValueArg(Map.of("value", " §cAdmin ")));
        assertEquals("x".repeat(256), LpSupport.displayValueArg(Map.of("value", "x".repeat(256))));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.displayValueArg(Map.of("value", "x".repeat(257))));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.displayValueArg(Map.of("value", "a\u0001b")));
        assertThrows(LpSupport.InvalidArgument.class,
                () -> LpSupport.keyArg(Map.of("value", "x".repeat(129)), "value"));
    }

    @Test
    void topNKeepsNewestMatchesInDescendingOrder() {
        Comparator<Integer> newestFirst = Comparator.reverseOrder();
        LpSupport.Bounded<Integer> top = LpSupport.topN(
                Stream.of(5, 1, 9, 3, 8, 2), newestFirst, 3);
        assertEquals(List.of(9, 8, 5), top.values());
        assertEquals(6, top.total());
        assertTrue(top.hasMore());

        LpSupport.Bounded<Integer> shortStream = LpSupport.topN(Stream.of(1, 2), newestFirst, 3);
        assertEquals(List.of(2, 1), shortStream.values());
        assertFalse(shortStream.hasMore());

        LpSupport.Bounded<Integer> filtered = LpSupport.topN(
                Stream.of(1, 9, 2, 8).filter(value -> value < 5), newestFirst, 2);
        assertEquals(List.of(2, 1), filtered.values());
        assertEquals(2, filtered.total(), "filtered-out entries must not count toward total");
    }

    @Test
    void boundedHelpersKeepOnlyTheConfiguredNumberOfValues() {
        LpSupport.Bounded<String> sorted = LpSupport.boundedSorted(
                Stream.of("d", "a", "e", "b", "a"), 2);
        assertEquals(List.of("a", "b"), sorted.values());
        assertEquals(5, sorted.total());
        assertTrue(sorted.hasMore());

        LpSupport.Bounded<Map.Entry<String, List<String>>> first = LpSupport.boundedFirst(
                Stream.of(Map.entry("one", List.of("a")), Map.entry("two", List.of("b")),
                        Map.entry("three", List.of("c"))), 2);
        assertEquals(List.of("one", "two"), first.values().stream().map(Map.Entry::getKey).toList());
        assertEquals(3, first.total());
        assertTrue(first.hasMore());
    }

    @Test
    void clearedNodesCanBeRestoredAfterFailedMutation() {
        ImmutableContextSet contexts = proxy(ImmutableContextSet.class, (p, method, args) -> {
            if (method.getName().equals("toMap")) return Map.of();
            throw new UnsupportedOperationException(method.getName());
        });
        Node node = permissionNode("test.node", contexts);
        PermissionHolder holder = holderWith(List.of(node));

        List<Node> cleared = LpSupport.clearPermissionNodes(holder, "test.node", contexts);
        assertEquals(1, cleared.size());
        assertTrue(holder.data().toCollection().isEmpty());

        LpSupport.restoreNodes(holder, cleared);
        assertEquals(List.of(node), new ArrayList<>(holder.data().toCollection()));
    }

    @Test
    void limitedListsReportTruncation() {
        assertEquals(List.of(1, 2), LpSupport.limited(List.of(1, 2, 3), 2));
        assertTrue(LpSupport.hasMore(List.of(1, 2, 3), 2));
        assertFalse(LpSupport.hasMore(List.of(1, 2), 2));
    }

    @Test
    void awaitReturnsCompletedValue() {
        assertEquals("done", LpSupport.await(CompletableFuture.completedFuture("done"), "completed", 1));
    }

    @Test
    void awaitTimesOutInsteadOfBlockingForever() {
        assertThrows(LpSupport.LuckPermsTimeoutException.class,
                () -> LpSupport.await(new CompletableFuture<>(), "neverCompletes", 1));
    }

    @Test
    void awaitWrapsFailedFuture() {
        assertThrows(LpSupport.LuckPermsTimeoutException.class,
                () -> LpSupport.await(CompletableFuture.failedFuture(new IllegalStateException("backend down")),
                        "failedFuture", 1));
    }

    @Test
    void awaitRestoresInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(LpSupport.LuckPermsTimeoutException.class,
                    () -> LpSupport.await(new CompletableFuture<>(), "interrupted", 1));
            assertTrue(Thread.interrupted(), "interrupt flag should be restored");
        } finally {
            // The assertion above consumes the flag; assert that nothing remains set.
            assertFalse(Thread.interrupted(), "interrupt flag should be cleared after the assertion");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        InvocationHandler safeHandler = (p, method, args) -> switch (method.getName()) {
            case "toString" -> type.getSimpleName() + "@" + System.identityHashCode(p);
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            default -> handler.invoke(p, method, args);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, safeHandler);
    }

    private static Node permissionNode(String permission, ImmutableContextSet contexts) {
        return proxy(PermissionNode.class, (p, method, args) -> switch (method.getName()) {
            case "getPermission" -> permission;
            case "getContexts" -> contexts;
            case "getType" -> NodeType.PERMISSION;
            case "hasExpiry" -> false;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** Minimal in-memory holder, enough to exercise clear/restore without a LuckPerms instance. */
    private static PermissionHolder holderWith(List<Node> initial) {
        List<Node> data = new ArrayList<>(initial);
        NodeMap nodes = (NodeMap) Proxy.newProxyInstance(NodeMap.class.getClassLoader(),
                new Class<?>[]{NodeMap.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("toCollection")) return List.copyOf(data);
                    if (name.equals("clear")) {
                        if (args == null || args.length == 0) {
                            data.clear();
                        } else {
                            @SuppressWarnings("unchecked")
                            Predicate<Node> predicate = (Predicate<Node>) args[0];
                            data.removeIf(predicate);
                        }
                        return null;
                    }
                    if (name.equals("add")) {
                        Node node = (Node) args[0];
                        if (data.contains(node)) return DataMutateResult.FAIL_ALREADY_HAS;
                        data.add(node);
                        return DataMutateResult.SUCCESS;
                    }
                    throw new UnsupportedOperationException(name);
                });
        return (PermissionHolder) Proxy.newProxyInstance(PermissionHolder.class.getClassLoader(),
                new Class<?>[]{PermissionHolder.class}, (proxy, method, args) -> {
                    if (method.getName().equals("data")) return nodes;
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
