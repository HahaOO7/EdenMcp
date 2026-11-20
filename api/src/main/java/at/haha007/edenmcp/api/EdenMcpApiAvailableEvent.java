package at.haha007.edenmcp.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired once EdenMcp has published its API service, for plugins that load earlier. */
public final class EdenMcpApiAvailableEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final EdenMcpApi api;

    public EdenMcpApiAvailableEvent(EdenMcpApi api) {
        this.api = api;
    }

    public EdenMcpApi api() {
        return api;
    }

    @SuppressWarnings("unused") // required by Bukkit's event convention
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
