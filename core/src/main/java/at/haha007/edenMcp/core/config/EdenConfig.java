package at.haha007.edenMcp.core.config;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.mode.OperatingMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

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
        boolean logToolCalls,
        boolean logModeChanges,
        boolean logVerbose,
        Map<String, Boolean> hookEnabled,
        CommandClassification commandClassification
) {
    public record TransportConfig(String type, String bind, int port,
                                  List<String> allowedHosts, List<String> allowedOrigins,
                                  int maxBodyBytes, int maxBatchSize, int maxSseClients,
                                  TlsConfig tls) {}

    public record TlsConfig(boolean enabled, String mode, String certPath, String keyPath,
                            String keystorePath, String keystorePassword,
                            boolean requireClientCert, String truststorePath, String truststorePassword) {}

    public record AuthConfig(boolean allowLegacyHeader, int maxFailures, int blockSeconds) {}

    public record PolicyConfig(boolean allowUnsafeAtRuntime, boolean logsRequireModerate) {}

    public static EdenConfig from(FileConfiguration cfg) {
        return from(cfg, null);
    }

    public static EdenConfig from(FileConfiguration cfg, Logger logger) {
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

        boolean tlsEnabled = cfg.getBoolean("mcp.transport.tls.enabled", true);
        String tlsMode = cfg.getString("mcp.transport.tls.mode", "self-signed");
        String certPath = cfg.getString("mcp.transport.tls.cert-path", "");
        String keyPath = cfg.getString("mcp.transport.tls.key-path", "");
        String ksPath = cfg.getString("mcp.transport.tls.keystore-path", "");
        String ksPass = cfg.getString("mcp.transport.tls.keystore-password", "");
        boolean requireClientCert = cfg.getBoolean("mcp.transport.tls.require-client-cert", false);
        String trustPath = cfg.getString("mcp.transport.tls.truststore-path", "");
        String trustPass = cfg.getString("mcp.transport.tls.truststore-password", "");

        TransportConfig transport = new TransportConfig(type, bind, port, allowedHosts, allowedOrigins,
                maxBodyBytes, maxBatchSize, maxSseClients,
                new TlsConfig(tlsEnabled, tlsMode, certPath, keyPath, ksPath, ksPass,
                        requireClientCert, trustPath, trustPass));

        AuthConfig auth = new AuthConfig(
                cfg.getBoolean("mcp.auth.allow-legacy-header", false),
                Math.max(1, cfg.getInt("mcp.auth.max-failures", 10)),
                Math.max(1, cfg.getInt("mcp.auth.block-seconds", 60))
        );
        PolicyConfig policy = new PolicyConfig(
                cfg.getBoolean("mcp.policy.allow-unsafe-at-runtime", true),
                cfg.getBoolean("mcp.policy.logs-require-moderate", false)
        );

        boolean logCalls = cfg.getBoolean("audit.log-tool-calls", true);
        boolean logMode = cfg.getBoolean("audit.log-mode-changes", true);
        boolean logVerbose = cfg.getBoolean("audit.log-verbose", false);

        Map<String, Boolean> hooks = new LinkedHashMap<>();
        ConfigurationSection hs = cfg.getConfigurationSection("hooks");
        if (hs != null) {
            for (String k : hs.getKeys(false)) {
                hooks.put(k, hs.getBoolean(k + ".enabled", true));
            }
        }

        List<CommandClassification.Rule> rules = new ArrayList<>();
        List<Map<?, ?>> mapList = cfg.getMapList("commands.classification");
        if (mapList == null || mapList.isEmpty()) {
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

        return new EdenConfig(enabled, mode, transport, auth, policy, logCalls, logMode, logVerbose,
                hooks, new CommandClassification(rules));
    }

    private static CapabilityLevel parseCommandLevel(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT).trim()) {
            case "safe", "safe_read", "safe-read" -> CapabilityLevel.SAFE_READ;
            case "moderate", "moderate_action", "moderate-action" -> CapabilityLevel.MODERATE_ACTION;
            default -> null; // unsafe/default is never an override target
        };
    }
}
