package at.haha007.edenmcp.integration;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.EdenMcpApi;
import at.haha007.edenmcp.api.Hook;
import at.haha007.edenmcp.api.HookContribution;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.api.HookInfo;
import at.haha007.edenmcp.api.HookRegistration;
import at.haha007.edenmcp.api.HookRegistrationException;
import at.haha007.edenmcp.api.McpResource;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.Registration;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.config.EdenConfig;
import at.haha007.edenmcp.core.config.EdenConfig.HookOverride;
import at.haha007.edenmcp.core.hook.HookManager;
import at.haha007.edenmcp.core.resource.ResourceRegistry;
import at.haha007.edenmcp.core.tool.ToolRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Implements the published service. All methods are main-thread only. */
public final class EdenMcpApiImpl implements EdenMcpApi {
    private static final Pattern HOOK_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern TOOL_NAME = Pattern.compile("edenmcp\\.[a-z0-9][a-z0-9._-]{0,190}");
    private static final List<String> RESERVED_IDS = List.of(
            "edenmcp", "core", "system", "mcp", "config", "server", "players", "plugins",
            "logs", "mode", "hooks", "chat", "command", "tools", "luckperms", "spark");

    private final AtomicInteger externalSeq = new AtomicInteger();
    private final HookManager manager;
    private final EdenConfig config;
    private final ToolRegistry tools;
    private final ResourceRegistry resources;

    public EdenMcpApiImpl(HookManager manager, EdenConfig config, ToolRegistry tools, ResourceRegistry resources) {
        this.manager = manager;
        this.config = config;
        this.tools = tools;
        this.resources = resources;
    }

    @Override
    public HookRegistration registerHook(Plugin owner, Hook hook) {
        checkMainThread();
        checkAccepting();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(hook, "hook");
        validateHookId(hook.id());
        if (hooks().stream().anyMatch(info -> info.id().equals(hook.id()))) {
            throw new HookRegistrationException(HookRegistrationException.Reason.DUPLICATE_ID,
                    "A hook with id '" + hook.id() + "' is already registered");
        }
        return manager.register(owner, hook, policyFor(hook.id()), "external");
    }

    @Override
    public Registration registerTool(Plugin owner, McpTool tool) {
        checkMainThread();
        checkAccepting();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(tool, "tool");
        if (tool.name() == null || tool.name().isBlank()) {
            throw new HookRegistrationException(HookRegistrationException.Reason.INVALID_NAME,
                    "Tool name must not be blank");
        }
        String hookId = generatedHookId(owner, "tools");
        String prefix = "edenmcp." + hookId + ".";
        McpTool wrapped = prefixed(tool, prefix);
        Hook hook = new Hook() {
            @Override public String id() { return hookId; }
            @Override public String displayName() { return owner.getName() + " tools"; }
            @Override public HookContribution contribute(HookContext ctx) {
                return HookContribution.tools(wrapped);
            }
        };
        HookRegistration registration = manager.register(owner, hook, policyFor(hookId), "tool");
        return delegating(owner, wrapped.name(), registration);
    }

    @Override
    public Registration registerResource(Plugin owner, McpResource resource) {
        checkMainThread();
        checkAccepting();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resource, "resource");
        String hookId = generatedHookId(owner, "resources");
        String uri = namespaceUri(resource.uri(), hookId);
        McpResource wrapped = new McpResource(uri, resource.name(), resource.description(),
                resource.mimeType(), resource.metadata(), resource.reader());
        Hook hook = new Hook() {
            @Override public String id() { return hookId; }
            @Override public String displayName() { return owner.getName() + " resources"; }
            @Override public HookContribution contribute(HookContext ctx) {
                return HookContribution.of(List.of(), List.of(wrapped));
            }
        };
        HookRegistration registration = manager.register(owner, hook, policyFor(hookId), "resource");
        return delegating(owner, uri, registration);
    }

    @Override
    public List<HookInfo> hooks() {
        return manager.hookInfos();
    }

    @Override
    public boolean isAcceptingRegistrations() {
        return manager.isAccepting();
    }

    private HookManager.HookPolicy policyFor(String hookId) {
        EdenConfig.ExternalHookConfig external = config.externalHooks();
        HookOverride override = external.overrides().get(hookId);
        boolean enabled = override != null ? override.enabled() : external.defaultEnabled();
        CapabilityLevel level = override != null ? override.maxCapabilityLevel() : external.maxCapabilityLevel();
        boolean dangerous = override != null
                ? override.allowDangerousSideEffects() : external.allowDangerousSideEffects();

        String toolPrefix = "edenmcp." + hookId + ".";
        String uriPrefix = "edenmcp://" + hookId + "/";
        Predicate<McpTool> toolValidator = t -> t.name() != null
                && t.name().startsWith(toolPrefix)
                && TOOL_NAME.matcher(t.name()).matches()
                && tools.get(t.name()).isEmpty();
        Predicate<McpResource> resourceValidator = r -> r.uri().startsWith(uriPrefix)
                && resources.get(r.uri()).isEmpty();
        return HookManager.HookPolicy.builtin(enabled).withLimits(enabled, level, dangerous,
                toolValidator, resourceValidator);
    }

    private static void validateHookId(String id) {
        if (id == null || !HOOK_ID.matcher(id).matches()) {
            throw new HookRegistrationException(HookRegistrationException.Reason.INVALID_ID,
                    "Hook id must match [a-z0-9][a-z0-9._-]{0,63}: " + id);
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (RESERVED_IDS.contains(lower) || lower.startsWith("edenmcp.") || lower.startsWith("ext.")) {
            throw new HookRegistrationException(HookRegistrationException.Reason.INVALID_ID,
                    "Hook id '" + id + "' is reserved");
        }
    }

    private static void checkMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("EdenMcp API calls must be made on the main server thread");
        }
    }

    private void checkAccepting() {
        if (!manager.isAccepting()) {
            throw new HookRegistrationException(HookRegistrationException.Reason.API_UNAVAILABLE,
                    "EdenMcp is shutting down");
        }
    }

    /** Unique per-plugin id for convenience registrations; avoids collisions on repeated calls. */
    private String generatedHookId(Plugin owner, String kind) {
        String base = "ext." + sanitize(owner.getName()) + "." + kind;
        if (base.length() > 52) base = base.substring(0, 52);
        base = base.replaceAll("[-.]+$", "");
        return base + "-" + externalSeq.incrementAndGet();
    }

    private static String sanitize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "-");
        s = s.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "plugin" : s;
    }

    private static String namespaceUri(String raw, String hookId) {
        String path = raw == null ? "unnamed" : raw;
        int scheme = path.indexOf("://");
        if (scheme >= 0) path = path.substring(scheme + 3);
        path = path.replaceAll("^/+", "").replaceAll("[^a-zA-Z0-9._/-]", "_");
        while (path.contains("..")) path = path.replace("..", ".");
        if (path.isBlank()) path = "unnamed";
        return "edenmcp://" + hookId + "/" + path;
    }

    private static String sanitizeToolName(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        s = s.replaceAll("-+", "-").replaceAll("^[-.]+|[-.]+$", "");
        return s.isEmpty() ? "tool" : s;
    }

    private static McpTool prefixed(McpTool delegate, String prefix) {
        String localName = sanitizeToolName(delegate.name());
        return new McpTool() {
            @Override public String name() { return prefix + localName; }
            @Override public String description() { return delegate.description(); }
            @Override public CapabilityLevel level() { return delegate.level(); }
            @Override public at.haha007.edenmcp.api.SideEffects sideEffects() { return delegate.sideEffects(); }
            @Override public List<String> tags() { return delegate.tags(); }
            @Override public Map<String, Object> inputSchema() { return delegate.inputSchema(); }
            @Override public ToolResult execute(ToolContext context) throws Exception { return delegate.execute(context); }
            @Override public boolean exposed() { return delegate.exposed(); }
        };
    }

    private static Registration delegating(Plugin owner, String id, HookRegistration delegate) {
        return new Registration() {
            @Override public Plugin owner() { return owner; }
            @Override public String id() { return id; }
            @Override public RegistrationState state() { return delegate.state(); }
            @Override public boolean unregister() { return delegate.unregister(); }
        };
    }
}
