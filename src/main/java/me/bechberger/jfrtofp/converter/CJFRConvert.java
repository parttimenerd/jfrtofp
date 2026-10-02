package me.bechberger.jfrtofp.converter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.bechberger.cjfr.CJFREvent;
import me.bechberger.cjfr.CJFREventType;
import me.bechberger.cjfr.CJFRFile;
import me.bechberger.cjfr.Options;
import me.bechberger.condensed.ReadList;
import me.bechberger.condensed.ReadStruct;

/** Converts a .cjfr file to Firefox Profiler JSON. */
public final class CJFRConvert {

    public static void convert(Path path, ConverterConfig config, OutputStream out)
            throws IOException {
        String[] jvmVersion = {null};
        String[] jvmArgs = {null};
        String[] javaArgs = {null};
        long[] startNanos = {0L};
        long[] endNanos = {0L};
        String[] cpuModel = {null};
        int[] cpuCores = {0};
        int[] cpuHwThreads = {0};
        String[] osVersion = {null};
        long[] pid = {-1L};
        boolean[] firstEvent = {true};

        Processor.ParsedEvent scratch = new Processor.ParsedEvent();
        HashMap<String, Object> scratchFields = new HashMap<>();
        LinkedHashMap<String, CJFREventType> seenTypes = new LinkedHashMap<>();
        Processor[] procRef = {null};
        ArrayList<PrebufferedEvent> prebuffer = new ArrayList<>();

        try (CJFRFile file = CJFRFile.open(path, Options.defaults().withReconstitution(false))) {
            CJFREvent event;
            while ((event = file.readEvent()) != null) {
                String typeName = event.getEventType().getName();

                if (!seenTypes.containsKey(typeName)) {
                    seenTypes.put(typeName, event.getEventType());
                    if (procRef[0] != null) {
                        procRef[0].registerEventTypeInfo(buildEventTypeInfo(event.getEventType()));
                    }
                }

                Instant startInst = event.getStartTime();
                Long rawStartNs = null;
                if (startInst != null) {
                    long sec = startInst.getEpochSecond();
                    if (sec > Long.MIN_VALUE / 1_000_000_000L && sec < Long.MAX_VALUE / 1_000_000_000L) {
                        rawStartNs = sec * 1_000_000_000L + startInst.getNano();
                    }
                }

                if (rawStartNs != null) {
                    if (firstEvent[0]) {
                        startNanos[0] = rawStartNs;
                        firstEvent[0] = false;
                    }
                    Duration dur = event.getDuration();
                    long durNs = dur != null ? safeToNanos(dur) : 0L;
                    long endNs = rawStartNs + durNs;
                    if (endNs > endNanos[0]) endNanos[0] = endNs;
                }

                ReadStruct raw = event.getRawStruct();
                raw.ensureComplete();

                switch (typeName) {
                    case "jdk.JVMInformation" -> {
                        jvmVersion[0] = getStr(raw, "jvmVersion");
                        jvmArgs[0] = getStr(raw, "jvmArguments");
                        javaArgs[0] = getStr(raw, "javaArguments");
                        Object p2 = raw.get("pid");
                        if (p2 instanceof Long l) pid[0] = l;
                        else if (p2 instanceof Integer i) pid[0] = i;
                        Object jvmStart = raw.get("jvmStartTime");
                        if (jvmStart instanceof Instant inst) {
                            long sec = inst.getEpochSecond();
                            if (sec > Long.MIN_VALUE / 1_000_000_000L && sec < Long.MAX_VALUE / 1_000_000_000L) {
                                startNanos[0] = sec * 1_000_000_000L + inst.getNano();
                            }
                        }
                    }
                    case "jdk.CPUInformation" -> {
                        cpuModel[0] = getStr(raw, "cpu");
                        cpuCores[0] = getIntVal(raw, "cores");
                        cpuHwThreads[0] = getIntVal(raw, "hwThreads");
                    }
                    case "jdk.OSInformation" -> {
                        osVersion[0] = getStr(raw, "osVersion");
                    }
                }

                if (procRef[0] == null) {
                    prebuffer.add(snapshotEvent(typeName, raw, rawStartNs));
                    if (prebuffer.size() >= 64
                            || "jdk.JVMInformation".equals(typeName)
                            || "jdk.CPUInformation".equals(typeName)
                            || "jdk.OSInformation".equals(typeName)) {
                        if (startNanos[0] != 0L) {
                            ProcessorDrain.buildProcessorAndDrain(procRef, prebuffer, scratch,
                                    () -> seenTypes.forEach((n, t) ->
                                            procRef[0].registerEventTypeInfo(buildEventTypeInfo(t))),
                                    jvmVersion, jvmArgs, javaArgs,
                                    startNanos, endNanos,
                                    cpuModel, cpuCores, cpuHwThreads,
                                    osVersion, pid);
                            scratch.frameClassNames = null;
                            scratch.frameMethodNames = null;
                            scratch.frameDescriptors = null;
                            scratch.frameLineNumbers = null;
                            scratch.frameIsJava = null;
                            scratch.stackDepth = 0;
                        }
                    }
                    continue;
                }

                fillScratch(scratch, scratchFields, typeName, raw, rawStartNs);
                procRef[0].process(scratch);
            }
        }

        if (procRef[0] == null) {
            ProcessorDrain.buildProcessorAndDrain(procRef, prebuffer, scratch,
                    () -> seenTypes.forEach((n, t) ->
                            procRef[0].registerEventTypeInfo(buildEventTypeInfo(t))),
                    jvmVersion, jvmArgs, javaArgs,
                    startNanos, endNanos,
                    cpuModel, cpuCores, cpuHwThreads,
                    osVersion, pid);
        }

        JsonWriter w = new JsonWriter(1 << 20);
        procRef[0].writeProfile(w);
        String json = w.toJson();
        out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── Event type info ───────────────────────────────────────────────────────

    private static MarkerSchemas.JFREventTypeInfo buildEventTypeInfo(CJFREventType type) {
        String name = type.getName();
        String label = type.getLabel();
        boolean hasStackTrace = false;
        List<MarkerSchemas.FieldInfo> fields = new ArrayList<>();
        for (var f : type.getFields()) {
            String fname = f.name();
            if ("startTime".equals(fname) || "duration".equals(fname)
                    || "eventThread".equals(fname)) continue;
            if ("stackTrace".equals(fname)) {
                hasStackTrace = true;
                continue;
            }
            fields.add(new MarkerSchemas.FieldInfo(fname, f.typeName(), null, f.getLabel()));
        }
        return new MarkerSchemas.JFREventTypeInfo(
                name, label, null, new String[0],
                fields.toArray(new MarkerSchemas.FieldInfo[0]),
                hasStackTrace);
    }

    // ── Prebuffer snapshot ────────────────────────────────────────────────────

    private static PrebufferedEvent snapshotEvent(
            String typeName, ReadStruct raw, Long rawStartNs) {
        PrebufferedEvent pe = new PrebufferedEvent();
        pe.typeName = typeName;
        double sm = rawStartNs != null ? rawStartNs / 1_000_000.0 : 0.0;
        Duration dur = getDuration(raw);
        double dm = dur != null ? safeToNanos(dur) / 1_000_000.0 : 0.0;
        pe.startMs = sm;
        pe.endMs = sm + dm;
        pe.fields = extractFields(raw);
        pe.thread = extractThread(raw);
        ReadStruct stMap = getStruct(raw, "stackTrace");
        if (stMap != null) {
            List<?> frames = getFrameList(stMap);
            if (frames != null && !frames.isEmpty()) {
                int n = frames.size();
                pe.frameClassNames = new String[n];
                pe.frameMethodNames = new String[n];
                pe.frameDescriptors = new String[n];
                pe.frameLineNumbers = new int[n];
                pe.frameIsJava = new boolean[n];
                fillFrames(frames, n,
                        pe.frameClassNames, pe.frameMethodNames, pe.frameDescriptors,
                        pe.frameLineNumbers, pe.frameIsJava);
                pe.stackDepth = n;
            }
        }
        return pe;
    }

    // ── Scratch fill ──────────────────────────────────────────────────────────

    private static void fillScratch(
            Processor.ParsedEvent scratch, HashMap<String, Object> scratchFields,
            String typeName, ReadStruct raw, Long rawStartNs) {
        scratch.type = typeName;
        double sm = rawStartNs != null ? rawStartNs / 1_000_000.0 : 0.0;
        Duration dur = getDuration(raw);
        double dm = dur != null ? safeToNanos(dur) / 1_000_000.0 : 0.0;
        scratch.startMs = sm;
        scratch.endMs = sm + dm;

        scratchFields.clear();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            // Note: include sampledThread in fields (needed by Processor for execution samples)
            if ("startTime".equals(key) || "duration".equals(key)
                    || "eventThread".equals(key) || "stackTrace".equals(key)) continue;
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof ReadStruct nested) {
                flatten(scratchFields, key, nested);
            } else if (v instanceof Instant inst) {
                scratchFields.put(key, safeToEpochMilli(inst));
            } else if (v instanceof Duration d) {
                scratchFields.put(key, safeToNanos(d));
            } else {
                scratchFields.put(key, v);
            }
        }
        scratch.fields = scratchFields;
        // Fix: fall back to sampledThread for jdk.ExecutionSample events
        scratch.thread = extractThread(raw);

        ReadStruct stMap = getStruct(raw, "stackTrace");
        if (stMap == null) {
            scratch.stackDepth = 0;
            return;
        }
        List<?> frames = getFrameList(stMap);
        if (frames == null || frames.isEmpty()) {
            scratch.stackDepth = 0;
            return;
        }
        int n = frames.size();
        scratch.ensureFrameCapacity(n);
        fillFrames(frames, n,
                scratch.frameClassNames, scratch.frameMethodNames, scratch.frameDescriptors,
                scratch.frameLineNumbers, scratch.frameIsJava);
        scratch.stackDepth = n;
    }

    private static HashMap<String, Object> extractFields(ReadStruct raw) {
        HashMap<String, Object> out = new HashMap<>(raw.size());
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            if ("startTime".equals(key) || "duration".equals(key)
                    || "eventThread".equals(key) || "stackTrace".equals(key)) continue;
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof ReadStruct nested) flatten(out, key, nested);
            else if (v instanceof Instant inst) out.put(key, safeToEpochMilli(inst));
            else if (v instanceof Duration d) out.put(key, safeToNanos(d));
            else out.put(key, v);
        }
        return out;
    }

    /**
     * Extract thread from event. Falls back to sampledThread when eventThread is absent
     * (needed for jdk.ExecutionSample events in .cjfr format).
     */
    private static Processor.JFRThread extractThread(ReadStruct raw) {
        // Try eventThread first, then sampledThread (for jdk.ExecutionSample)
        ReadStruct tMap = getStruct(raw, "eventThread");
        if (tMap == null) tMap = getStruct(raw, "sampledThread");
        if (tMap == null) return null;
        String javaName = getStr(tMap, "javaName");
        String osName = getStr(tMap, "osName");
        long id = getLongOrDefault(tMap, "javaThreadId",
                getLongOrDefault(tMap, "osThreadId", -1L));
        Object virt = tMap.get("virtual");
        boolean isVirtual = virt instanceof Boolean b && b;
        return new Processor.JFRThread(id, javaName, osName, isVirtual);
    }

    @SuppressWarnings("unchecked")
    private static List<?> getFrameList(ReadStruct stMap) {
        Object v = stMap.get("frames");
        if (v instanceof ReadList<?> rl) {
            rl.ensureComplete();
            return rl;
        }
        if (v instanceof List<?> l) return l;
        return null;
    }

    private static void fillFrames(
            List<?> frames, int n,
            String[] classNames, String[] methodNames, String[] descriptors,
            int[] lineNumbers, boolean[] isJava) {
        for (int i = 0; i < n; i++) {
            Object rawFrame = frames.get(i);
            ReadStruct frame = rawFrame instanceof ReadStruct rs ? rs : null;
            if (frame == null) {
                classNames[i] = "";
                methodNames[i] = "";
                descriptors[i] = "";
                lineNumbers[i] = -1;
                isJava[i] = true;
                continue;
            }
            frame.ensureComplete();
            String frameType = getStr(frame, "type");
            isJava[i] = !"Native".equals(frameType);
            lineNumbers[i] = getIntVal(frame, "lineNumber");
            String methodName = "";
            String className = "";
            String descriptor = "";
            ReadStruct method = getStruct(frame, "method");
            if (method != null) {
                method.ensureComplete();
                String mn = getStr(method, "name");
                if (mn != null) methodName = mn;
                String dsc = getStr(method, "descriptor");
                if (dsc != null) descriptor = dsc;
                ReadStruct classStruct = getStruct(method, "type");
                if (classStruct != null) {
                    classStruct.ensureComplete();
                    String cn = getStr(classStruct, "name");
                    if (cn != null) className = cn;
                }
            }
            classNames[i] = className;
            methodNames[i] = methodName;
            descriptors[i] = descriptor;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static void flatten(HashMap<String, Object> out, String prefix, ReadStruct m) {
        m.ensureComplete();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String key = prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof ReadStruct nested) flatten(out, key, nested);
            else if (v instanceof Instant inst) out.put(key, safeToEpochMilli(inst));
            else if (v instanceof Duration d) out.put(key, safeToNanos(d));
            else out.put(key, v);
        }
    }

    private static ReadStruct getStruct(ReadStruct m, String key) {
        Object v = m.get(key);
        return v instanceof ReadStruct rs ? rs : null;
    }

    private static String getStr(ReadStruct m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        return v.toString();
    }

    private static Long getLong(ReadStruct m, String key) {
        Object v = m.get(key);
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return (long) i;
        return null;
    }

    private static long getLongOrDefault(ReadStruct m, String key, long def) {
        Long v = getLong(m, key);
        return v != null ? v : def;
    }

    private static int getIntVal(ReadStruct m, String key) {
        Object v = m.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return (int) (long) l;
        return 0;
    }

    private static Duration getDuration(ReadStruct raw) {
        Object v = raw.get("duration");
        if (v instanceof Duration d) return d;
        if (v instanceof Long nanos) return Duration.ofNanos(nanos);
        return null;
    }

    private static long safeToNanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException e) {
            return d.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static long safeToEpochMilli(Instant inst) {
        try {
            return inst.toEpochMilli();
        } catch (ArithmeticException e) {
            return inst.isBefore(Instant.EPOCH) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private CJFRConvert() {}
}
