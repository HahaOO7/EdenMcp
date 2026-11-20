package at.haha007.edenMcp.hooks.spark;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class GcStatsTool extends SparkStatsTool {
    GcStatsTool(SparkApi sparkApi) {
        super(sparkApi);
    }

    @Override
    public String name() {
        return "eden.spark.stats.gc";
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
    protected Map<String, Object> poll(String window) throws Exception {
        return sparkApi.gc();
    }
}
