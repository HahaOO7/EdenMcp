package at.haha007.edenmcp.core.hook;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.Hook;
import at.haha007.edenmcp.api.HookContribution;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.api.HookInfo;
import at.haha007.edenmcp.api.HookRegistration;
import at.haha007.edenmcp.api.McpResource;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.Registration;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.core.resource.ResourceRegistry;
import at.haha007.edenmcp.core.tool.ToolRegistry;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the lifecycle of every hook (built-in and third-party).
 *
 * <p>All mutating methods are expected to be called on the main server thread.
 */
public final class HookManager {
    /** Hard upper bound for nested name arrays returned by {@link #describeHooks()}. */
    public static final int MAX_DESCRIBED_LIST_ITEMS = 100;

    private final ToolRegistry registry;
    private final ResourceRegistry resources;
    private final HookContext context;
    private final Logger logger;
    private final Map<String, HookState> states = new ConcurrentHashMap<>();

    private @Nullable Server server;
    private volatile boolean started;
    private volatile boolean accepting = true;

    /** Per-hook limits applied by EdenMcp; hooks cannot widen these themselves. */
    public record HookPolicy(boolean enabledInConfig, CapabilityLevel maxLevel,
                             boolean allowDangerous, boolean enforceLimits,
                             java.util.function.Predicate<McpTool> toolValidator,
                             java.util.function.Predicate<McpResource> resourceValidator) {
        public static HookPolicy builtin(boolean enabled) {
            return new HookPolicy(enabled, CapabilityLevel.UNSAFE_ACTION, true, false,
                    Objects::nonNull, Objects::nonNull);
        }

        public HookPolicy withLimits(boolean enabled, CapabilityLevel level, boolean allowDangerous,
                                     java.util.function.Predicate<McpTool> toolValidator,
                                     java.util.function.Predicate<McpResource> resourceValidator) {
            return new HookPolicy(enabled, level, allowDangerous, true, toolValidator, resourceValidator);
        }
    }

    public HookManager(ToolRegistry registry, HookContext context) {
        this(registry, new ResourceRegistry(), context);
    }

    public HookManager(ToolRegistry registry, ResourceRegistry resources, HookContext context) {
        this.registry = registry;
        this.resources = resources;
        this.context = context;
        this.logger = context.logger();
    }

    /** Registry backed by every enabled hook; also used for core-contributed resources. */
    public ResourceRegistry resources() {
        return resources;
    }

    /** Disables registration during EdenMcp shutdown. */
    public void stopAccepting() {
        accepting = false;
    }

    public boolean isAccepting() {
        return accepting;
    }

    /** Register a hook. Immediately enables it if startup has already completed. */
    public HookRegistration register(@Nullable Plugin owner, Hook hook, HookPolicy policy, String source) {
        Objects.requireNonNull(hook, "hook");
        if (!accepting) {
            throw new IllegalStateException("EdenMcp is shutting down; hook registration is closed");
        }
        String id = hook.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Hook id must not be blank");
        }
        if (states.containsKey(id)) {
            throw new IllegalStateException("A hook with id '" + id + "' is already registered");
        }

        HookState state = new HookState(hook, owner, source, policy);
        states.put(id, state);
        HookRegistrationImpl handle = new HookRegistrationImpl(id, hook, owner);
        states.put(id, state.withHandle(handle));

        logger.info("[AUDIT] hook-register id=" + id + " owner="
                + (owner == null ? "core" : owner.getName()) + " source=" + source);
        if (started) {
            enable(id);
        }
        return handle;
    }

    /** Backwards-compatible path used by built-in hooks and older tests. */
    public HookRegistration registerKnownHook(Hook hook, boolean enabledInConfig) {
        return register(context.edenMcp(), hook, HookPolicy.builtin(enabledInConfig), "builtin");
    }

    /**
     * Hooks receive a context scoped to their owner, so a third-party hook sees its own
     * plugin (not EdenMcp) in {@link HookContext#owner()}.
     */
    private HookContext contextFor(HookState state) {
        Server effectiveServer = server != null ? server : context.server();
        Plugin owner = state.owner() != null ? state.owner() : context.edenMcp();
        return new HookContext(effectiveServer, owner, context.edenMcp(), logger);
    }

    public void enableAvailableHooks(Server server) {
        this.server = server;
        started = true;
        for (String id : states.keySet()) {
            enable(id);
        }
    }

    private void enable(String id) {
        HookState state = states.get(id);
        if (state == null || !state.policy().enabledInConfig()) return;

        Hook hook = state.hook();
        if (!hook.isAvailable(server)) {
            states.put(id, state.withAvailable(false).withEnabled(false));
            logger.info("Hook '" + id + "' not available; skipping.");
            return;
        }

        HookContribution contribution;
        boolean enableAttempted = false;
        List<McpTool> registeredTools = new ArrayList<>();
        List<McpResource> registeredResources = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            contribution = hook.contribute(contextFor(state));
            // Enable first: a hook that fails to initialise must never expose its contributions.
            enableAttempted = true;
            hook.onEnable();
            if (contribution != null) {
                for (McpTool tool : contribution.tools()) {
                    if (tool == null) {
                        skipped.add("<null tool>");
                        continue;
                    }
                    if (!allowed(tool, state.policy()) || !state.policy().toolValidator().test(tool)) {
                        skipped.add(tool.name());
                        logger.warning("[SECURITY] Hook '" + id + "' tool '" + tool.name()
                                + "' is not permitted by server policy or namespacing rules; skipping.");
                        continue;
                    }
                    registeredTools.add(tool);
                    registry.register(tool);
                }
                for (McpResource resource : contribution.resources()) {
                    if (resource == null) {
                        skipped.add("<null resource>");
                        continue;
                    }
                    if (!state.policy().resourceValidator().test(resource)) {
                        skipped.add(resource.uri());
                        logger.warning("[SECURITY] Hook '" + id + "' resource '" + resource.uri()
                                + "' is not permitted by server policy or namespacing rules; skipping.");
                        continue;
                    }
                    registeredResources.add(resource);
                    resources.register(resource);
                }
            }
            int tools = contribution == null ? 0 : contribution.tools().size();
            int res = contribution == null ? 0 : contribution.resources().size();
            states.put(id, state.withAvailable(true).withEnabled(true)
                    .withContribution(contribution).withSkipped(skipped).withLastError(null));
            logger.info("Hook '" + id + "' enabled with " + tools + " tool(s) and "
                    + res + " resource(s)." + (skipped.isEmpty() ? "" : " Skipped: " + skipped));
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Failed to enable hook '" + id + "'", t);
            // Roll back every contribution this hook managed to register before it failed.
            for (int i = registeredResources.size() - 1; i >= 0; i--) {
                try {
                    resources.unregisterIfRegistered(registeredResources.get(i));
                } catch (Throwable rollbackFailure) {
                    logger.log(Level.WARNING, "Failed to roll back resource of hook '" + id + "'", rollbackFailure);
                }
            }
            for (int i = registeredTools.size() - 1; i >= 0; i--) {
                try {
                    registry.unregisterIfRegistered(registeredTools.get(i));
                } catch (Throwable rollbackFailure) {
                    logger.log(Level.WARNING, "Failed to roll back tool of hook '" + id + "'", rollbackFailure);
                }
            }
            if (enableAttempted) {
                try {
                    hook.onDisable();
                } catch (Throwable disableFailure) {
                    logger.log(Level.WARNING, "Error disabling failed hook '" + id + "'", disableFailure);
                }
            }
            states.put(id, state.withAvailable(true).withEnabled(false).withContribution(null)
                    .withSkipped(List.of()).withLastError(t.toString()));
        }
    }

    private static boolean allowed(McpTool tool, HookPolicy policy) {
        if (!policy.enforceLimits()) return true;
        if (tool.level() != null && tool.level().ordinal() > policy.maxLevel().ordinal()) return false;
        return tool.sideEffects() != SideEffects.DANGEROUS || policy.allowDangerous();
    }

    private void disable(String id) {
        HookState state = states.get(id);
        if (state == null) return;
        if (state.enabled() && state.contribution() != null) {
            try {
                state.hook().onDisable();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Error disabling hook '" + id + "'", t);
            }
            unregisterContributions(state);
        }
        states.put(id, state.withEnabled(false).withContribution(null));
    }

    private void unregisterContributions(HookState state) {
        String id = state.hook().id();
        registry.unregisterByPrefix("edenmcp." + id + ".");
        resources.unregisterByPrefix("edenmcp://" + id + "/");
        if (state.contribution() != null) {
            // Prefix removal covers conforming names; exact-instance removal also covers
            // any legacy contribution without risking removal of another source's entry.
            state.contribution().tools().forEach(registry::unregisterIfRegistered);
            state.contribution().resources().forEach(resources::unregisterIfRegistered);
        }
    }

    public void disableAll() {
        for (String id : states.keySet()) disable(id);
    }

    /** Unregister every hook owned by a plugin that is being disabled. */
    public void unregisterAllFor(@Nullable Plugin owner) {
        if (owner == null) return;
        for (String id : List.copyOf(states.keySet())) {
            HookState state = states.get(id);
            if (state != null && state.owner() == owner) unregister(id, owner);
        }
    }

    boolean unregister(String id, @Nullable Plugin expectedOwner) {
        HookState state = states.get(id);
        if (state == null) return false;
        if (expectedOwner != null && state.owner() != null && state.owner() != expectedOwner) {
            throw new IllegalStateException("Hook '" + id + "' belongs to another plugin");
        }
        disable(id);
        states.remove(id);
        logger.info("[AUDIT] hook-unregister id=" + id);
        return true;
    }

    public List<HookInfo> hookInfos() {
        return states.values().stream()
                .sorted(Comparator.comparing(s -> s.hook().id()))
                .map(s -> new HookInfo(
                        s.hook().id(),
                        s.hook().displayName(),
                        s.source(),
                        s.owner(),
                        s.policy().enabledInConfig(),
                        s.available(),
                        s.enabled(),
                        s.enabled() && s.contribution() != null
                                ? s.contribution().tools().stream().map(McpTool::name).toList() : List.of(),
                        s.enabled() && s.contribution() != null
                                ? s.contribution().resources().stream().map(McpResource::uri).toList() : List.of(),
                        s.skipped(),
                        s.lastError()))
                .toList();
    }

    /** Descriptors with nested name arrays capped at {@link #MAX_DESCRIBED_LIST_ITEMS}. */
    public List<Map<String, Object>> describeHooks() {
        return describeHooks(MAX_DESCRIBED_LIST_ITEMS);
    }

    /**
     * Descriptors for {@code edenmcp.hooks.list}. Each nested name array ({@code tools},
     * {@code resources}, {@code skipped}) is bounded by {@code maxItemsPerList}, which can
     * never exceed the hard cap. Truncation is reported via sibling {@code <name>Count},
     * {@code <name>Total} and {@code <name>HasMore} entries; original keys stay unchanged.
     */
    public List<Map<String, Object>> describeHooks(int maxItemsPerList) {
        int limit = Math.clamp(maxItemsPerList, 1, MAX_DESCRIBED_LIST_ITEMS);
        return hookInfos().stream().map(info -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", info.id());
            m.put("displayName", info.displayName());
            m.put("source", info.source());
            if (info.owner() != null) m.put("owner", info.owner().getName());
            m.put("enabledInConfig", info.enabledInConfig());
            m.put("available", info.available());
            m.put("enabled", info.enabled());
            putBoundedNames(m, "tools", info.tools(), limit);
            putBoundedNames(m, "resources", info.resources(), limit);
            putBoundedNames(m, "skipped", info.skipped(), limit);
            if (info.lastError() != null) m.put("lastError", info.lastError());
            return m;
        }).toList();
    }

    private static void putBoundedNames(Map<String, Object> target, String key,
                                        List<String> names, int limit) {
        List<String> visible = names.size() > limit ? names.subList(0, limit) : names;
        target.put(key, visible);
        target.put(key + "Count", visible.size());
        target.put(key + "Total", names.size());
        target.put(key + "HasMore", names.size() > visible.size());
    }

    private final class HookRegistrationImpl implements HookRegistration {
        private final String id;
        private final Hook hook;
        private final @Nullable Plugin owner;

        private HookRegistrationImpl(String id, Hook hook, @Nullable Plugin owner) {
            this.id = id;
            this.hook = hook;
            this.owner = owner;
        }

        @Override public Plugin owner() { return owner; }
        @Override public String id() { return id; }
        @Override public Hook hook() { return hook; }

        @Override public Registration.RegistrationState state() {
            HookState s = states.get(id);
            if (s == null) return Registration.RegistrationState.UNREGISTERED;
            if (!s.enabled() && s.lastError() != null) return Registration.RegistrationState.FAILED;
            return s.enabled() ? Registration.RegistrationState.ENABLED : Registration.RegistrationState.DISABLED;
        }

        @Override public HookInfo info() {
            HookState s = states.get(id);
            if (s == null) return null;
            return hookInfos().stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
        }

        @Override public void setEnabled(boolean enabled) {
            logger.info("[AUDIT] hook-set-enabled id=" + id + " enabled=" + enabled);
            if (enabled) enable(id);
            else disable(id);
        }

        @Override public boolean isEnabled() {
            HookState s = states.get(id);
            return s != null && s.enabled();
        }

        @Override public boolean unregister() {
            return HookManager.this.unregister(id, owner);
        }
    }

    private record HookState(Hook hook, @Nullable Plugin owner, String source, HookPolicy policy,
                             boolean available, boolean enabled,
                             @Nullable HookContribution contribution, List<String> skipped,
                             @Nullable String lastError, @Nullable HookRegistration handle) {
        HookState(Hook hook, @Nullable Plugin owner, String source, HookPolicy policy) {
            this(hook, owner, source, policy, false, false, null, List.of(), null, null);
        }

        HookState withAvailable(boolean a) { return new HookState(hook, owner, source, policy, a, enabled, contribution, skipped, lastError, handle); }
        HookState withEnabled(boolean e) { return new HookState(hook, owner, source, policy, available, e, contribution, skipped, lastError, handle); }
        HookState withContribution(HookContribution c) { return new HookState(hook, owner, source, policy, available, enabled, c, skipped, lastError, handle); }
        HookState withSkipped(List<String> s) { return new HookState(hook, owner, source, policy, available, enabled, contribution, List.copyOf(s), lastError, handle); }
        HookState withLastError(String e) { return new HookState(hook, owner, source, policy, available, enabled, contribution, skipped, e, handle); }
        HookState withHandle(HookRegistration h) { return new HookState(hook, owner, source, policy, available, enabled, contribution, skipped, lastError, h); }
    }
}
