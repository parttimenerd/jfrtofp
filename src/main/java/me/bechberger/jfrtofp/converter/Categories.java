package me.bechberger.jfrtofp.converter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of categories.ts (originally CategoryE.kt). Defines the fixed list of
 * category buckets used by the Firefox Profiler view, with mutable subcategory
 * lists that grow as new (category, subcategory) pairs are observed during
 * conversion.
 */
public final class Categories {

    public static final class Entry {
        public final String displayName;
        public final String color;
        public final List<String> subcategories;
        public final int index;

        Entry(String displayName, String color, List<String> subcategories, int index) {
            this.displayName = displayName;
            this.color = color;
            this.subcategories = subcategories;
            this.index = index;
        }
    }

    private static final List<Entry> ALL = new ArrayList<>();
    private static final Map<String, Entry> BY_NAME = new HashMap<>();

    public static final Entry OTHER;
    public static final Entry JAVA;
    public static final Entry NON_PROJECT_JAVA;
    public static final Entry GC;
    public static final Entry CPP;
    public static final Entry JFR;
    public static final Entry JAVA_APPLICATION;
    public static final Entry JAVA_APPLICATION_STATS;
    public static final Entry JVM_CLASSLOADING;
    public static final Entry JVM_CODE_CACHE;
    public static final Entry JVM_COMPILATION_OPT;
    public static final Entry JVM_COMPILATION;
    public static final Entry JVM_DIAGNOSTICS;
    public static final Entry JVM_FLAG;
    public static final Entry JVM_GC_COLLECTOR;
    public static final Entry JVM_GC_CONF;
    public static final Entry JVM_GC_DETAILED;
    public static final Entry JVM_GC_HEAP;
    public static final Entry JVM_GC_METASPACE;
    public static final Entry JVM_GC_PHASES;
    public static final Entry JVM_GC_REFERENCE;
    public static final Entry JVM_INTERNAL;
    public static final Entry JVM_PROFILING;
    public static final Entry JVM_RUNTIME_MODULES;
    public static final Entry JVM_RUNTIME_SAFEPOINT;
    public static final Entry JVM_RUNTIME_TABLES;
    public static final Entry JVM_RUNTIME;
    public static final Entry JVM;
    public static final Entry OS_MEMORY;
    public static final Entry OS_NETWORK;
    public static final Entry OS_PROCESS;
    public static final Entry OS;
    public static final Entry MISC;

    static {
        OTHER = create("Other", "grey", "Profiling", "Waiting");
        JAVA = create("Java", "blue", "Other", "Interpreted", "Compiled", "Native", "Inlined");
        NON_PROJECT_JAVA = create("Java (non-project)", "darkgray",
            "Other", "Interpreted", "Compiled", "Native", "Inlined");
        GC = create("GC", "orange", "Other");
        CPP = create("Native", "red", "Other");
        JFR = create("Flight Recorder", "lightgrey");
        JAVA_APPLICATION = create("Java Application", "red");
        JAVA_APPLICATION_STATS = create("Java Application, Statistics", "grey");
        JVM_CLASSLOADING = create("Java Virtual Machine, Class Loading", "brown");
        JVM_CODE_CACHE = create("Java Virtual Machine, Code Cache", "lightbrown");
        JVM_COMPILATION_OPT = create("Java Virtual Machine, Compiler, Optimization", "lightblue");
        JVM_COMPILATION = create("Java Virtual Machine, Compiler", "lightblue");
        JVM_DIAGNOSTICS = create("Java Virtual Machine, Diagnostics", "lightgrey");
        JVM_FLAG = create("Java Virtual Machine, Flag", "lightgrey");
        JVM_GC_COLLECTOR = create("Java Virtual Machine, GC, Collector", "orange");
        JVM_GC_CONF = create("Java Virtual Machine, GC, Configuration", "lightgrey");
        JVM_GC_DETAILED = create("Java Virtual Machine, GC, Detailed", "lightorange");
        JVM_GC_HEAP = create("Java Virtual Machine, GC, Heap", "lightorange");
        JVM_GC_METASPACE = create("Java Virtual Machine, GC, Metaspace", "lightorange");
        JVM_GC_PHASES = create("Java Virtual Machine, GC, Phases", "lightorange");
        JVM_GC_REFERENCE = create("Java Virtual Machine, GC, Reference", "lightorange");
        JVM_INTERNAL = create("Java Virtual Machine, Internal", "lightgrey");
        JVM_PROFILING = create("Java Virtual Machine, Profiling", "lightgrey");
        JVM_RUNTIME_MODULES = create("Java Virtual Machine, Runtime, Modules", "lightgrey");
        JVM_RUNTIME_SAFEPOINT = create("Java Virtual Machine, Runtime, Safepoint", "yellow");
        JVM_RUNTIME_TABLES = create("Java Virtual Machine, Runtime, Tables", "lightgrey");
        JVM_RUNTIME = create("Java Virtual Machine, Runtime", "green");
        JVM = create("Java Virtual Machine", "lightgrey");
        OS_MEMORY = create("Operating System, Memory", "lightgrey");
        OS_NETWORK = create("Operating System, Network", "lightgrey");
        OS_PROCESS = create("Operating System, Processor", "lightgrey");
        OS = create("Operating System", "lightgrey");
        MISC = create("Misc", "lightgrey", "Other");
    }

    private static Entry create(String displayName, String color, String... subcategories) {
        List<String> subs = new ArrayList<>();
        for (String s : subcategories) subs.add(s);
        Entry e = new Entry(displayName, color, subs, ALL.size());
        ALL.add(e);
        BY_NAME.put(displayName, e);
        return e;
    }

    /** Packed (categoryIndex, subcategoryIndex) — avoid int[2] allocation. Use
     *  {@link #subCat(long)} / {@link #subSub(long)} to unpack. Adds the
     *  subcategory if new. */
    public static long subPacked(Entry category, String subcategoryName) {
        int idx = category.subcategories.indexOf(subcategoryName);
        if (idx == -1) {
            category.subcategories.add(subcategoryName);
            idx = category.subcategories.size() - 1;
        }
        return ((long) category.index << 32) | (idx & 0xFFFFFFFFL);
    }

    public static int subCat(long packed) { return (int) (packed >>> 32); }
    public static int subSub(long packed) { return (int) packed; }

    /** Legacy wrapper kept for the cold paths that don't run per-frame. */
    public static int[] sub(Entry category, String subcategoryName) {
        long p = subPacked(category, subcategoryName);
        return new int[] { subCat(p), subSub(p) };
    }

    public static Entry fromCategoryName(String displayName) {
        Entry e = BY_NAME.get(displayName);
        return e != null ? e : OTHER;
    }

    public static List<Entry> all() {
        return ALL;
    }

    /** Append the categories array to the JSON output. */
    public static void writeCategoryList(JsonWriter w) {
        w.beginArray();
        for (Entry e : ALL) {
            w.beginObject();
            w.keyString("name", e.displayName);
            w.keyString("color", e.color);
            w.key("subcategories").beginArray();
            for (String s : e.subcategories) w.value(s);
            w.endArray();
            w.endObject();
        }
        w.endArray();
    }
}
