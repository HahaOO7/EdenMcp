package at.haha007.edenMcp.core.hook;

import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolRegistry;
import org.bukkit.Server;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HookManager {
    private final ToolRegistry registry;
    private final HookContext context;
    private final Logger logger;
    private final Map<String, HookState> states = new ConcurrentHashMap<>();

    public HookManager(ToolRegistry registry, HookContext context) {
        this.registry = registry;
        this.context = context;
        this.logger = context.logger();
    }

    public void registerKnownHook(EdenHook hook, boolean enabledInConfig) {
        states.put(hook.id(), new HookState(hook, enabledInConfig, false, null));
    }

    public void enableAvailableHooks(Server server) {
        for (HookState state : states.values()) {
            tryEnable(state, server);
        }
    }

    private void tryEnable(HookState state, Server server) {
        EdenHook hook = state.hook();
        if (!state.enabledInConfig()) return;
        if (!hook.isAvailable(server)) {
            states.put(hook.id(), state.withAvailable(false).withEnabled(false));
            logger.info("Hook '" + hook.id() + "' not available; skipping.");
            return;
        }
        HookContribution contribution = null;
        boolean enableAttempted = false;
        List<McpTool> registered = new ArrayList<>();
        try {
            contribution = hook.contribute(context);
            // Enable first: a hook that fails to initialise must never expose its tools.
            enableAttempted = true;
            contribution.onEnable().ifPresent(Runnable::run);
            for (McpTool tool : contribution.tools()) {
                registered.add(tool);
                registry.register(tool);
            }
            states.put(hook.id(), state.withAvailable(true).withEnabled(true).withContribution(contribution));
            logger.info("Hook '" + hook.id() + "' enabled with " + contribution.tools().size() + " tool(s).");
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Failed to enable hook '" + hook.id() + "'", t);
            // Roll back every tool this hook managed to register before it failed.
            for (int i = registered.size() - 1; i >= 0; i--) {
                try {
                    registry.unregisterIfRegistered(registered.get(i));
                } catch (Throwable rollbackFailure) {
                    logger.log(Level.WARNING, "Failed to roll back tool of hook '" + hook.id() + "'", rollbackFailure);
                }
            }
            if (enableAttempted && contribution != null) {
                try {
                    contribution.onDisable().ifPresent(Runnable::run);
                } catch (Throwable disableFailure) {
                    logger.log(Level.WARNING, "Error disabling failed hook '" + hook.id() + "'", disableFailure);
                }
            }
            states.put(hook.id(), state.withAvailable(true).withEnabled(false));
        }
    }

    public void disableAll() {
        for (HookState state : states.values()) {
            if (state.enabled() && state.contribution() != null) {
                try {
                    state.contribution().onDisable().ifPresent(Runnable::run);
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Error disabling hook '" + state.hook().id() + "'", t);
                }
                registry.unregisterByPrefix("eden." + state.hook().id() + ".");
                // also remove exact contributed tool names
                state.contribution().tools().forEach(t -> registry.unregister(t.name()));
            }
        }
    }

    public List<Map<String, Object>> describeHooks() {
        return states.values().stream()
                .sorted(Comparator.comparing(s -> s.hook().id()))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.hook().id());
                    m.put("enabledInConfig", s.enabledInConfig());
                    m.put("available", s.available());
                    m.put("enabled", s.enabled());
                    m.put("tools", s.enabled() && s.contribution() != null
                            ? s.contribution().tools().stream().map(McpTool::name).toList() : List.of());
                    return m;
                })
                .toList();
    }

    private record HookState(EdenHook hook, boolean enabledInConfig, boolean available, boolean enabled, HookContribution contribution) {
        HookState(EdenHook hook, boolean enabledInConfig, boolean available, HookContribution contribution) {
            this(hook, enabledInConfig, available, contribution != null, contribution);
        }
        HookState withAvailable(boolean a) { return new HookState(hook, enabledInConfig, a, enabled, contribution); }
        HookState withEnabled(boolean e) { return new HookState(hook, enabledInConfig, available, e, contribution); }
        HookState withContribution(HookContribution c) { return new HookState(hook, enabledInConfig, available, enabled, c); }
    }
}
