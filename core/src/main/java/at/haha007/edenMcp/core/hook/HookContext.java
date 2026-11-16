package at.haha007.edenMcp.core.hook;

import at.haha007.edenMcp.core.audit.AuditLogger;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

public record HookContext(
        Server server,
        Plugin plugin,
        Logger logger,
        AuditLogger auditLogger
) {
}
