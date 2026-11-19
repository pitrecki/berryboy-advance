package berryboy.desktop.profile;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host accounting; these counters do not enter serialized guest state. */
final class ProfileMetrics {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static {
        if (!THREADS.isThreadAllocatedMemorySupported() || !THREADS.isCurrentThreadCpuTimeSupported())
            throw new UnsupportedOperationException("Profile requires thread allocation and CPU accounting");
        THREADS.setThreadAllocatedMemoryEnabled(true);
        THREADS.setThreadCpuTimeEnabled(true);
    }
    private ProfileMetrics() { }
    static long allocated() { return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    static long cpu() { return THREADS.getCurrentThreadCpuTime(); }
    static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum();
    }
    static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }
    static Map<String, Object> distribution(long[] values) {
        if (values.length == 0) throw new IllegalArgumentException("Empty profile samples");
        var sorted = values.clone(); Arrays.sort(sorted);
        var result = new LinkedHashMap<String, Object>();
        result.put("total", Arrays.stream(values).sum());
        result.put("p50", sorted[(sorted.length - 1) / 2]);
        result.put("p95", sorted[(int) ((sorted.length - 1L) * 95 / 100)]);
        result.put("p99", sorted[(int) ((sorted.length - 1L) * 99 / 100)]);
        result.put("max", sorted[sorted.length - 1]);
        return result;
    }
}
