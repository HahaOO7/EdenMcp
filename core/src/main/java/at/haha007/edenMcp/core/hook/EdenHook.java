package at.haha007.edenMcp.core.hook;

import org.bukkit.Server;

public interface EdenHook {
    String id();

    boolean isAvailable(Server server);

    HookContribution contribute(HookContext ctx);
}
