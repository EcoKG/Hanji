package io.hanji.examples.monitor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/** One JVM measurement, sent to the page as JSON. */
public record Sample(long time, long heapUsed, long heapCommitted, long heapMax, int threads,
                     double processCpu, long gcCount) {

    static Sample capture() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long gc = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc += Math.max(0, bean.getCollectionCount());
        }
        double cpu = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean) {
            cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getProcessCpuLoad();
        }
        return new Sample(System.currentTimeMillis(), heap.getUsed(), heap.getCommitted(), heap.getMax(),
                ManagementFactory.getThreadMXBean().getThreadCount(), cpu, gc);
    }
}
