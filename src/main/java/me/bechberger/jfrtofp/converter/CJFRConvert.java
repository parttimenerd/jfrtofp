package me.bechberger.jfrtofp.converter;

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

/** Converts a .cjfr file to Firefox Profiler JSON using a two-pass approach. */
public final class CJFRConvert {

    public static void convert(Path path, ConverterConfig config, OutputStream out)
            throws Exception {

        // ── Pass 1: collect metadata ────────────────────────────────────────
        String[] jvmVersion   = {null};
        String[] jvmArgs      = {null};
        String[] javaArgs     = {null};
        long[]   startNanos   = {0L};
        long[]   endNanos     = {0L};
        String[] cpuModel     = {null};
        int[]    cpuCores     = {0};
        int[]    cpuHwThreads = {0};
        String[] osVersion    = {null};
        long[]   pid          = {-1L};
        boolean[] firstEvent  = {true};

        LinkedHashMap<String, CJFREventType> seenTypes = new LinkedHashMap<>();

        try (CJFRFile file = CJFRFile.open(path, Options.defaults().withReconstitution(false))) {
            CJFREvent event;
            while ((event = file.readEvent()) != null) {
                String typeName = event.getEventType().getName();
                seenTypes.putIfAbsent(typeName, event.getEventType());

                Instant startInst = event.getStartTime();
                Long rawStartNs = toRawNs(startInst);

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

                switch (typeName) {
                    case "jdk.JVMInformation" -> {
                        ReadStruct raw = event.getRawStruct();
                        raw.ensureComplete();
                        jvmVersion[0] = getStr(raw, "jvmVersion");
                        jvmArgs[0]    = getStr(raw, "jvmArguments");
                        javaArgs[0]   = getStr(raw, "javaArguments");
                        Object p2 = raw.get("pid");
                        if (p2 instanceof Long l) pid[0] = l;
                        else if (p2 instanceof Integer i) pid[0] = i;
                        Object jvmStart = raw.get("jvmStartTime");
                        if (jvmStart instanceof Instant inst) {
                            Long ns = toRawNs(inst);
                            if (ns != null) startNanos[0] = ns;
                        }
                    }
                    case "jdk.CPUInformation" -> {
                        ReadStruct raw = event.getRawStruct();
                        raw.ensureComplete();
                        cpuModel[0]     = getStr(raw, "cpu");
                        cpuCores[0]     = getIntVal(raw, "cores");
                        cpuHwThreads[0] = getIntVal(raw, "hwThreads");
                    }
                    case "jdk.OSInformation" -> {
                        ReadStruct raw = event.getRawStruct();
                        raw.ensureComplete();
                        osVersion[0] = getStr(raw, "osVersion");
                    }
                }
            }
        }

        // ── Build Processor ──────────────────────────────────────────────────
        Processor.JFRMetadata meta = new Processor.JFRMetadata(
                jvmVersion[0], jvmArgs[0], javaArgs[0],
                startNanos[0] / 1_000_000.0,
                endNanos[0]   / 1_000_000.0,
                cpuModel[0],
                cpuCores[0]     != 0 ? cpuCores[0]     : null,
                cpuHwThreads[0] != 0 ? cpuHwThreads[0] : null,
                osVersion[0], pid[0], path.toString());
        Processor proc = new Processor(config, meta);

        for (Map.Entry<String, CJFREventType> e : seenTypes.entrySet()) {
            proc.registerEventTypeInfo(buildEventTypeInfo(e.getValue()));
        }

        // ── Pass 2: process all events ───────────────────────────────────────
        Processor.ParsedEvent scratch = new Processor.ParsedEvent();
        HashMap<String, Object> scratchFields = new HashMap<>();

        try (CJFRFile file = CJFRFile.open(path, Options.defaults().withReconstitution(false))) {
            CJFREvent event;
            while ((event = file.readEvent()) != null) {
                String typeName = event.getEventType().getName();

                if (!seenTypes.containsKey(typeName)) {
                    seenTypes.put(typeName, event.getEventType());
                    proc.registerEventTypeInfo(buildEventTypeInfo(event.getEventType()));
                }

                Instant startInst = event.getStartTime();
                Long rawStartNs = toRawNs(startInst);

                ReadStruct raw = event.getRawStruct();
                raw.ensureComplete();
                fillScratch(scratch, scratchFields, typeName, raw, rawStartNs);
                proc.process(scratch);
            }
        }

        JsonWriter w = new JsonWriter(1 << 20);
        proc.writeProfile(w);
        out.write(w.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        long javaId = getLongOrDefault(tMap, "javaThreadId", 0L);
        // javaThreadId=0 means no Java thread ID (native/GC thread); use osThreadId for uniqueness.
        long id = javaId != 0L ? javaId : getLongOrDefault(tMap, "osThreadId", -1L);
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

    private static Long toRawNs(Instant inst) {
        if (inst == null) return null;
        long sec = inst.getEpochSecond();
        if (sec <= Long.MIN_VALUE / 1_000_000_000L || sec >= Long.MAX_VALUE / 1_000_000_000L) {
            return null;
        }
        return sec * 1_000_000_000L + inst.getNano();
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
