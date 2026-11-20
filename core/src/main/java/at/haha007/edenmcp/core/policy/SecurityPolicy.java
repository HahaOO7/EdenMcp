package at.haha007.edenmcp.core.policy;

import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.McpTool;
import org.jetbrains.annotations.Nullable;

/**
 * Single place for authorization decisions that are not purely mode-based.
 * The transport and the mode-control tool both consult this policy so that
 * an escalation cannot bypass a server-side lock through a second code path.
 */
public final class SecurityPolicy {
    private final boolean allowUnsafeAtRuntime;

    public SecurityPolicy(boolean allowUnsafeAtRuntime) {
        this.allowUnsafeAtRuntime = allowUnsafeAtRuntime;
    }

    /** Mode ceiling for tool execution. */
    public boolean canExecute(@Nullable OperatingMode mode, @Nullable McpTool tool) {
        return mode != null && tool != null && mode.allows(tool.level());
    }

    /** Whether a transition into the target mode is permitted by server policy. */
    public boolean canEnter(@Nullable OperatingMode target) {
        return target != null && (target != OperatingMode.UNSAFE || allowUnsafeAtRuntime);
    }

    public boolean allowsUnsafeAtRuntime() {
        return allowUnsafeAtRuntime;
    }
}
