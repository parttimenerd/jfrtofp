package me.bechberger.jfrtofp.converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of processor.ts. Top-level orchestration of JFR event stream → Profile JSON.
 *
 * <p>Unlike the TS version, this processor consumes the events in two passes by
 * design: the caller is expected to call {@link #firstPass} once per event for
 * thread/interval estimation, then {@link #secondPass} once per event for the
 * actual conversion. Each event object lives only for the duration of one call,
 * so we avoid keeping a List<ParsedJFREvent> in memory.
 */
public final class Processor {

    public static final class JFRMetadata {
        public final String jvmVersion;     // nullable
        public final String jvmArgs;        // nullable
        public final String javaArgs;       // nullable
        public final double startMs;
        public final double endMs;
        public final String cpuModel;       // nullable
        public final Integer cpuCores;      // nullable
        public final Integer cpuHwThreads;  // nullable
        public final String osVersion;      // nullable
        public final long pid;

        public JFRMetadata(String jvmVersion, String jvmArgs, String javaArgs,
                           double startMs, double endMs,
                           String cpuModel, Integer cpuCores, Integer cpuHwThreads,
                           String osVersion, long pid) {
            this.jvmVersion = jvmVersion;
            this.jvmArgs = jvmArgs;
            this.javaArgs = javaArgs;
            this.startMs = startMs;
            this.endMs = endMs;
            this.cpuModel = cpuModel;
            this.cpuCores = cpuCores;
            this.cpuHwThreads = cpuHwThreads;
            this.osVersion = osVersion;
            this.pid = pid;
        }
    }

    public static final class JFRThread {
        public final long id;
        public final String javaName; // nullable
        public final String osName;   // nullable
        public final boolean virtual;

        public JFRThread(long id, String javaName, String osName, boolean virtual) {
            this.id = id;
            this.javaName = javaName;
            this.osName = osName;
            this.virtual = virtual;
        }
    }

    /** A flattened, transient JFR event view passed to the processor's pass methods.
     *
     *  Stack-trace frames are stored as five parallel arrays sized to {@code stackDepth}
     *  (top-of-stack first, matching JFR's wire format). This lets us avoid allocating
     *  a fresh {@code JFRFrame[]} of objects per event — the populate path can append
     *  directly into the parallel arrays, and {@code processFrames} reads them with
     *  zero per-frame allocation. {@code stackDepth == 0} means no stack. */
    public static final class ParsedEvent {
        public String type;
        public double startMs;
        public double endMs;
        public int stackDepth;          // 0 = no stack
        public String[] frameClassNames;
        public String[] frameMethodNames;
        public String[] frameDescriptors;
        public int[]    frameLineNumbers;
        public boolean[] frameIsJava;
        public JFRThread thread;             // nullable
        public Map<String, Object> fields;   // never null

        public void reset() {
            type = null;
            startMs = 0;
            endMs = 0;
            stackDepth = 0;
            // Keep the parallel arrays; they're reused and grown as needed.
            thread = null;
            fields = null;
        }

        /** Ensure the parallel frame arrays have capacity for at least {@code n} frames. */
        public void ensureFrameCapacity(int n) {
            if (frameClassNames == null || frameClassNames.length < n) {
                int cap = Math.max(n, frameClassNames == null ? 32 : frameClassNames.length * 2);
                frameClassNames = new String[cap];
                frameMethodNames = new String[cap];
                frameDescriptors = new String[cap];
                frameLineNumbers = new int[cap];
                frameIsJava = new boolean[cap];
            }
        }
    }

    private final ConverterConfig config;
    private final JFRMetadata meta;
    private final BasicInfo basicInfo;
    private Tables tables;
    private MarkerSchemas.Processor markerSchemas;

    // Single-pass collected state
    private long mainThreadId = -1;
    private double endMsAcc;
    private final HashMap<Long, ThreadInfo> threadInfoMap = new HashMap<>();
    // Per-thread execution-sample times for interval estimation. Stored as a
    // primitive double[] with a length to avoid boxing.
    private final HashMap<Long, DoubleList> startTimesPerThread = new HashMap<>();

    private ThreadProcessor parentProcessor;
    private final HashMap<Long, ThreadProcessor> threadProcessors = new HashMap<>();
    private final ArrayList<CPULoadSample> cpuLoadSamples = new ArrayList<>();
    private final ArrayList<MemorySample> usedHeapSamples = new ArrayList<>();
    private final ArrayList<MemorySample> committedHeapSamples = new ArrayList<>();
    private final HashMap<String, MarkerSchemas.JFREventTypeInfo> eventTypeInfoMap = new HashMap<>();

    public Processor(ConverterConfig config, JFRMetadata meta) {
        this.config = config;
        this.meta = meta;
        this.basicInfo = basicInfoFromMetadata(meta);
        this.endMsAcc = meta.startMs;
        // Eagerly create tables; we no longer need a separate first pass.
        this.tables = new Tables(config, meta.startMs, config.sourceUrl);
        this.markerSchemas = new MarkerSchemas.Processor(config);
        this.parentProcessor = new ThreadProcessor(
            true, -1, tables, markerSchemas, basicInfo, config);
    }

    /** Register a single event-type-info as soon as it becomes known. */
    public void registerEventTypeInfo(MarkerSchemas.JFREventTypeInfo info) {
        eventTypeInfoMap.putIfAbsent(info.name, info);
    }

    // ── Single-pass event handler ────────────────────────────────────────────

    public void process(ParsedEvent event) {
        // Track end time + thread aggregates for ranking/interval estimation.
        if (event.endMs > endMsAcc) endMsAcc = event.endMs;
        boolean isExec = config.isExecutionSample(event.type);
        if (event.thread != null) {
            JFRThread t = event.thread;
            if ("main".equals(t.javaName) && mainThreadId == -1) {
                mainThreadId = t.id;
            }
            ThreadInfo info = threadInfoMap.get(t.id);
            if (info == null) {
                info = new ThreadInfo();
                info.id = t.id;
                info.javaName = t.javaName;
                info.osName = t.osName;
                info.isSystemThread = isSystemThread(t.javaName, t.osName);
                info.isGCThread = isGCThread(t.javaName, t.osName);
                threadInfoMap.put(t.id, info);
            }
            if (isExec) {
                info.executionSampleCount++;
                DoubleList times = startTimesPerThread.get(t.id);
                if (times == null) {
                    times = new DoubleList();
                    startTimesPerThread.put(t.id, times);
                }
                times.add(event.startMs);
            } else {
                info.otherSampleCount++;
            }
        }

        if (config.ignoredEvents.contains(event.type)) return;
        MarkerSchemas.JFREventTypeInfo info = eventTypeInfoMap.get(event.type);
        if (info == null) return;

        // Counters
        if ("jdk.CPULoad".equals(event.type)) {
            CPULoadSample s = new CPULoadSample();
            s.timeMs = event.startMs;
            s.jvmUser = MarkerTypes.asDouble(event.fields.get("jvmUser"));
            s.jvmSystem = MarkerTypes.asDouble(event.fields.get("jvmSystem"));
            cpuLoadSamples.add(s);
        }
        if ("jdk.GCHeapSummary".equals(event.type)) {
            MemorySample u = new MemorySample();
            u.timeMs = event.startMs;
            u.bytes = MarkerTypes.asDouble(event.fields.get("heapUsed"));
            usedHeapSamples.add(u);
            MemorySample c = new MemorySample();
            c.timeMs = event.startMs;
            c.bytes = MarkerTypes.asDouble(event.fields.get("heapSpace.committedSize"));
            committedHeapSamples.add(c);
        }

        if (event.thread == null) {
            parentProcessor.processEvent(event, info);
        } else {
            JFRThread t = event.thread;
            if (!config.includeGCThreads && isGCThread(t.javaName, t.osName)) return;
            ThreadProcessor proc = threadProcessors.get(t.id);
            if (proc == null) {
                proc = new ThreadProcessor(false, t.id, tables, markerSchemas, basicInfo, config);
                threadProcessors.put(t.id, proc);
            }
            proc.processEvent(event, info);
        }
    }

    // ── Final assembly ───────────────────────────────────────────────────────

    public void writeProfile(JsonWriter w) {
        // Finalize basic info now that all events have been seen.
        basicInfo.mainThreadId = mainThreadId;
        basicInfo.endMs = endMsAcc;
        basicInfo.intervalMs = estimateInterval(startTimesPerThread);
        if (mainThreadId != -1) {
            ThreadInfo mi = threadInfoMap.get(mainThreadId);
            if (mi != null) mi.isMainThread = true;
        }

        // Filter and rank threads
        ArrayList<ThreadInfo> validInfos = new ArrayList<>();
        for (ThreadInfo info : threadInfoMap.values()) {
            if (isValidThread(info)) validInfos.add(info);
        }
        validInfos.sort((a, b) -> Long.compare(threadScore(b), threadScore(a)));

        int nonSystemCount = 1;
        for (ThreadInfo info : validInfos) {
            if (!info.isSystemThread) nonSystemCount++;
        }
        int initialVisibleThreadsLength = Math.min(nonSystemCount, config.initialVisibleThreads + 1);
        int initialSelectedCount = Math.min(initialVisibleThreadsLength - 1, config.initialSelectedThreads);
        if (initialSelectedCount < 0) initialSelectedCount = 0;

        // Profile object
        w.beginObject();

        // meta
        w.key("meta"); writeMeta(w, initialVisibleThreadsLength, initialSelectedCount);
        // libs
        w.key("libs").beginArray().endArray();
        // shared
        w.key("shared"); tables.writeSharedData(w);
        // counters
        if (!cpuLoadSamples.isEmpty() || !usedHeapSamples.isEmpty()) {
            w.key("counters").beginArray();
            if (!cpuLoadSamples.isEmpty()) writeProcessCPUCounter(w);
            if (!usedHeapSamples.isEmpty()) writeUsedHeapCounter(w);
            w.endArray();
        }
        // threads: parent first, then ranked
        w.key("threads").beginArray();
        parentProcessor.writeThread(w);
        for (ThreadInfo info : validInfos) {
            ThreadProcessor proc = threadProcessors.get(info.id);
            if (proc != null) proc.writeThread(w);
        }
        w.endArray();

        w.endObject();
    }

    private void writeMeta(JsonWriter w, int initialVisibleLen, int initialSelectedCount) {
        String osVersion = meta.osVersion != null ? meta.osVersion : "";
        String platform = "X11";
        if (osVersion.contains("Android")) platform = "Android";
        else if (osVersion.contains("Mac OS X")) platform = "Macintosh";
        else if (osVersion.contains("Windows")) platform = "Windows";

        w.beginObject();
        w.keyDouble("interval", basicInfo.intervalMs);
        w.keyDouble("startTime", basicInfo.startMs);
        w.keyDouble("endTime", basicInfo.endMs);
        w.key("categories"); Categories.writeCategoryList(w);
        w.keyString("product", meta.javaArgs != null ? meta.javaArgs : "JVM Application");
        w.keyInt("stackwalk", 0);
        if (meta.jvmVersion != null) w.keyString("misc", "JVM Version " + meta.jvmVersion);
        if (!osVersion.isEmpty()) w.keyString("oscpu", osVersion);
        if (meta.cpuModel != null) w.keyString("cpuName", meta.cpuModel);
        w.keyString("platform", platform);
        w.key("markerSchema"); markerSchemas.writeMarkerSchemaList(w);
        if (meta.jvmArgs != null) {
            w.keyString("arguments",
                "jvm=" + meta.jvmArgs + "  --  java=" + (meta.javaArgs != null ? meta.javaArgs : ""));
        } else {
            w.keyString("arguments", "<unknown>");
        }
        if (meta.cpuCores != null) w.keyInt("physicalCPUs", meta.cpuCores);
        if (meta.cpuHwThreads != null) w.keyInt("logicalCPUs", meta.cpuHwThreads);
        w.key("sampleUnits").beginObject();
        w.keyString("time", "ms");
        w.keyString("eventDelay", "ms");
        w.keyString("threadCPUDelta", "µs");
        w.endObject();
        w.keyString("importedFrom", "JFR profile");
        w.key("extra").beginArray().endArray();
        w.key("initialVisibleThreads").beginArray();
        for (int i = 0; i < initialVisibleLen; i++) w.value(i);
        w.endArray();
        w.key("initialSelectedThreads").beginArray();
        if (config.selectProcessTrackInitially) w.value(0);
        for (int i = 0; i < initialSelectedCount; i++) w.value(i + 1);
        w.endArray();
        w.keyBoolean("keepProfileThreadOrder", true);
        w.keyInt("processType", 0);
        w.keyInt("version", 25);
        w.keyInt("preprocessedProfileVersion", 62);
        w.keyBoolean("symbolicated", true);
        w.keyBoolean("symbolicationNotSupported", true);
        w.keyBoolean("usesOnlyOneStackType", true);
        w.keyBoolean("doesNotUseFrameImplementation", true);
        w.keyBoolean("sourceCodeIsNotOnSearchfox", true);
        w.endObject();
    }

    private void writeProcessCPUCounter(JsonWriter w) {
        cpuLoadSamples.sort(Comparator.comparingDouble(s -> s.timeMs));
        int n = cpuLoadSamples.size();
        w.beginObject();
        w.keyString("name", "processCPU");
        w.keyString("category", "CPU");
        w.keyString("description", "Process CPU utilization");
        w.keyString("pid", String.valueOf(basicInfo.pid));
        w.keyInt("mainThreadIndex", 0);
        w.key("samples").beginObject();
        w.key("time").beginArray();
        for (CPULoadSample s : cpuLoadSamples) w.value(s.timeMs);
        w.endArray();
        w.key("count").beginArray();
        for (CPULoadSample s : cpuLoadSamples) {
            w.value((long) Math.round((s.jvmUser + s.jvmSystem) * 1_000_000));
        }
        w.endArray();
        w.keyInt("length", n);
        w.endObject();
        w.key("display").beginObject();
        w.keyString("graphType", "line-rate");
        w.keyString("unit", "%");
        w.keyString("color", "grey");
        w.endObject();
        w.endObject();
    }

    private void writeUsedHeapCounter(JsonWriter w) {
        usedHeapSamples.sort(Comparator.comparingDouble(s -> s.timeMs));
        int n = usedHeapSamples.size();
        double[] deltas = new double[n];
        for (int i = 0; i < n; i++) {
            deltas[i] = i == 0 ? usedHeapSamples.get(i).bytes
                              : usedHeapSamples.get(i).bytes - usedHeapSamples.get(i - 1).bytes;
        }
        w.beginObject();
        w.keyString("name", "usedHeap");
        w.keyString("category", "Memory");
        w.keyString("description", "Used heap");
        w.keyString("pid", String.valueOf(basicInfo.pid));
        w.keyInt("mainThreadIndex", 0);
        w.key("samples").beginObject();
        w.key("time").beginArray();
        for (MemorySample s : usedHeapSamples) w.value(s.timeMs);
        w.endArray();
        w.key("count").beginArray();
        for (double d : deltas) w.value(d);
        w.endArray();
        w.keyInt("length", n);
        w.endObject();
        w.key("display").beginObject();
        w.keyString("graphType", "line-accumulated");
        w.keyString("unit", "bytes");
        w.keyString("color", "orange");
        w.keyString("markerSchemaLocation", "timeline-memory");
        w.endObject();
        w.endObject();
    }

    private boolean isValidThread(ThreadInfo info) {
        if (info.isMainThread) return true;
        if (info.isGCThread) return config.includeGCThreads;
        long combined = info.executionSampleCount + info.otherSampleCount;
        if (combined < config.minRequiredItemsPerThread) return false;
        if (!info.isSystemThread) return info.executionSampleCount > 0;
        return true;
    }

    private long threadScore(ThreadInfo info) {
        if (info.isMainThread) return Long.MAX_VALUE;
        return info.executionSampleCount * 2L + info.otherSampleCount;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static BasicInfo basicInfoFromMetadata(JFRMetadata meta) {
        BasicInfo b = new BasicInfo();
        b.mainThreadId = -1;
        b.startMs = meta.startMs;
        b.endMs = meta.endMs;
        b.intervalMs = 1.0;
        b.pid = meta.pid;
        b.hwThreads = meta.cpuHwThreads != null ? meta.cpuHwThreads : 1;
        return b;
    }

    private static boolean isSystemThread(String javaName, String osName) {
        if (javaName == null || javaName.isEmpty()) return false;
        switch (javaName) {
            case "JFR Shutdown Hook":
            case "Permissionless thread":
            case "Thread Monitor CTRL-C":
            case "Monitor Ctrl-Break":
            case "Notification Thread":
            case "Finalizer":
            case "Attach Listener":
            case "Signal Dispatcher":
            case "Reference Handler":
            case "Common-Cleaner":
            case "VM Thread":
            case "VM Periodic Task Thread":
            case "Sweeper thread":
            case "Service Thread":
            case "Watcher Thread":
            case "DestroyJavaVM":
                return true;
        }
        if (javaName.startsWith("JFR ")) return true;
        if (javaName.startsWith("GC Thread")) return true;
        if (javaName.startsWith("G1 ")) return true;
        if (javaName.startsWith("ZGC ")) return true;
        if (javaName.startsWith("Shenandoah ")) return true;
        if (javaName.startsWith("ParGC ")) return true;
        if (javaName.startsWith("CMS ")) return true;
        if (javaName.startsWith("C1 CompilerThread")) return true;
        if (javaName.startsWith("C2 CompilerThread")) return true;
        if (javaName.startsWith("Graal Compiler Thread")) return true;
        if (javaName.startsWith("JVMCI CompilerThread")) return true;
        if (javaName.contains("CompilerThread")) return true;
        return false;
    }

    private static boolean isGCThread(String javaName, String osName) {
        return osName != null && osName.startsWith("GC Thread")
            && (javaName == null || javaName.isEmpty());
    }

    private static double estimateInterval(Map<Long, DoubleList> startTimesPerThread) {
        final double MAX_INTERVAL = 1000.0;
        DoubleList all = new DoubleList();
        for (DoubleList times : startTimesPerThread.values()) {
            int len = times.size();
            if (len < 3) continue;
            double[] arr = times.copyToSortedArray();
            for (int i = 1; i < len; i++) {
                double diff = arr[i] - arr[i - 1];
                if (diff > 0 && diff < MAX_INTERVAL) all.add(diff);
            }
        }
        if (all.size() == 0) return 1.0;
        double[] arr = all.copyToSortedArray();
        int n = arr.length;
        int from = (int) Math.floor(n * 0.1);
        int to = (int) Math.floor(n * 0.8);
        if (to <= from) return 1.0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += arr[i];
        return sum / (to - from);
    }

    /** Primitive-double list to avoid boxing on the hot path. */
    static final class DoubleList {
        double[] data = new double[16];
        int n = 0;
        void add(double v) {
            if (n == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[n++] = v;
        }
        int size() { return n; }
        double[] copyToSortedArray() {
            double[] out = Arrays.copyOf(data, n);
            Arrays.sort(out);
            return out;
        }
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    static final class BasicInfo {
        long mainThreadId;
        double startMs;
        double endMs;
        double intervalMs;
        long pid;
        int hwThreads;
    }

    static final class ThreadInfo {
        long id;
        String javaName;
        String osName;
        boolean isMainThread;
        boolean isSystemThread;
        boolean isGCThread;
        long executionSampleCount;
        long otherSampleCount;
    }

    static final class CPULoadSample {
        double timeMs;
        double jvmUser;
        double jvmSystem;
    }

    static final class MemorySample {
        double timeMs;
        double bytes;
    }

    // ── Per-thread processor ─────────────────────────────────────────────────

    static final class ThreadProcessor {
        final boolean isParentThread;
        final long threadId;
        final Tables tables;
        final MarkerSchemas.Processor markerSchemas;
        final BasicInfo basicInfo;
        final ConverterConfig config;

        Double startMs = null; // unboxed-friendly: -1 sentinel via Double null
        double endMs = 0;
        final HashMap<Long, Double> cpuLoads = new HashMap<>(); // µs → load
        final HashSet<String> seenEventTypes = new HashSet<>();
        long itemCount = 0;
        Tables.SamplesTableWrapper samplesTable = new Tables.SamplesTableWrapper();
        Tables.RawMarkerTableWrapper markerTable = new Tables.RawMarkerTableWrapper();
        // pausedRanges stored as parallel arrays
        ArrayList<double[]> pausedRanges = new ArrayList<>();
        Double threadStartMs = null;
        Double threadEndMs = null;
        String javaName = null;
        String osName = null;

        ThreadProcessor(boolean isParentThread, long threadId,
                        Tables tables, MarkerSchemas.Processor markerSchemas,
                        BasicInfo basicInfo, ConverterConfig config) {
            this.isParentThread = isParentThread;
            this.threadId = threadId;
            this.tables = tables;
            this.markerSchemas = markerSchemas;
            this.basicInfo = basicInfo;
            this.config = config;
            if (isParentThread) startMs = basicInfo.startMs;
        }

        void processEvent(ParsedEvent event, MarkerSchemas.JFREventTypeInfo info) {
            if (startMs == null) startMs = event.startMs;
            if (event.endMs > endMs) endMs = event.endMs;
            seenEventTypes.add(event.type);

            if (event.thread != null) {
                if (javaName == null) javaName = event.thread.javaName;
                if (osName == null) osName = event.thread.osName;
            }

            if (config.isExecutionSample(event.type)) {
                if (event.stackDepth > 0) {
                    int stackIdx = tables.processFrames(
                        event.frameClassNames, event.frameMethodNames,
                        event.frameDescriptors, event.frameLineNumbers,
                        event.frameIsJava, event.stackDepth, tables.defaultUrl);
                    samplesTable.processEvent(stackIdx, event.startMs);
                }
                itemCount++;
            } else if (config.enableMarkers) {
                MarkerSchemas.SchemaMapping mapping = markerSchemas.getMapping(info);
                if (mapping != null) {
                    int nameIdx = tables.stringTable.get(event.type);
                    int phase = event.endMs == event.startMs ? 0 : 1;
                    String catName = info.categoryNames != null && info.categoryNames.length > 0
                        ? info.categoryNames[0] : "";
                    int categoryIdx = Categories.fromCategoryName(catName).index;
                    String dataJson = markerSchemas.buildMarkerData(
                        mapping, event.type, event.startMs, event.fields,
                        event, tables, null);
                    markerTable.add(new Tables.MarkerItem(
                        nameIdx, event.startMs, event.endMs, phase, categoryIdx, dataJson));
                    itemCount++;
                }
            }

            switch (event.type) {
                case "jdk.ThreadCPULoad": {
                    double user = MarkerTypes.asDouble(event.fields.get("user"));
                    double system = MarkerTypes.asDouble(event.fields.get("system"));
                    long micros = Math.round(event.startMs * 1000.0);
                    cpuLoads.put(micros, (user + system) * basicInfo.hwThreads);
                    break;
                }
                case "jdk.ThreadStart":
                    threadStartMs = event.startMs;
                    break;
                case "jdk.ThreadEnd":
                    threadEndMs = event.startMs;
                    break;
                case "jdk.ThreadPark":
                    pausedRanges.add(new double[] { event.startMs, event.endMs });
                    break;
                default:
                    break;
            }
        }

        double getCpuLoad(double timeMs) {
            if (cpuLoads.isEmpty()) return 1.0;
            long micros = Math.round(timeMs * 1000.0);
            long floorKey = Long.MIN_VALUE;
            long ceilKey = Long.MAX_VALUE;
            Double floorVal = null;
            Double ceilVal = null;
            for (Map.Entry<Long, Double> e : cpuLoads.entrySet()) {
                long k = e.getKey();
                double v = e.getValue();
                if (k <= micros && k > floorKey) { floorKey = k; floorVal = v; }
                if (k >= micros && k < ceilKey) { ceilKey = k; ceilVal = v; }
            }
            if (floorVal == null) return ceilVal;
            if (ceilVal == null) return floorVal;
            return (micros - floorKey) < (ceilKey - micros) ? floorVal : ceilVal;
        }

        String name() {
            if (isParentThread) return "GeckoMain";
            String jn = (javaName != null && !javaName.isEmpty()) ? javaName : null;
            if (jn != null) return jn;
            if (osName != null) return osName;
            return "<unknown>";
        }

        String pid() { return String.valueOf(basicInfo.pid); }
        long tid() { return isParentThread ? 0 : threadId; }
        String processType() { return isParentThread ? "tab" : "default"; }

        double registerTime() {
            if (threadStartMs != null) return threadStartMs;
            if (startMs != null) return startMs;
            return basicInfo.startMs;
        }

        double unregisterTime() {
            if (threadEndMs != null) return threadEndMs;
            return endMs;
        }

        List<MarkerSchemas.SampleLikeMarkerConfig> generateSampleLikeMarkersConfig() {
            ArrayList<MarkerSchemas.SampleLikeMarkerConfig> out = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            for (String typeName : seenEventTypes) {
                for (MarkerSchemas.SampleLikeMarkerConfig cfg :
                        MarkerSchemas.generateSampleLikeMarkersConfig(typeName, null)) {
                    if (seen.add(cfg.name)) out.add(cfg);
                }
            }
            return out;
        }

        void writeThread(JsonWriter w) {
            String threadName = name();
            w.beginObject();
            w.keyString("processType", processType());
            w.keyDouble("processStartupTime",
                startMs != null ? startMs : basicInfo.startMs);
            w.keyDouble("processShutdownTime", endMs);
            w.keyDouble("registerTime", registerTime());
            w.keyDouble("unregisterTime", unregisterTime());
            w.key("pausedRanges").beginArray();
            pausedRanges.sort(Comparator.comparingDouble(a -> a[0]));
            for (double[] r : pausedRanges) {
                w.beginObject();
                w.keyDouble("startTime", r[0]);
                w.keyDouble("endTime", r[1]);
                w.keyString("reason", "parked");
                w.endObject();
            }
            w.endArray();
            w.keyString("name", threadName);
            w.keyBoolean("isMainThread", "GeckoMain".equals(threadName));
            w.keyString("processName", "Parent Process");
            w.keyString("pid", pid());
            w.keyLong("tid", tid());
            w.key("samples"); samplesTable.writeTo(w, this::getCpuLoad);
            w.keyNull("jsAllocations");
            w.keyNull("nativeAllocations");
            w.key("markers"); markerTable.writeTo(w);
            List<MarkerSchemas.SampleLikeMarkerConfig> slm = generateSampleLikeMarkersConfig();
            if (!slm.isEmpty()) {
                w.key("sampleLikeMarkersConfig").beginArray();
                for (MarkerSchemas.SampleLikeMarkerConfig c : slm) c.writeTo(w);
                w.endArray();
            }
            w.endObject();
        }
    }
}
