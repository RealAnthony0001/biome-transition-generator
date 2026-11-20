package net.biometransitions.debug;

import net.biometransitions.BiomeTransitions;
import net.biometransitions.config.BtgConfig;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Placement attempts, successes and rejection reasons. */
public final class Stats {
    private static final Map<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();

    private Stats() {}

    public static void inc(String key) {
        if (BtgConfig.get().debug.collectStats) COUNTERS.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public static void reject(String featureId, String reason) {
        inc("rejected: " + reason);
        if (BtgConfig.get().debug.logRejections) BiomeTransitions.LOGGER.info("Rejected {}: {}", featureId, reason);
    }

    public static Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        COUNTERS.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public static void reset() { COUNTERS.clear(); }
}
