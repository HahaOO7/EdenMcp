package at.haha007.edenMcp.core.config;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.mode.OperatingMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public record EdenConfig(
        boolean enabled,
        OperatingMode startupMode,
        TransportConfig transport,
        AuthConfig auth,
        PolicyConfig policy,
        ToolConfig tools,
        boolean logToolCalls,
        boolean logModeChanges,
        boolean logVerbose,
        int auditMaxValueLength,
        Map<String, Boolean> hookEnabled,
        CommandClassification commandClassification
) {
    public record TransportConfig(String type, String bind, int port,
                                  List<String> allowedHosts, List<String> allowedOrigins,
                                  int maxBodyBytes, int maxBatchSize, int maxSseClients,
                                  int sessionIdleSeconds, int maxSessions,
                                  int threadPoolCoreSize, int threadPoolMaxSize,
                                  int threadPoolKeepAliveSeconds, int threadPoolQueueCapacity,
                                  int shutdownGraceSeconds, int sseKeepaliveMs,
                                  int sessionPruneIntervalMs, int corsMaxAgeSeconds,
                                  int authFailurePruneThreshold, int readBufferBytes,
                                  TlsConfig tls) {}

    public record TlsConfig(boolean enabled, String mode, String certPath, String keyPath,
                            String keystorePath, String keystorePassword,
                            boolean requireClientCert, String truststorePath, String truststorePassword,
                            int selfSignedKeySize, int selfSignedValidityDays) {}

    public record AuthConfig(boolean allowLegacyHeader, int maxFailures, int blockSeconds,
                             int rotationGraceSeconds, String multiKeyDirectory) {}

    public record PolicyConfig(boolean allowUnsafeAtRuntime, boolean logsRequireModerate) {}

    /** Runtime bounds for the built-in tools. Defaults match the shipped config.yml. */
    public record ToolConfig(long chatMinIntervalMs, int chatSyncTimeoutSeconds,
                             int commandDispatchTimeoutSeconds,
                             int logsDefaultLimit, int logsMaxLimit,
                             int logsMaxScanBytes, int logsMaxPatternLength) {
        public static ToolConfig defaults() {
            return new ToolConfig(250, 5, 10, 100, 500, 1024 * 1024, 200);
        }
    }

    public static EdenConfig from(FileConfiguration cfg, @Nullable Logger logger) {
        boolean enabled = cfg.getBoolean("mcp.enabled", true);
        OperatingMode mode = OperatingMode.parse(cfg.getString("mcp.mode", "safe"), OperatingMode.SAFE);

        String type = cfg.getString("mcp.transport.type", "http-sse");
        String bind = cfg.getString("mcp.transport.bind", "0.0.0.0");
        int port = cfg.getInt("mcp.transport.port", 8765);
        List<String> allowedHosts = List.copyOf(cfg.getStringList("mcp.transport.allowed-hosts"));
        List<String> allowedOrigins = List.copyOf(cfg.getStringList("mcp.transport.allowed-origins"));
        int maxBodyBytes = Math.max(1024, cfg.getInt("mcp.transport.max-body-bytes", 1024 * 1024));
        int maxBatchSize = Math.max(1, cfg.getInt("mcp.transport.max-batch-size", 20));
        int maxSseClients = Math.max(1, cfg.getInt("mcp.transport.max-sse-clients", 20));
        int sessionIdleSeconds = Math.max(60, cfg.getInt("mcp.transport.session-idle-seconds", 1800));
        int maxSessions = Math.max(1, cfg.getInt("mcp.transport.max-sessions", 100));

        int coreThreads = Math.max(1, cfg.getInt("mcp.transport.thread-pool-core-size", 8));
        int maxThreads = Math.max(coreThreads, cfg.getInt("mcp.transport.thread-pool-max-size", 32));
        int keepAliveSeconds = Math.max(1, cfg.getInt("mcp.transport.thread-pool-keep-alive-seconds", 60));
        int queueCapacity = Math.max(1, cfg.getInt("mcp.transport.thread-pool-queue-capacity", 256));
        int shutdownGraceSeconds = Math.max(0, cfg.getInt("mcp.transport.shutdown-grace-seconds", 1));
        int sseKeepaliveMs = Math.max(1000, cfg.getInt("mcp.transport.sse-keepalive-ms", 15000));
        int sessionPruneIntervalMs = Math.max(1000, cfg.getInt("mcp.transport.session-prune-interval-ms", 30000));
        int corsMaxAgeSeconds = Math.max(0, cfg.getInt("mcp.transport.cors-max-age-seconds", 600));
        int authFailurePruneThreshold = Math.max(1, cfg.getInt("mcp.transport.auth-failure-prune-threshold", 1000));
        int readBufferBytes = Math.max(256, cfg.getInt("mcp.transport.read-buffer-bytes", 8192));

        boolean tlsEnabled = cfg.getBoolean("mcp.transport.tls.enabled", true);
        String tlsMode = cfg.getString("mcp.transport.tls.mode", "self-signed");
        String certPath = cfg.getString("mcp.transport.tls.cert-path", "");
        String keyPath = cfg.getString("mcp.transport.tls.key-path", "");
        String ksPath = cfg.getString("mcp.transport.tls.keystore-path", "");
        String ksPass = cfg.getString("mcp.transport.tls.keystore-password", "");
        boolean requireClientCert = cfg.getBoolean("mcp.transport.tls.require-client-cert", false);
        String trustPath = cfg.getString("mcp.transport.tls.truststore-path", "");
        String trustPass = cfg.getString("mcp.transport.tls.truststore-password", "");
        // Keep the generated key strong even if an operator sets a low value.
        int selfSignedKeySize = Math.max(2048, cfg.getInt("mcp.transport.tls.self-signed-key-size", 2048));
        int selfSignedValidityDays = Math.max(1, cfg.getInt("mcp.transport.tls.self-signed-validity-days", 3650));

        TransportConfig transport = new TransportConfig(type, bind, port, allowedHosts, allowedOrigins,
                maxBodyBytes, maxBatchSize, maxSseClients, sessionIdleSeconds, maxSessions,
                coreThreads, maxThreads, keepAliveSeconds, queueCapacity, shutdownGraceSeconds,
                sseKeepaliveMs, sessionPruneIntervalMs, corsMaxAgeSeconds, authFailurePruneThreshold,
                readBufferBytes,
                new TlsConfig(tlsEnabled, tlsMode, certPath, keyPath, ksPath, ksPass,
                        requireClientCert, trustPath, trustPass, selfSignedKeySize, selfSignedValidityDays));

        AuthConfig auth = new AuthConfig(
                cfg.getBoolean("mcp.auth.allow-legacy-header", false),
                Math.max(1, cfg.getInt("mcp.auth.max-failures", 10)),
                Math.max(1, cfg.getInt("mcp.auth.block-seconds", 60)),
                Math.max(0, cfg.getInt("mcp.auth.rotation-grace-seconds", 300)),
                cfg.getString("mcp.auth.multi-key-directory", "keys")
        );
        PolicyConfig policy = new PolicyConfig(
                cfg.getBoolean("mcp.policy.allow-unsafe-at-runtime", true),
                cfg.getBoolean("mcp.policy.logs-require-moderate", false)
        );

        long chatMinIntervalMs = Math.max(0L, cfg.getLong("mcp.tools.chat-min-interval-ms", 250));
        int chatSyncTimeoutSeconds = Math.max(1, cfg.getInt("mcp.tools.chat-sync-timeout-seconds", 5));
        int commandDispatchTimeoutSeconds = Math.max(1, cfg.getInt("mcp.tools.command-dispatch-timeout-seconds", 10));
        int logsDefaultLimit = Math.max(1, cfg.getInt("mcp.tools.logs.default-limit", 100));
        int logsMaxLimit = Math.max(logsDefaultLimit, cfg.getInt("mcp.tools.logs.max-limit", 500));
        int logsMaxScanBytes = Math.max(1024, cfg.getInt("mcp.tools.logs.max-scan-bytes", 1024 * 1024));
        int logsMaxPatternLength = Math.max(1, cfg.getInt("mcp.tools.logs.max-pattern-length", 200));
        ToolConfig tools = new ToolConfig(chatMinIntervalMs, chatSyncTimeoutSeconds,
                commandDispatchTimeoutSeconds, logsDefaultLimit, logsMaxLimit,
                logsMaxScanBytes, logsMaxPatternLength);

        boolean logCalls = cfg.getBoolean("audit.log-tool-calls", true);
        boolean logMode = cfg.getBoolean("audit.log-mode-changes", true);
        boolean logVerbose = cfg.getBoolean("audit.log-verbose", false);
        int auditMaxValueLength = Math.max(1, cfg.getInt("audit.max-value-length", 200));

        Map<String, Boolean> hooks = new LinkedHashMap<>();
        ConfigurationSection hs = cfg.getConfigurationSection("hooks");
        if (hs != null) {
            for (String k : hs.getKeys(false)) {
                hooks.put(k, hs.getBoolean(k + ".enabled", true));
            }
        }

        List<CommandClassification.Rule> rules = new ArrayList<>();
        List<Map<?, ?>> mapList = cfg.getMapList("commands.classification");
        if (mapList.isEmpty()) {
            List<?> list = cfg.getList("commands.classification");
            mapList = new ArrayList<>();
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) mapList.add(m);
                }
            }
        }
        for (Map<?, ?> m : mapList) {
            Object lvl = m.get("level");
            Object pat = m.get("pattern");
            if (lvl == null || pat == null) continue;
            CapabilityLevel level = parseCommandLevel(String.valueOf(lvl));
            if (level == null) continue;
            CommandClassification.Rule rule = CommandClassification.Rule.of(level, String.valueOf(pat));
            if (!rule.isAnchored() && logger != null) {
                logger.warning("[SECURITY] Command classification pattern '" + rule.rawPattern()
                        + "' was not anchored; auto-anchoring to ^(?:...)$.");
            }
            rules.add(rule);
        }

        return new EdenConfig(enabled, mode, transport, auth, policy, tools, logCalls, logMode, logVerbose,
                auditMaxValueLength, hooks, new CommandClassification(rules));
    }

    private static @Nullable CapabilityLevel parseCommandLevel(@Nullable String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT).trim()) {
            case "safe", "safe_read", "safe-read" -> CapabilityLevel.SAFE_READ;
            case "moderate", "moderate_action", "moderate-action" -> CapabilityLevel.MODERATE_ACTION;
            default -> null; // unsafe/default is never an override target
        };
    }
}
