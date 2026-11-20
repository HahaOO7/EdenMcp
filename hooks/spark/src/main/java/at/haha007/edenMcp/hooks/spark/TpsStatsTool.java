package at.haha007.edenMcp.hooks.spark;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TpsStatsTool extends SparkStatsTool {
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("5s", "SECONDS_5");
        ALIASES.put("10s", "SECONDS_10");
        ALIASES.put("1m", "MINUTES_1");
        ALIASES.put("5m", "MINUTES_5");
        ALIASES.put("15m", "MINUTES_15");
    }

    TpsStatsTool(SparkApi sparkApi) {
        super(sparkApi);
    }

    @Override
    public String name() {
        return "eden.spark.stats.tps";
    }

    @Override
    public String description() {
        return "Read spark's TPS statistics for the selected polling window(s).";
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
        return SparkApi.Windows.TPS;
    }

    @Override
    protected Map<String, Object> poll(@Nullable String window) throws Exception {
        return sparkApi.tps(window);
    }
}
