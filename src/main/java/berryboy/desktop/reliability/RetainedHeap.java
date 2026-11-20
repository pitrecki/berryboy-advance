package berryboy.desktop.reliability;

import java.lang.management.ManagementFactory;

/** Explicit, paused host diagnostic; never part of guest execution or a device performance claim. */
public final class RetainedHeap {
    public record Sample(long usedBytes, long gcCount, long gcCollectionMillis) { }
    private RetainedHeap() { }
    public static Sample collectAndSample() {
        long before = collections();
        System.gc();
        long after = collections();
        if (after <= before) throw new IllegalStateException("Explicit GC did not complete; retained heap is unqualified");
        long millis = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum();
        return new Sample(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), after, millis);
    }
    private static long collections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum();
    }
}
