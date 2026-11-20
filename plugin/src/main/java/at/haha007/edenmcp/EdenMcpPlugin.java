package at.haha007.edenmcp;

import at.haha007.edenmcp.api.EdenMcpApi;
import at.haha007.edenmcp.api.EdenMcpApiAvailableEvent;
import at.haha007.edenmcp.core.audit.AuditLogger;
import at.haha007.edenmcp.core.auth.AccessKeyManager;
import at.haha007.edenmcp.core.auth.Principal;
import at.haha007.edenmcp.core.config.EdenConfig;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.core.hook.HookManager;
import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.core.policy.SecurityPolicy;
import at.haha007.edenmcp.core.tool.ToolRegistry;
import at.haha007.edenmcp.core.transport.McpHttpServer;
import at.haha007.edenmcp.core.util.ServerFileAccess;
import at.haha007.edenmcp.integration.EdenMcpApiImpl;
import at.haha007.edenmcp.hooks.luckperms.LuckPermsHook;
import at.haha007.edenmcp.hooks.spark.SparkHook;
import at.haha007.edenmcp.tools.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
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
    @Nullable private EdenMcpApiImpl api;
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
            HookContext hookContext = new HookContext(getServer(), this, this, getLogger());
            hookManager = new HookManager(registry, hookContext);

            // Known hooks assembled by plugin (server-authoritative)
            hookManager.registerKnownHook(new LuckPermsHook(), edenConfig.hookEnabled().getOrDefault("luckperms", true));
            hookManager.registerKnownHook(new SparkHook(), edenConfig.hookEnabled().getOrDefault("spark", true));

            // Core tools
            this.commandDispatchTool = new CommandDispatchTool(this, edenConfig.commandClassification(),
                    getLogger(), edenConfig.logVerbose(), edenConfig.tools());
            int listMaxEntries = edenConfig.tools().listMaxEntries();
            registry.register(new StatusTool(listMaxEntries));
            registry.register(new PluginsListTool(listMaxEntries));
            registry.register(new PlayersListTool(listMaxEntries));
            registry.register(new HooksListTool(hookManager, listMaxEntries));
            registry.register(new ModeGetTool(modeRef));
            registry.register(new ModeSetTool(modeRef, audit, m -> {
                if (httpServer != null) httpServer.broadcastModeChange(m);
            }, securityPolicy));
            ServerFileAccess serverFiles = new ServerFileAccess(Path.of("").toAbsolutePath());
            registry.register(new FileListTool(serverFiles, edenConfig.tools().filesMaxListEntries(),
                    edenConfig.tools().filesMaxListDepth()));
            registry.register(new FileReadTool(serverFiles, edenConfig.tools().filesMaxReadBytes()));
            registry.register(new FileWriteTool(serverFiles, edenConfig.tools().filesMaxWriteBytes(), audit));
            Path logsDir = resolveLogDir();
            boolean logsRequireModerate = edenConfig.policy().logsRequireModerate();
            registry.register(new LogsListTool(logsDir, logsRequireModerate, edenConfig.tools()));
            registry.register(new LogsReadTool(logsDir, logsRequireModerate, edenConfig.tools()));
            registry.register(new ChatSendTool(this, getLogger(), edenConfig.logVerbose(), edenConfig.tools()));
            registry.register(commandDispatchTool);

            // Config/state snapshots are no longer exposed; edenmcp.file.read can read
            // the server's config files directly, and mode/hooks have their own tools.

            hookManager.enableAvailableHooks(getServer());

            // Publish the public API so other plugins can register hooks at runtime.
            api = new EdenMcpApiImpl(hookManager, edenConfig, registry, hookManager.resources());
            getServer().getServicesManager().register(EdenMcpApi.class, api, this, ServicePriority.Normal);
            getServer().getPluginManager().registerEvents(new HookCleanupListener(), this);
            getServer().getPluginManager().callEvent(new EdenMcpApiAvailableEvent(api));

            httpServer = new McpHttpServer(edenConfig, keyManager, registry, hookManager, audit, getLogger(), modeRef, Map::of);
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
        if (api != null) {
            getServer().getServicesManager().unregister(EdenMcpApi.class, api);
        }
        if (hookManager != null) hookManager.stopAccepting();
        if (httpServer != null) {
            try { httpServer.stop(); } catch (Exception e) { getLogger().log(Level.WARNING, "Error stopping MCP server", e); }
            httpServer = null;
        }
        if (hookManager != null) {
            try { hookManager.disableAll(); } catch (Exception e) { getLogger().log(Level.WARNING, "Error disabling hooks", e); }
            hookManager = null;
        }
        api = null;
        getLogger().info("EdenMcp disabled.");
    }

    /** Removes a plugin's hooks even if the plugin forgot to close its registrations. */
    private final class HookCleanupListener implements Listener {
        @EventHandler
        public void onPluginDisable(PluginDisableEvent event) {
            if (hookManager != null) hookManager.unregisterAllFor(event.getPlugin());
        }
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

    private Path resolveLogDir() {
        // Common Paper/Spigot locations
        Path p = Path.of("logs");
        if (Files.isDirectory(p)) return p.toAbsolutePath();
        Path p2 = getServer().getWorldContainer().toPath().resolve("logs");
        if (Files.isDirectory(p2)) return p2.toAbsolutePath();
        return p.toAbsolutePath();
    }

}
