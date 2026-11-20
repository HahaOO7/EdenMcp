package at.haha007.edenmcp.api;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/** Services available to a hook while it contributes tools and resources. */
public record HookContext(
        Server server,
        Plugin owner,
        Plugin edenMcp,
        Logger logger
) {
}
