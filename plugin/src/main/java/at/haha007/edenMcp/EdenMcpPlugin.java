package at.haha007.edenMcp;

import at.haha007.edenMcp.core.audit.AuditLogger;
import at.haha007.edenMcp.core.auth.AccessKeyManager;
import at.haha007.edenMcp.core.auth.Principal;
import at.haha007.edenMcp.core.config.ConfigRedactor;
import at.haha007.edenMcp.core.config.EdenConfig;
import at.haha007.edenMcp.core.hook.HookContext;
import at.haha007.edenMcp.core.hook.HookManager;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.policy.SecurityPolicy;
import at.haha007.edenMcp.core.resource.McpResource;
import at.haha007.edenMcp.core.tool.ToolRegistry;
import at.haha007.edenMcp.core.transport.McpHttpServer;
import at.haha007.edenMcp.hooks.luckperms.LuckPermsHook;
import at.haha007.edenMcp.hooks.spark.SparkHook;
import at.haha007.edenMcp.tools.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class EdenMcpPlugin extends JavaPlugin {
    @Nullable private McpHttpServer httpServer;
    @Nullable private HookManager hookManager;
    @Nullable private AuditLogger audit;
    @Nullable private AccessKeyManager keyManager;
    @Nullable private CommandDispatchTool commandDispatchTool;
    private final AtomicReference<OperatingMode> modeRef = new AtomicReference<>(OperatingMode.SAFE);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration cfg = getConfig();
        EdenConfig edenConfig = EdenConfig.from(cfg, getLogger());

        if (!edenConfig.enabled()) {
            getLogger().info("EdenMcp is disabled in config.");
            return;
        }

        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("Could not create plugin data folder: " + getDataFolder());
            }

            this.audit = new AuditLogger(getLogger(), edenConfig.auditMaxValueLength());
            audit.setLogToolCalls(edenConfig.logToolCalls());
            audit.setLogModeChanges(edenConfig.logModeChanges());
            if (!edenConfig.logToolCalls() || !edenConfig.logModeChanges()) {
                getLogger().warning("[SECURITY] Audit logging is partially disabled; MCP activity will not be fully recorded.");
            }

            this.keyManager = new AccessKeyManager(
                    getDataFolder().toPath().resolve(".key"),
                    getLogger(),
                    edenConfig.auth().rotationGraceSeconds(),
                    edenConfig.auth().multiKeyDirectory().isBlank() ? null : Path.of(edenConfig.auth().multiKeyDirectory()));
            keyManager.loadOrCreate();

            SecurityPolicy securityPolicy = new SecurityPolicy(edenConfig.policy().allowUnsafeAtRuntime());

            modeRef.set(edenConfig.startupMode());
            getLogger().info("EdenMcp startup mode: " + modeRef.get());
            if (modeRef.get() == OperatingMode.UNSAFE) {
                getLogger().warning("[SECURITY] EdenMcp starting in UNSAFE mode.");
            }

            ToolRegistry registry = new ToolRegistry();
            HookContext hookContext = new HookContext(getServer(), this, getLogger(), audit);
            hookManager = new HookManager(registry, hookContext);

            // Known hooks assembled by plugin (server-authoritative)
            hookManager.registerKnownHook(new LuckPermsHook(), edenConfig.hookEnabled().getOrDefault("luckperms", true));
            hookManager.registerKnownHook(new SparkHook(), edenConfig.hookEnabled().getOrDefault("spark", true));

            // Core tools
            this.commandDispatchTool = new CommandDispatchTool(this, edenConfig.commandClassification(),
                    getLogger(), edenConfig.logVerbose(), edenConfig.tools());
            registry.register(new StatusTool());
            registry.register(new PluginsListTool());
            registry.register(new PlayersListTool());
            registry.register(new HooksListTool(hookManager));
            registry.register(new ModeGetTool(modeRef));
            registry.register(new ModeSetTool(modeRef, audit, m -> {
                if (httpServer != null) httpServer.broadcastModeChange(m);
            }, securityPolicy));
            registry.register(new ConfigGetTool(this::buildConfigSnapshot));
            registry.register(new LogsReadTool(resolveLogPath(), edenConfig.policy().logsRequireModerate(), edenConfig.tools()));
            registry.register(new ChatSendTool(this, getLogger(), edenConfig.logVerbose(), edenConfig.tools()));
            registry.register(commandDispatchTool);

            // Core read-only resource: same redacted snapshot as eden.config.get.
            hookManager.resources().register(McpResource.text(
                    "eden://config/snapshot", "EdenMcp configuration snapshot",
                    "Redacted EdenMcp configuration and runtime state (JSON).", "application/json",
                    () -> ConfigRedactor.redact(buildConfigSnapshot(), "")));

            hookManager.enableAvailableHooks(getServer());

            httpServer = new McpHttpServer(edenConfig, keyManager, registry, hookManager, audit, getLogger(), modeRef, this::buildConfigSnapshot);
            httpServer.start(getDataFolder().toPath());

            if (edenConfig.transport().bind() != null && !edenConfig.transport().bind().equals("127.0.0.1") && !edenConfig.transport().bind().equalsIgnoreCase("localhost")) {
                getLogger().warning("MCP server bound to " + edenConfig.transport().bind() + " (remote-capable). Ensure access key and TLS/network controls are properly configured.");
            }

            registerCommands();
            getLogger().info("EdenMcp enabled.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable EdenMcp", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            try { httpServer.stop(); } catch (Exception e) { getLogger().log(Level.WARNING, "Error stopping MCP server", e); }
            httpServer = null;
        }
        if (hookManager != null) {
            try { hookManager.disableAll(); } catch (Exception e) { getLogger().log(Level.WARNING, "Error disabling hooks", e); }
            hookManager = null;
        }
        getLogger().info("EdenMcp disabled.");
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var root = Commands.literal("mcp")
                    .requires(src -> src.getSender().hasPermission("edenmcp.admin"))
                    .then(Commands.literal("mode")
                            .then(Commands.literal("get").executes(ctx -> {
                                ctx.getSource().getSender().sendMessage(Component.text("EdenMcp mode: " + modeRef.get().name().toLowerCase(Locale.ROOT)));
                                return Command.SINGLE_SUCCESS;
                            }))
                            .then(Commands.literal("set")
                                    .then(Commands.argument("mode", StringArgumentType.word())
                                            .suggests((ignored, b) -> {
                                                b.suggest("safe");
                                                b.suggest("moderate");
                                                b.suggest("unsafe");
                                                return b.buildFuture();
                                            })
                                            .executes(ctx -> {
                                                String raw = StringArgumentType.getString(ctx, "mode");
                                                OperatingMode next = OperatingMode.parse(raw, null);
                                                if (next == null) {
                                                    ctx.getSource().getSender().sendMessage(Component.text("Invalid mode: " + raw));
                                                    return 0;
                                                }
                                                OperatingMode prev = modeRef.getAndSet(next);
                                                if (audit != null) audit.modeChange("command:" + ctx.getSource().getSender().getName(), prev.name(), next.name());
                                                if (httpServer != null) httpServer.broadcastModeChange(next);
                                                ctx.getSource().getSender().sendMessage(Component.text("EdenMcp mode changed: " + prev.name().toLowerCase(Locale.ROOT) + " -> " + next.name().toLowerCase(Locale.ROOT)));
                                                return Command.SINGLE_SUCCESS;
                                            }))))
                    .then(Commands.literal("key")
                            .then(Commands.literal("rotate").executes(ctx -> {
                                try {
                                    var result = Objects.requireNonNull(keyManager, "keyManager").rotate();
                                    int closed = httpServer != null
                                            ? httpServer.invalidateSessionsForPrincipal(result.invalidatedPrincipalId()) : 0;
                                    if (audit != null) {
                                        audit.keyRotate("command:" + ctx.getSource().getSender().getName(), Principal.PRIMARY_ID);
                                    }
                                    ctx.getSource().getSender().sendMessage(Component.text(
                                            "MCP access key rotated. New key: "
                                                    + getDataFolder().toPath().resolve(".key").toAbsolutePath()
                                                    + (result.graceSeconds() > 0
                                                            ? " Previous key valid for " + result.graceSeconds()
                                                                    + "s (in-memory grace only)." : "")));
                                    ctx.getSource().getSender().sendMessage(Component.text(
                                            "Closed " + closed + " existing MCP session(s). Update clients and verify the TLS fingerprint."));
                                    return Command.SINGLE_SUCCESS;
                                } catch (Exception e) {
                                    getLogger().log(Level.WARNING, "Failed to rotate MCP access key", e);
                                    ctx.getSource().getSender().sendMessage(Component.text(
                                            "Failed to rotate MCP access key: " + e.getMessage()));
                                    return 0;
                                }
                            })))
                    .then(Commands.literal("reload").executes(ctx -> {
                        String result = reloadPluginConfig();
                        ctx.getSource().getSender().sendMessage(Component.text(result));
                        return Command.SINGLE_SUCCESS;
                    }));
            event.registrar().register(root.build(), "EdenMcp administration");
        });
    }

    private String reloadPluginConfig() {
        try {
            reloadConfig();
            EdenConfig edenConfig = EdenConfig.from(getConfig(), getLogger());
            if (audit != null) {
                audit.setLogToolCalls(edenConfig.logToolCalls());
                audit.setLogModeChanges(edenConfig.logModeChanges());
            }
            if (commandDispatchTool != null) {
                commandDispatchTool.updateClassification(edenConfig.commandClassification());
            }
            OperatingMode prev = modeRef.getAndSet(edenConfig.startupMode());
            if (prev != edenConfig.startupMode() && audit != null) {
                audit.modeChange("config-reload", prev.name(), edenConfig.startupMode().name());
            }
            if (httpServer != null) httpServer.broadcastModeChange(modeRef.get());
            return "EdenMcp config reloaded (mode=" + modeRef.get().name().toLowerCase(Locale.ROOT)
                    + ", audit/command classification updated; transport/hooks require restart).";
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to reload EdenMcp config", e);
            return "Failed to reload EdenMcp config: " + e.getMessage();
        }
    }

    private Path resolveLogPath() {
        // Common Paper/Spigot locations
        Path p = Path.of("logs", "latest.log");
        if (Files.exists(p)) return p.toAbsolutePath();
        Path p2 = getServer().getWorldContainer().toPath().resolve("logs").resolve("latest.log");
        if (Files.exists(p2)) return p2.toAbsolutePath();
        return p.toAbsolutePath();
    }

    private Map<String, Object> buildConfigSnapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        // Full config exposure with redaction applied by tool
        FileConfiguration cfg = getConfig();
        root.put("mcp", sectionToMap(cfg.getConfigurationSection("mcp")));
        root.put("hooks", sectionToMap(cfg.getConfigurationSection("hooks")));
        root.put("commands", sectionToMap(cfg.getConfigurationSection("commands")));
        root.put("audit", sectionToMap(cfg.getConfigurationSection("audit")));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("activeMode", modeRef.get().name().toLowerCase());
        state.put("hooks", hookManager != null ? hookManager.describeHooks() : java.util.List.of());
        root.put("state", state);
        return root;
    }

    private @Nullable Object sectionToMap(@Nullable Object obj) {
        return switch (obj) {
            case null -> null;
            case org.bukkit.configuration.ConfigurationSection sec -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String k : sec.getKeys(false)) {
                    Object v = sec.get(k);
                    m.put(k, sectionToMap(v));
                }
                // include values that might not be sections but lists/maps
                yield m;
            }
            case java.util.List<?> list -> {
                java.util.List<Object> out = new java.util.ArrayList<>();
                for (Object i : list) out.add(sectionToMap(i));
                yield out;
            }
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), sectionToMap(e.getValue()));
                yield out;
            }
            default -> obj;
        };
    }
}
