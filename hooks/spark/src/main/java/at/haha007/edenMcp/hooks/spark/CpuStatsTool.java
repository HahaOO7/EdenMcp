package at.haha007.edenMcp.hooks.spark;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CpuStatsTool extends SparkStatsTool {
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("10s", "SECONDS_10");
        ALIASES.put("1m", "MINUTES_1");
        ALIASES.put("15m", "MINUTES_15");
    }

    CpuStatsTool(SparkApi sparkApi) {
        super(sparkApi);
    }

    @Override
    public String name() {
        return "eden.spark.stats.cpu";
    }

    @Override
    public String description() {
        return "Read spark's process CPU usage statistics for the selected polling window(s).";
    }

    @Override
    protected List<String> windows() {
        return SparkApi.Windows.keys(ALIASES);
    }

    @Override
    protected Map<String, String> aliases() {
        return ALIASES;
    }

    @Override
    protected Set<String> allowed() {
        return SparkApi.Windows.CPU;
    }

    @Override
    protected Map<String, Object> poll(String window) throws Exception {
        return sparkApi.cpu(window);
    }
}
