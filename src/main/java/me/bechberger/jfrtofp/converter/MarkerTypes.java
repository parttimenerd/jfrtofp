package me.bechberger.jfrtofp.converter;

import java.util.HashMap;
import java.util.Map;

/**
 * Port of marker-types.ts. Each marker type knows two things:
 *  - how to format its value into the marker.data object (writeValue)
 *  - the format string emitted in markerSchema fields[].format
 *
 * <p>Field values arrive as boxed Java primitives (Long, Integer, Double, Float,
 * Boolean, String) or null, exactly as produced by the jafar parser. Converters
 * write JSON directly into the JsonWriter to avoid intermediate allocations.
 */
public final class MarkerTypes {

    /** The format string written as a field's `format` in profile.meta.markerSchema. */
    public interface Format {
        void writeFormat(JsonWriter w);
    }

    public static final Format STRING_FMT     = w -> w.value("string");
    public static final Format URL_FMT        = w -> w.value("url");
    public static final Format FILE_PATH_FMT  = w -> w.value("file-path");
    public static final Format DURATION_FMT   = w -> w.value("duration");
    public static final Format TIME_FMT       = w -> w.value("time");
    public static final Format SECONDS_FMT    = w -> w.value("seconds");
    public static final Format MILLIS_FMT     = w -> w.value("milliseconds");
    public static final Format MICROS_FMT     = w -> w.value("microseconds");
    public static final Format NANOS_FMT      = w -> w.value("nanoseconds");
    public static final Format BYTES_FMT      = w -> w.value("bytes");
    public static final Format PERCENTAGE_FMT = w -> w.value("percentage");
    public static final Format INTEGER_FMT    = w -> w.value("integer");
    public static final Format DECIMAL_FMT    = w -> w.value("decimal");
    public static final Format LIST_FMT       = w -> w.value("list");

    /** {columns:[{},{}]} — no 'type' key, matching Kotlin's encodeDefaults=false behavior */
    public static final Format TABLE_FMT = w -> {
        w.beginObject();
        w.key("columns").beginArray().beginObject().endObject().beginObject().endObject().endArray();
        w.endObject();
    };

    /**
     * A converter for one marker type. Reads `value` (the raw boxed primitive
     * from the jafar parser) and writes its converted JSON form to `w`. The
     * `tables` reference is used by types that need access to the recording
     * start time (TIMESTAMP, MILLIS) or tables (TABLE).
     */
    public interface Converter {
        void writeValue(JsonWriter w, Tables tables, Object value);
    }

    /** Marker type: Format + Converter + flags. */
    public static final class Type {
        public final String name;
        public final Format format;
        public final Converter convert;
        public final boolean generic;

        public Type(String name, Format format, Converter convert, boolean generic) {
            this.name = name;
            this.format = format;
            this.convert = convert;
            this.generic = generic;
        }
    }

    // ── Helpers for primitive coercion ───────────────────────────────────────

    static double asDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    static long asLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof Boolean b) return b ? 1L : 0L;
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return 0L; }
    }

    static String asString(Object v) {
        return v == null ? "" : v.toString();
    }

    // ── Concrete converters ──────────────────────────────────────────────────

    private static final Converter STRING_CONV = (w, t, v) -> w.value(asString(v));

    private static final Converter NUM_CONV = (w, t, v) -> w.value(asDouble(v));

    private static final Converter ADDRESS_CONV = (w, t, v) -> {
        long n = asLong(v);
        // Mask to 64-bit unsigned representation, write hex.
        w.value("0x" + Long.toUnsignedString(n, 16));
    };

    private static final Converter TIMESPAN_CONV = (w, t, v) -> w.value(asDouble(v) / 1_000_000.0);

    private static final Converter NANOS_CONV = TIMESPAN_CONV;

    private static final Converter MILLIS_CONV = (w, t, v) -> {
        double startMs = t != null ? t.startTimeMs : 0.0;
        w.value(asDouble(v) - startMs);
    };

    private static final Converter TIMESTAMP_CONV = (w, t, v) -> {
        double startMs = t != null ? t.startTimeMs : 0.0;
        double val = asDouble(v);
        // Some JFR producers emit timestamps in nanoseconds-from-some-arbitrary-epoch.
        // Mirror the TS heuristic: divide by 1000 until val is in the same magnitude as startMs.
        while (val > startMs * 100.0) val /= 1000.0;
        w.value(val - startMs);
    };

    private static final Converter PERCENTAGE_CONV = (w, t, v) -> w.value(asDouble(v));
    private static final Converter EVENT_THREAD_CONV = (w, t, v) -> w.value(asString(v));
    private static final Converter STACKTRACE_CONV = (w, t, v) -> w.value(asLong(v));
    private static final Converter BPS_CONV = (w, t, v) -> w.value(asDouble(v));
    private static final Converter BITS_PS_CONV = (w, t, v) -> w.value(asDouble(v) / 8.0);

    private static final long[][] MODIFIER_FLAGS = {
        {0x0001L, 0}, {0x0002L, 1}, {0x0004L, 2}, {0x0008L, 3},
        {0x0010L, 4}, {0x0020L, 5}, {0x0040L, 6}, {0x0080L, 7},
        {0x0100L, 8}, {0x0200L, 9}, {0x0400L, 10}, {0x0800L, 11},
    };
    private static final String[] MODIFIER_NAMES = {
        "public", "private", "protected", "static",
        "final", "synchronized", "volatile", "transient",
        "native", "interface", "abstract", "strict",
    };

    private static final Converter MODIFIERS_CONV = (w, t, v) -> {
        long n = asLong(v);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < MODIFIER_FLAGS.length; i++) {
            if ((n & MODIFIER_FLAGS[i][0]) != 0) {
                if (!first) sb.append(' ');
                first = false;
                sb.append(MODIFIER_NAMES[(int) MODIFIER_FLAGS[i][1]]);
            }
        }
        w.value(sb.toString());
    };

    // ── The registry ─────────────────────────────────────────────────────────

    public static final Type BOOLEAN     = new Type("BOOLEAN", STRING_FMT, STRING_CONV, false);
    public static final Type BYTES       = new Type("BYTES", BYTES_FMT, NUM_CONV, false);
    public static final Type ADDRESS     = new Type("ADDRESS", STRING_FMT, ADDRESS_CONV, false);
    public static final Type INT         = new Type("INT", INTEGER_FMT, NUM_CONV, false);
    public static final Type LONG        = new Type("LONG", INTEGER_FMT, NUM_CONV, true);
    public static final Type FLOAT       = new Type("FLOAT", DECIMAL_FMT, NUM_CONV, true);
    public static final Type DOUBLE      = new Type("DOUBLE", DECIMAL_FMT, NUM_CONV, true);
    public static final Type STRING      = new Type("STRING", STRING_FMT, STRING_CONV, true);
    public static final Type MILLIS      = new Type("MILLIS", MILLIS_FMT, MILLIS_CONV, false);
    public static final Type TIMESTAMP   = new Type("TIMESTAMP", INTEGER_FMT, TIMESTAMP_CONV, false);
    public static final Type TIMESPAN    = new Type("TIMESPAN", DURATION_FMT, TIMESPAN_CONV, false);
    public static final Type NANOS       = new Type("NANOS", MILLIS_FMT, NANOS_CONV, false);
    public static final Type PERCENTAGE  = new Type("PERCENTAGE", PERCENTAGE_FMT, PERCENTAGE_CONV, false);
    public static final Type EVENT_THREAD= new Type("EVENT_THREAD", STRING_FMT, EVENT_THREAD_CONV, false);
    public static final Type STACKTRACE  = new Type("STACKTRACE", INTEGER_FMT, STACKTRACE_CONV, false);
    public static final Type BPS         = new Type("BYTES_PER_SECOND", BYTES_FMT, BPS_CONV, false);
    public static final Type BITS_PS     = new Type("BITS_PER_SECOND", BYTES_FMT, BITS_PS_CONV, false);
    public static final Type PATH        = new Type("PATH", FILE_PATH_FMT, STRING_CONV, false);
    public static final Type CLASS       = new Type("CLASS", STRING_FMT, STRING_CONV, false);
    public static final Type METHOD      = new Type("METHOD", STRING_FMT, STRING_CONV, false);
    public static final Type MODIFIERS   = new Type("MODIFIERS", STRING_FMT, MODIFIERS_CONV, false);
    public static final Type EPOCH_MILLIS= new Type("EPOCH_MILLIS", MILLIS_FMT, NUM_CONV, false);
    public static final Type TICKS       = new Type("TICKS", INTEGER_FMT, NUM_CONV, false);
    public static final Type TICKSPAN    = new Type("TICKSPAN", INTEGER_FMT, NUM_CONV, false);
    public static final Type TABLE       = new Type("TABLE", TABLE_FMT, STRING_CONV, true);
    public static final Type UBYTE       = new Type("UBYTE", INTEGER_FMT, NUM_CONV, true);
    public static final Type UNSIGNED    = new Type("UNSIGNED", INTEGER_FMT, NUM_CONV, true);
    public static final Type UINT        = new Type("UINT", INTEGER_FMT, NUM_CONV, true);
    public static final Type USHORT      = new Type("USHORT", INTEGER_FMT, NUM_CONV, true);
    public static final Type ULONG       = new Type("ULONG", INTEGER_FMT, NUM_CONV, true);

    // Lookup tables built from above.
    private static final Map<String, Type> FIELD_NAME_ALIASES = new HashMap<>();
    private static final Map<String, Type> TYPE_NAME_MAP = new HashMap<>();

    static {
        // Bytes-name aliases
        for (String n : new String[] {
            "dataAmount", "allocated", "totalSize", "usedSize", "initialSize",
            "reservedSize", "nonNMethodSize", "profiledSize", "nonProfiledSize",
            "expansionSize", "minBlockLength", "minSize", "maxSize",
            "osrBytesCompiled", "minTLABSize", "tlabRefillWasteLimit",
        }) {
            FIELD_NAME_ALIASES.put(n.toLowerCase(), BYTES);
        }
        // Address-name aliases
        for (String n : new String[] {
            "baseAddress", "topAddress", "startAddress", "reservedTopAddress",
            "objectAlignment", "classLoaderData",
        }) {
            FIELD_NAME_ALIASES.put(n.toLowerCase(), ADDRESS);
        }

        // Type-name lookup (lowercased + stripped of underscores)
        Type[] all = {
            BOOLEAN, BYTES, ADDRESS, INT, LONG, FLOAT, DOUBLE, STRING,
            MILLIS, TIMESTAMP, TIMESPAN, NANOS, PERCENTAGE, EVENT_THREAD,
            STACKTRACE, BPS, BITS_PS, PATH, CLASS, METHOD, MODIFIERS,
            EPOCH_MILLIS, TICKS, TICKSPAN, TABLE, UBYTE, UNSIGNED, UINT,
            USHORT, ULONG,
        };
        for (Type t : all) {
            TYPE_NAME_MAP.put(canonName(t.name), t);
        }
        // Extra string-aliased type names (mirror the TS list)
        for (String alias : new String[] {
            "COMPILER_PHASE_TYPE", "COMPILER_TYPE", "DEOPTIMIZATION_ACTION",
            "DEOPTIMIZATION_REASON", "FLAG_VALUE_ORIGIN", "FRAME_TYPE",
            "G1_HEAP_REGION_TYPE", "G1_YC_TYPE", "GC_CAUSE", "GC_NAME",
            "GC_THRESHHOLD_UPDATER", "GC_WHEN", "INFLATE_CAUSE",
            "METADATA_TYPE", "METASPACE_OBJECT_TYPE", "NARROW_OOP_MODE",
            "NETWORK_INTERFACE_NAME", "OLD_OBJECT_ROOT_TYPE",
            "OLD_OBJECT_ROOT_SYSTEM", "REFERENCE_TYPE",
            "ShenandoahHeapRegionState", "SYMBOL", "ThreadState",
            "VMOperationType", "ZPageTypeType", "ZStatisticsCounterType",
            "ZStatisticsSamplerType",
        }) {
            TYPE_NAME_MAP.put(canonName(alias), STRING);
        }
    }

    private static String canonName(String s) {
        return s.toLowerCase().replace("_", "");
    }

    private static final java.util.Set<String> BYTE_FIELD_NAMES = java.util.Set.of(
        "committed", "reserved", "used", "gcThreshold", "unallocatedCapacity"
    );

    /**
     * Mirror of resolveMarkerType from TS. Decides which Type best fits a field
     * given its field name, declared JFR type name, and JFR content-type
     * annotation.
     */
    public static Type resolveMarkerType(
            String fieldName, String typeName, String contentType) {
        String lower = fieldName.toLowerCase();
        if (lower.endsWith("pointer")) return ADDRESS;
        if (fieldName.endsWith("Size") || BYTE_FIELD_NAMES.contains(fieldName)) return BYTES;

        Type contentResult = null;
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            int dot = ct.lastIndexOf('.');
            if (dot >= 0) ct = ct.substring(dot + 1);
            ct = ct.replace("_", "");
            contentResult = TYPE_NAME_MAP.get(ct);
        }

        Type nameResult = FIELD_NAME_ALIASES.get(lower);
        if (nameResult == null) nameResult = TYPE_NAME_MAP.get(lower);
        if (nameResult == null) {
            String t = typeName == null ? "" : typeName.toLowerCase();
            int dot = t.lastIndexOf('.');
            if (dot >= 0) t = t.substring(dot + 1);
            t = t.replace("_", "");
            nameResult = TYPE_NAME_MAP.get(t);
        }
        if (nameResult == null) nameResult = TABLE;

        if (nameResult != TABLE && contentResult != null && contentResult.generic) {
            return nameResult;
        }
        return contentResult != null ? contentResult : nameResult;
    }
}
