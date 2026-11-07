package io.geoverse.bench;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

final class ResourceMetrics {
    record Snapshot(long heapUsed, long heapCommitted, long nonHeapUsed, long directBufferBytes, long gcCount, long gcPauseMs) {}

    static Snapshot snapshot() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
            .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
        long count = 0; long pause = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() >= 0) count += gc.getCollectionCount();
            if (gc.getCollectionTime() >= 0) pause += gc.getCollectionTime();
        }
        return new Snapshot(heap.getUsed(), heap.getCommitted(), nonHeap.getUsed(), direct, count, pause);
    }
}
