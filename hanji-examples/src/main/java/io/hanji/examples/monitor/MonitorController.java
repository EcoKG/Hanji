package io.hanji.examples.monitor;

import io.hanji.JsCallable;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Java functions called from monitor.js via {@code hanji.call(...)}. */
public class MonitorController {

    record ThreadRow(long id, String name, String state, boolean daemon) {
    }

    private final List<byte[]> retained = new ArrayList<>();

    @JsCallable
    Map<String, Object> runtimeInfo() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("vm", rt.getVmName() + " " + rt.getVmVersion());
        info.put("vendor", rt.getVmVendor());
        info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        info.put("processors", Runtime.getRuntime().availableProcessors());
        info.put("pid", ProcessHandle.current().pid());
        return info;
    }

    @JsCallable
    String gc() {
        synchronized (retained) {
            retained.clear();
        }
        System.gc();
        return "GC 요청을 보냈습니다";
    }

    /** Allocates and keeps memory so the chart has something to show. Runs off the UI thread. */
    @JsCallable(background = true)
    String allocate(int megabytes) {
        int mb = Math.max(1, Math.min(megabytes, 256));
        synchronized (retained) {
            for (int i = 0; i < mb; i++) {
                retained.add(new byte[1024 * 1024]);
            }
        }
        return mb + " MB 할당";
    }

    @JsCallable(background = true)
    List<ThreadRow> threads() {
        ThreadInfo[] infos = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
        return Arrays.stream(infos)
                .map(t -> new ThreadRow(t.getThreadId(), t.getThreadName(), t.getThreadState().name(), t.isDaemon()))
                .sorted(Comparator.comparing(ThreadRow::name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }
}
