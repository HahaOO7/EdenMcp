package at.haha007.edenmcp.hooks.spark;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class GcStatsTool extends SparkStatsTool {
    GcStatsTool(SparkApi sparkApi) {
        super(sparkApi);
    }

    @Override
    public String name() {
        return "edenmcp.spark.stats.gc";
    }

    @Override
    public String description() {
        return "Read spark's garbage-collection counters and averages per collector.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    protected List<String> windows() {
        return List.of();
    }

    @Override
    protected Map<String, String> aliases() {
        return Map.of();
    }

    @Override
    protected Set<String> allowed() {
        return Set.of();
    }

    @Override
    protected Map<String, Object> poll(@Nullable String window) throws Exception {
        return sparkApi.gc();
    }
}
