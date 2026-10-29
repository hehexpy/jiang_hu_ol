package com.jianghuol.helper;

import java.util.LinkedHashMap;
import java.util.Locale;

/** 每个流程线程独立记账；耗时使用单调时钟，截图等待与匹配不重叠计数。 */
final class PerfStats {
    private static final ThreadLocal<PerfStats> CURRENT = new ThreadLocal<>();
    private final LinkedHashMap<String, long[]> totals = new LinkedHashMap<>();
    private long phaseStart = System.nanoTime();
    private long lastFrameNs, lastMatchNs, lastIdleNs, lastResultNs;

    static void begin() { CURRENT.set(new PerfStats()); }
    static void add(String kind, long start) {
        PerfStats p = CURRENT.get();
        if (p == null) return;
        long[] v = p.totals.get(kind);
        if (v == null) { v = new long[2]; p.totals.put(kind, v); }
        v[0]++; v[1] += System.nanoTime() - start;
    }
    static void phase(String label) {
        PerfStats p = CURRENT.get();
        if (p == null) return;
        long now = System.nanoTime();
        long frames = 0, matches = 0, idle = 0, results = 0;
        for (java.util.Map.Entry<String, long[]> e : p.totals.entrySet()) {
            if (e.getKey().equals("frame_wait")) frames += e.getValue()[1];
            if (e.getKey().startsWith("match:")) matches += e.getValue()[1];
            if (e.getKey().equals("explicit_wait")) idle += e.getValue()[1];
            if (e.getKey().startsWith("result_wait:")) results += e.getValue()[1];
        }
        LogBus.get().i("perf", String.format(Locale.ROOT,
                "PERF step=%s total_ms=%.1f frame_ms=%.1f match_ms=%.1f explicit_ms=%.1f result_wall_ms=%.1f",
                label, (now - p.phaseStart) / 1e6, (frames - p.lastFrameNs) / 1e6,
                (matches - p.lastMatchNs) / 1e6, (idle - p.lastIdleNs) / 1e6,
                (results - p.lastResultNs) / 1e6));
        p.lastFrameNs = frames; p.lastMatchNs = matches; p.lastIdleNs = idle; p.lastResultNs = results;
        p.phaseStart = now;
    }
    static void finish() {
        PerfStats p = CURRENT.get();
        if (p == null) return;
        for (java.util.Map.Entry<String, long[]> e : p.totals.entrySet()) {
            long[] v = e.getValue();
            LogBus.get().i("perf", String.format(Locale.ROOT,
                    "PERF kind=%s calls=%d total_ms=%.1f mean_ms=%.2f",
                    e.getKey(), v[0], v[1] / 1e6, v[1] / 1e6 / v[0]));
        }
        CURRENT.remove();
    }
}
