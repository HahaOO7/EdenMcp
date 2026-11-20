package at.haha007.edenmcp.hooks.spark;

import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small reflective adapter over spark's public API.
 *
 * <p>Using reflection keeps this hook decoupled from spark's snapshot repository and avoids
 * shipping (or shading) a copy of the API into the plugin classloader. The method names are
 * part of spark's documented developer API and are verified against the bundled spark build.
 */
final class SparkApi {
    private static final String SERVICE = "me.lucko.spark.api.Spark";
    private static final String PROVIDER = "me.lucko.spark.api.SparkProvider";

    private final Server server;

    SparkApi(Server server) {
        this.server = server;
    }

    /** Bundled Paper Spark is not a Bukkit plugin, so detect it via its API service or class. */
    static boolean isAvailable(Server server) {
        try {
            if (server.getPluginManager().getPlugin("spark") != null) return true;
        } catch (Throwable ignored) {
            // Keep checking the other signals.
        }
        try {
            for (Class<?> service : server.getServicesManager().getKnownServices()) {
                if (SERVICE.equals(service.getName())) return true;
            }
        } catch (Throwable ignored) {
            // Keep checking the other signals.
        }
        try {
            Class.forName(PROVIDER, false, server.getClass().getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object spark() {
        Object instance = null;
        try {
            for (Class<?> service : server.getServicesManager().getKnownServices()) {
                if (!SERVICE.equals(service.getName())) continue;
                RegisteredServiceProvider<?> registration = server.getServicesManager().getRegistration(service);
                if (registration == null) continue;
                instance = registration.getProvider();
                break;
            }
        } catch (Throwable ignored) {
            // Fall through to the singleton provider below.
        }
        if (instance == null) {
            try {
                Class<?> provider = Class.forName(PROVIDER, true, server.getClass().getClassLoader());
                instance = provider.getMethod("get").invoke(null);
            } catch (Throwable ignored) {
                // Spark has not published its API yet.
            }
        }
        if (instance == null) {
            throw new IllegalStateException("Spark API is not available yet");
        }
        return instance;
    }

    Map<String, Object> cpu(@Nullable String window) throws Exception {
        return pollDouble("cpuProcess", window);
    }

    Map<String, Object> systemCpu() throws Exception {
        return pollDouble("cpuSystem", null);
    }

    Map<String, Object> tps(@Nullable String window) throws Exception {
        return pollDouble("tps", window);
    }

    Map<String, Object> mspt(@Nullable String window) throws Exception {
        Object statistic = call(spark(), "mspt");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statistic", call(statistic, "name"));
        Map<String, Object> windows = new LinkedHashMap<>();
        for (Object candidate : windows(statistic)) {
            String name = enumName(candidate);
            if (window != null && !window.equals(name)) continue;
            Object info = call(statistic, "poll", candidate);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("window", name);
            values.put("lengthMs", durationMs(candidate));
            values.put("mean", call(info, "mean"));
            values.put("min", call(info, "min"));
            values.put("max", call(info, "max"));
            values.put("median", call(info, "median"));
            values.put("percentile95th", call(info, "percentile95th"));
            windows.put(name, values);
        }
        result.put("windows", windows);
        return result;
    }

    Map<String, Object> gc() throws Exception {
        Object raw = call(spark(), "gc");
        Map<String, Object> result = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> collectors)) return result;
        for (Map.Entry<?, ?> entry : collectors.entrySet()) {
            Object collector = entry.getValue();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", call(collector, "name"));
            values.put("totalCollections", call(collector, "totalCollections"));
            values.put("totalTimeMs", call(collector, "totalTime"));
            values.put("avgTimeMs", call(collector, "avgTime"));
            values.put("avgFrequencyMs", call(collector, "avgFrequency"));
            result.put(String.valueOf(entry.getKey()), values);
        }
        return result;
    }

    /** Combined, best-effort snapshot used by the resource and convenience diagnostics. */
    Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "spark");
        result.put("timestamp", java.time.Instant.now().toString());
        result.put("cpuProcess", section(() -> cpu(null)));
        result.put("cpuSystem", section(this::systemCpu));
        result.put("tps", section(() -> tps(null)));
        result.put("mspt", section(() -> mspt(null)));
        result.put("gc", section(this::gc));
        return result;
    }

    private Object section(SupplierThrowing supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return Map.of("error", String.valueOf(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    private Map<String, Object> pollDouble(String method, @Nullable String window) throws Exception {
        Object statistic = call(spark(), method);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statistic", call(statistic, "name"));
        Map<String, Object> windows = new LinkedHashMap<>();
        for (Object candidate : windows(statistic)) {
            String name = enumName(candidate);
            if (window != null && !window.equals(name)) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("window", name);
            values.put("lengthMs", durationMs(candidate));
            values.put("value", call(statistic, "poll", candidate));
            windows.put(name, values);
        }
        result.put("windows", windows);
        return result;
    }

    private static Object[] windows(Object statistic) throws Exception {
        Object raw = call(statistic, "getWindows");
        return raw instanceof Object[] array ? array : new Object[0];
    }

    private static String enumName(Object window) {
        return window instanceof Enum<?> e ? e.name() : String.valueOf(window);
    }

    private static long durationMs(Object window) throws Exception {
        Object duration = call(window, "length");
        return duration instanceof Duration d ? d.toMillis() : -1L;
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) throw new IllegalStateException("Spark API is unavailable");
        Method method = findMethod(target.getClass(), name, args);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        }
        // Spark's API implementations are often package-private inner classes; their methods
        // are public but not accessible from this classloader without this hint.
        method.trySetAccessible();
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }

    private static @Nullable Method findMethod(Class<?> type, String name, Object[] args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean matches = true;
            Class<?>[] params = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                Class<?> expected = boxed(params[i]);
                if (args[i] == null) {
                    if (params[i].isPrimitive()) matches = false;
                    continue;
                }
                if (!expected.isInstance(args[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return method;
        }
        return null;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    @FunctionalInterface
    private interface SupplierThrowing {
        Object get() throws Exception;
    }

    /** Exposed so stats tools can advertise the exact window names spark reports. */
    static final class Windows {
        static final Set<String> CPU = Set.of("SECONDS_10", "MINUTES_1", "MINUTES_15");
        static final Set<String> TPS = Set.of("SECONDS_5", "SECONDS_10", "MINUTES_1", "MINUTES_5", "MINUTES_15");
        static final Set<String> MSPT = Set.of("SECONDS_10", "MINUTES_1", "MINUTES_5");

        private Windows() {
        }

        static @Nullable String resolve(Map<String, Object> args, Map<String, String> aliases,
                                        Set<String> allowed) {
            String key = "window";
            Object value = args.get(key);
            if (value == null) return null;
            if (!(value instanceof String raw)) {
                throw new IllegalArgumentException("Invalid '" + key + "'; allowed: " + String.join(", ", aliases.keySet()));
            }
            String text = raw.trim();
            String resolved = aliases.get(text.toLowerCase(java.util.Locale.ROOT));
            if (resolved == null) resolved = text.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            if (!allowed.contains(resolved)) {
                throw new IllegalArgumentException("Invalid '" + key + "'; allowed: " + String.join(", ", aliases.keySet()));
            }
            return resolved;
        }

        static List<String> keys(Map<String, String> aliases) {
            return List.copyOf(aliases.keySet());
        }
    }
}
