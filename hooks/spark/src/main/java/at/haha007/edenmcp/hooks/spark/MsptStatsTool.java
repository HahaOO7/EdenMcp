package at.haha007.edenmcp.hooks.spark;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MsptStatsTool extends SparkStatsTool {
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("10s", "SECONDS_10");
        ALIASES.put("1m", "MINUTES_1");
        ALIASES.put("5m", "MINUTES_5");
    }

    MsptStatsTool(SparkApi sparkApi) {
        super(sparkApi);
    }

    @Override
    public String name() {
        return "edenmcp.spark.stats.mspt";
    }

    @Override
    public String description() {
        return "Read spark's milliseconds-per-tick distribution (mean, median, percentiles) for the selected window(s).";
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
        return SparkApi.Windows.MSPT;
    }

    @Override
    protected Map<String, Object> poll(@Nullable String window) throws Exception {
        return sparkApi.mspt(window);
    }
}
