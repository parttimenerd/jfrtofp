package me.bechberger.jfrtofp.converter;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.metadata.AbstractMetadataElement;
import io.jafar.parser.internal_api.metadata.MetadataAnnotation;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts a .jfr file to Firefox Profiler JSON using a two-pass approach. */
public final class JFRConvert {

    public static void convert(Path path, ConverterConfig config, OutputStream out)
            throws Exception {

        // ── Pass 1: collect metadata ────────────────────────────────────────
        // Read jvmVersion, jvmArgs, javaArgs, cpuModel, cpuCores, cpuHwThreads,
        // osVersion, pid, startNanos, endNanos, and event type metadata.
        // This pass is cheap: we collect only a handful of fields.

        String[] jvmVersion   = {null};
        String[] jvmArgs      = {null};
        String[] javaArgs     = {null};
        long[]   startNanos   = {0L};
        long[]   endNanos     = {0L};
        double[] jvmStartMs   = {Double.NaN};  // milliseconds from jvmStartTime, precise
        String[] cpuModel     = {null};
        int[]    cpuCores     = {0};
        int[]    cpuHwThreads = {0};
        String[] osVersion    = {null};
        long[]   pid          = {-1L};
        boolean[] firstEvent  = {true};

        LinkedHashMap<String, MetadataClass> seenTypes = new LinkedHashMap<>();

        try (UntypedJafarParser p = UntypedJafarParser.open(
                path, ParsingContext.create(), UntypedStrategy.FULL_ITERATION)) {
            p.handle((type, value, ctl) -> { synchronized (seenTypes) {
                String typeName = type.getName();
                seenTypes.putIfAbsent(typeName, type);

                Long rawStart = getLong(value, "startTime");
                if (rawStart != null) {
                    if (firstEvent[0]) {
                        startNanos[0] = rawStart;
                        firstEvent[0] = false;
                    }
                    Long rawDuration = getLong(value, "duration");
                    long endNs = rawStart + (rawDuration != null ? rawDuration : 0L);
                    if (endNs > endNanos[0]) endNanos[0] = endNs;
                }

                switch (typeName) {
                    case "jdk.JVMInformation" -> {
                        jvmVersion[0] = getStr(value, "jvmVersion");
                        jvmArgs[0]    = getStr(value, "jvmArguments");
                        javaArgs[0]   = getStr(value, "javaArguments");
                        Object p2 = value.get("pid");
                        if (p2 instanceof Long l) pid[0] = l;
                        else if (p2 instanceof Integer i) pid[0] = i;
                        Long jvmStart = getTimestampMs(value, "jvmStartTime");
                        if (jvmStart != null) jvmStartMs[0] = (double) jvmStart;
                    }
                    case "jdk.CPUInformation" -> {
                        cpuModel[0]     = getStr(value, "cpu");
                        cpuCores[0]     = getIntVal(value, "cores");
                        cpuHwThreads[0] = getIntVal(value, "hwThreads");
                    }
                    case "jdk.OSInformation" -> {
                        osVersion[0] = getStr(value, "osVersion");
                    }
                }
            }});
            p.run();
        }

        // ── Build Processor from metadata ────────────────────────────────────
        // Use jvmStartMs if available (precise ms); fall back to first-event nanos/1e6.
        double startMs = !Double.isNaN(jvmStartMs[0]) ? jvmStartMs[0] : startNanos[0] / 1_000_000.0;
        Processor.JFRMetadata meta = new Processor.JFRMetadata(
                jvmVersion[0], jvmArgs[0], javaArgs[0],
                startMs,
                endNanos[0]   / 1_000_000.0,
                cpuModel[0],
                cpuCores[0]     != 0 ? cpuCores[0]     : null,
                cpuHwThreads[0] != 0 ? cpuHwThreads[0] : null,
                osVersion[0], pid[0], path.toString());
        Processor proc = new Processor(config, meta);

        // Register all event types discovered in pass 1
        for (Map.Entry<String, MetadataClass> e : seenTypes.entrySet()) {
            proc.registerEventTypeInfo(buildEventTypeInfo(e.getKey(), e.getValue()));
        }

        // ── Pass 2: process all events ───────────────────────────────────────
        Processor.ParsedEvent scratch  = new Processor.ParsedEvent();
        HashMap<String, Object> scratchFields = new HashMap<>();

        try (UntypedJafarParser p = UntypedJafarParser.open(
                path, ParsingContext.create(), UntypedStrategy.FULL_ITERATION)) {
            p.handle((type, value, ctl) -> { synchronized (proc) {
                String typeName = type.getName();

                // Register any type seen for the first time in pass 2
                // (unlikely but possible if chunk ordering changes)
                if (!seenTypes.containsKey(typeName)) {
                    seenTypes.put(typeName, type);
                    proc.registerEventTypeInfo(buildEventTypeInfo(typeName, type));
                }

                Long rawStart = getLong(value, "startTime");
                fillScratch(scratch, scratchFields, typeName, value, rawStart);
                proc.process(scratch);
            }});
            p.run();
        }

        JsonWriter w = new JsonWriter(1 << 20);
        proc.writeProfile(w);
        out.write(w.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void fillScratch(
            Processor.ParsedEvent scratch, HashMap<String, Object> scratchFields,
            String typeName, Map<String, Object> value, Long rawStartNs) {
        scratch.type = typeName;
        double sm = rawStartNs != null ? rawStartNs / 1_000_000.0 : 0.0;
        Long rawDuration = getLong(value, "duration");
        double dm = rawDuration != null ? rawDuration / 1_000_000.0 : 0.0;
        scratch.startMs = sm;
        scratch.endMs = sm + dm;

        scratchFields.clear();
        for (Map.Entry<String, Object> e : value.entrySet()) {
            String key = e.getKey();
            if ("startTime".equals(key) || "duration".equals(key)
                    || "eventThread".equals(key) || "stackTrace".equals(key)) continue;
            Object v = unwrap(e.getValue());
            if (v == null) continue;
            if (v instanceof Map<?, ?> m) {
                flatten(scratchFields, key, m);
            } else {
                scratchFields.put(key, v);
            }
        }
        scratch.fields = scratchFields;
        scratch.thread = extractThread(value);

        Object stVal = value.get("stackTrace");
        Map<String, Object> stMap = asMap(stVal);
        if (stMap == null) {
            scratch.stackDepth = 0;
            return;
        }
        Object[] rawFrames = asObjectArray(stMap.get("frames"));
        if (rawFrames == null || rawFrames.length == 0) {
            scratch.stackDepth = 0;
            return;
        }
        int n = rawFrames.length;
        scratch.ensureFrameCapacity(n);
        fillFrames(rawFrames, n,
            scratch.frameClassNames, scratch.frameMethodNames, scratch.frameDescriptors,
            scratch.frameLineNumbers, scratch.frameIsJava);
        scratch.stackDepth = n;
    }

    private static Processor.JFRThread extractThread(Map<String, Object> value) {
        // Prefer sampledThread (execution samples), fall back to eventThread.
        Object threadVal = value.get("sampledThread");
        if (threadVal == null) threadVal = value.get("eventThread");
        Map<String, Object> tMap = asMap(threadVal);
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

    private static void fillFrames(Object[] rawFrames, int n,
                                   String[] classNames, String[] methodNames,
                                   String[] descriptors, int[] lineNumbers,
                                   boolean[] isJava) {
        for (int i = 0; i < n; i++) {
            Map<String, Object> frame = asMap(rawFrames[i]);
            if (frame == null) {
                classNames[i] = "";
                methodNames[i] = "";
                descriptors[i] = "";
                lineNumbers[i] = -1;
                isJava[i] = true;
                continue;
            }
            String frameType = getStr(frame, "type");
            isJava[i] = !"Native".equals(frameType);
            lineNumbers[i] = getIntVal(frame, "lineNumber");
            String methodName = "";
            String className = "";
            String descriptor = "";
            Object methodVal = frame.get("method");
            Map<String, Object> method = asMap(methodVal);
            if (method != null) {
                String mn = getStr(method, "name");
                if (mn != null) methodName = mn;
                String dsc = getStr(method, "descriptor");
                if (dsc != null) descriptor = dsc;
                Object typeVal = method.get("type");
                Map<String, Object> classMap = asMap(typeVal);
                if (classMap != null) {
                    String cn = getStr(classMap, "name");
                    if (cn != null) className = cn;
                } else if (typeVal instanceof String s) {
                    className = s;
                }
            }
            classNames[i] = className;
            methodNames[i] = methodName;
            descriptors[i] = descriptor;
        }
    }

    private static void flatten(HashMap<String, Object> out, String prefix, Map<?, ?> m) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = prefix + "." + e.getKey();
            Object v = unwrap(e.getValue());
            if (v == null) continue;
            if (v instanceof Map<?, ?> nested) flatten(out, key, nested);
            else out.put(key, v);
        }
    }

    private static MarkerSchemas.JFREventTypeInfo buildEventTypeInfo(
            String name, MetadataClass type) {
        String label = annotationValue(type, "jdk.jfr.Label");
        String description = annotationValue(type, "jdk.jfr.Description");
        String category = annotationValue(type, "jdk.jfr.Category");
        String[] categoryNames = category != null ? new String[]{category} : new String[0];

        boolean hasStackTrace = false;
        ArrayList<MarkerSchemas.FieldInfo> fields = new ArrayList<>();
        List<MetadataField> mfields = type.getFields();
        if (mfields != null) {
            for (MetadataField f : mfields) {
                String fname = f.getName();
                if ("startTime".equals(fname) || "duration".equals(fname)
                        || "eventThread".equals(fname)) continue;
                if ("stackTrace".equals(fname)) {
                    hasStackTrace = true;
                    continue;
                }
                MetadataClass ftype = f.getType();
                String typeName = ftype != null ? ftype.getName() : "java.lang.String";
                String fieldLabel = annotationValue(f, "jdk.jfr.Label");
                String contentType = annotationValue(f, "jdk.jfr.ContentType");
                fields.add(new MarkerSchemas.FieldInfo(fname, typeName, contentType, fieldLabel));
            }
        }
        return new MarkerSchemas.JFREventTypeInfo(
            name, label, description, categoryNames,
            fields.toArray(new MarkerSchemas.FieldInfo[0]),
            hasStackTrace);
    }

    private static String annotationValue(AbstractMetadataElement el, String annotationName) {
        List<MetadataAnnotation> anns;
        try {
            if (el instanceof MetadataClass mc) anns = mc.getAnnotations();
            else if (el instanceof MetadataField mf) anns = mf.getAnnotations();
            else return null;
        } catch (Throwable t) {
            return null;
        }
        if (anns == null) return null;
        for (MetadataAnnotation a : anns) {
            MetadataClass at = a.getType();
            if (at != null && annotationName.equals(at.getName())) {
                return a.getValue();
            }
        }
        return null;
    }

    private static String getStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof Map<?, ?> inner) {
            Object s = inner.get("string");
            if (s != null) return s.toString();
        }
        if (v instanceof ComplexType ct) {
            Object inner = ct.getValue();
            if (inner instanceof Map<?, ?> innerMap) {
                Object s = innerMap.get("string");
                if (s != null) return s.toString();
            }
            return inner != null ? inner.toString() : null;
        }
        return v.toString();
    }

    private static Long getLong(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return (long) i;
        return null;
    }

    /**
     * Reads a JFR timestamp field that may be stored as a raw Long (millis since epoch)
     * or as a ComplexType wrapping a Long. Returns epoch milliseconds or null.
     */
    private static Long getTimestampMs(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return (long) i;
        if (v instanceof ComplexType ct) {
            Object inner = ct.getValue();
            if (inner instanceof Long l) return l;
            if (inner instanceof Integer i) return (long) i;
            if (inner instanceof Map<?, ?> innerMap) {
                Object epochMs = innerMap.get("epochMs");
                if (epochMs instanceof Long l) return l;
                if (epochMs instanceof Integer i) return (long) i;
            }
        }
        return null;
    }

    private static long getLongOrDefault(Map<String, Object> m, String key, long def) {
        Long v = getLong(m, key);
        return v != null ? v : def;
    }

    private static int getIntVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return (int) (long) l;
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof ComplexType ct) v = ct.getValue();
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }

    private static Object[] asObjectArray(Object v) {
        if (v instanceof ArrayType at) v = at.getArray();
        if (v instanceof Object[] arr) return arr;
        return null;
    }

    private static Object unwrap(Object v) {
        if (v instanceof ComplexType ct) v = ct.getValue();
        if (v instanceof ArrayType at) return at.getArray();
        if (v instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("string")) {
            return m.get("string");
        }
        return v;
    }

    private JFRConvert() {}
}
