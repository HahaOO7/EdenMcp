package at.haha007.edenMcp.core.config;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.mode.OperatingMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public record EdenConfig(
        boolean enabled,
        OperatingMode startupMode,
        TransportConfig transport,
        boolean logToolCalls,
        boolean logModeChanges,
        java.util.Map<String, Boolean> hookEnabled,
        CommandClassification commandClassification
) {
    public record TransportConfig(String type, String bind, int port, TlsConfig tls) {}
    public record TlsConfig(boolean enabled, String mode, String certPath, String keyPath, String keystorePath, String keystorePassword) {}

    public static EdenConfig from(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("mcp.enabled", true);
        OperatingMode mode = OperatingMode.parse(cfg.getString("mcp.mode", "safe"), OperatingMode.SAFE);

        String type = cfg.getString("mcp.transport.type", "http-sse");
        String bind = cfg.getString("mcp.transport.bind", "0.0.0.0");
        int port = cfg.getInt("mcp.transport.port", 8765);
        boolean tlsEnabled = cfg.getBoolean("mcp.transport.tls.enabled", true);
        String tlsMode = cfg.getString("mcp.transport.tls.mode", "self-signed");
        String certPath = cfg.getString("mcp.transport.tls.cert-path", "");
        String keyPath = cfg.getString("mcp.transport.tls.key-path", "");
        String ksPath = cfg.getString("mcp.transport.tls.keystore-path", "");
        String ksPass = cfg.getString("mcp.transport.tls.keystore-password", "");
        TransportConfig transport = new TransportConfig(type, bind, port,
                new TlsConfig(tlsEnabled, tlsMode, certPath, keyPath, ksPath, ksPass));

        boolean logCalls = cfg.getBoolean("audit.log-tool-calls", true);
        boolean logMode = cfg.getBoolean("audit.log-mode-changes", true);

        java.util.Map<String, Boolean> hooks = new java.util.LinkedHashMap<>();
        ConfigurationSection hs = cfg.getConfigurationSection("hooks");
        if (hs != null) {
            for (String k : hs.getKeys(false)) {
                hooks.put(k, hs.getBoolean(k + ".enabled", true));
            }
        }

        List<CommandClassification.Rule> rules = new ArrayList<>();
        List<java.util.Map<?, ?>> mapList = cfg.getMapList("commands.classification");
        if (mapList != null && !mapList.isEmpty()) {
            for (java.util.Map<?, ?> m : mapList) {
                Object lvl = m.get("level");
                Object pat = m.get("pattern");
                if (lvl == null || pat == null) continue;
                CapabilityLevel level = parseCommandLevel(String.valueOf(lvl));
                if (level == null) continue;
                try {
                    rules.add(new CommandClassification.Rule(level, Pattern.compile(String.valueOf(pat)), String.valueOf(pat)));
                } catch (Exception ignored) {
                    // invalid regex; skip
                }
            }
        } else {
            List<?> list = cfg.getList("commands.classification");
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof java.util.Map<?, ?> m) {
                        Object lvl = m.get("level");
                        Object pat = m.get("pattern");
                        if (lvl == null || pat == null) continue;
                        CapabilityLevel level = parseCommandLevel(String.valueOf(lvl));
                        if (level == null) continue;
                        try {
                            rules.add(new CommandClassification.Rule(level, Pattern.compile(String.valueOf(pat)), String.valueOf(pat)));
                        } catch (Exception ignored) {
                            // invalid regex; skip
                        }
                    }
                }
            }
        }

        return new EdenConfig(enabled, mode, transport, logCalls, logMode, hooks, new CommandClassification(rules));
    }

    private static CapabilityLevel parseCommandLevel(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT).trim()) {
            case "safe", "safe_read", "safe-read" -> CapabilityLevel.SAFE_READ;
            case "moderate", "moderate_action", "moderate-action" -> CapabilityLevel.MODERATE_ACTION;
            default -> null; // keep unsafe/default not as override target except maybe ignore
        };
    }
}
