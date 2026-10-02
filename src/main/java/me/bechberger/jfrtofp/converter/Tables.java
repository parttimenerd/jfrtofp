package me.bechberger.jfrtofp.converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of tables.ts. Holds the dedup tables that build up the Profile.shared
 * structure: strings, sources, resources, funcs, frames, and stacks.
 *
 * <p>This is the converter's hot path — keep all maps/arrays as plain Java
 * collections and avoid intermediate allocations on lookup.
 */
public final class Tables {

    public final ConverterConfig config;
    public final double startTimeMs;
    public final String defaultUrl;          // nullable

    public final StringTableWrapper stringTable = new StringTableWrapper();
    public final SourceTableWrapper sourceTable;
    public final ResourceTableWrapper resourceTable;
    public final FuncTableWrapper funcTable;
    public final FrameTableWrapper frameTable;
    public final StackTableWrapper stackTable;

    public Tables(ConverterConfig config, double startTimeMs, String defaultUrl) {
        this.config = config;
        this.startTimeMs = startTimeMs;
        this.defaultUrl = defaultUrl;
        this.sourceTable = new SourceTableWrapper(stringTable);
        this.resourceTable = new ResourceTableWrapper(stringTable);
        this.funcTable = new FuncTableWrapper(stringTable, sourceTable, resourceTable);
        this.frameTable = new FrameTableWrapper(funcTable, config);
        this.stackTable = new StackTableWrapper(frameTable);
    }

    /** Indirect quicksort: sort {@code idx[lo..hi]} so {@code keys[idx[i]]}
     *  is non-decreasing. Avoids the Integer[]+Comparator boxing cost. */
    static void sortIndicesByDoubleAsc(int[] idx, double[] keys, int lo, int hi) {
        while (lo < hi) {
            if (hi - lo < 16) {
                for (int i = lo + 1; i <= hi; i++) {
                    int v = idx[i];
                    double kv = keys[v];
                    int j = i - 1;
                    while (j >= lo && keys[idx[j]] > kv) {
                        idx[j + 1] = idx[j];
                        j--;
                    }
                    idx[j + 1] = v;
                }
                return;
            }
            int mid = (lo + hi) >>> 1;
            // median-of-three pivot
            if (keys[idx[lo]] > keys[idx[mid]]) { int t = idx[lo]; idx[lo] = idx[mid]; idx[mid] = t; }
            if (keys[idx[lo]] > keys[idx[hi]])  { int t = idx[lo]; idx[lo] = idx[hi];  idx[hi]  = t; }
            if (keys[idx[mid]] > keys[idx[hi]]) { int t = idx[mid]; idx[mid] = idx[hi]; idx[hi] = t; }
            double pivot = keys[idx[mid]];
            int t = idx[mid]; idx[mid] = idx[hi - 1]; idx[hi - 1] = t;
            int i = lo, j = hi - 1;
            while (true) {
                while (keys[idx[++i]] < pivot) {}
                while (keys[idx[--j]] > pivot) {}
                if (i >= j) break;
                int tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
            }
            int tmp = idx[i]; idx[i] = idx[hi - 1]; idx[hi - 1] = tmp;
            // recurse on the smaller side; loop on the larger to bound stack depth
            if (i - 1 - lo < hi - (i + 1)) {
                sortIndicesByDoubleAsc(idx, keys, lo, i - 1);
                lo = i + 1;
            } else {
                sortIndicesByDoubleAsc(idx, keys, i + 1, hi);
                hi = i - 1;
            }
        }
    }

    // ── Primitive-array list helpers (avoid Integer/Boolean boxing) ──────────

    /** Growable int[] backed list. Replaces ArrayList&lt;Integer&gt; on hot paths. */
    public static final class IntList {
        int[] data = new int[64];
        int n = 0;
        public void add(int v) {
            if (n == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[n++] = v;
        }
        public int get(int i) { return data[i]; }
        public int size() { return n; }
        /** Raw backing array — only the first {@link #size()} entries are valid. */
        public int[] raw() { return data; }
    }

    /** Growable boolean[] backed list. */
    public static final class BoolList {
        boolean[] data = new boolean[64];
        int n = 0;
        public void add(boolean v) {
            if (n == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[n++] = v;
        }
        public boolean get(int i) { return data[i]; }
        public int size() { return n; }
        public boolean[] raw() { return data; }
    }

    /** Process raw frame list (top-of-stack first) into a stack index.
     *  Caller passes top-first frames as five arrays of length {@code stackDepth}
     *  (caller may over-size; we only read 0..stackDepth). */
    public int processFrames(String[] classNames, String[] methodNames,
                             String[] descriptors, int[] lineNumbers,
                             boolean[] isJava, int stackDepth, String sourceUrl) {
        if (stackDepth == 0) return -1;
        int prefixIdx = -1;
        int last = -1;
        for (int i = stackDepth - 1; i >= 0; i--) {
            int frameIdx = frameTable.getFrame(
                classNames[i], methodNames[i], descriptors[i],
                lineNumbers[i], isJava[i], sourceUrl);
            last = stackTable.appendFrame(prefixIdx, frameIdx);
            prefixIdx = last;
        }
        return last;
    }

    public void writeSharedData(JsonWriter w) {
        w.beginObject();
        w.key("stringArray").beginArray();
        for (String s : stringTable.toArray()) w.value(s);
        w.endArray();
        w.key("stackTable"); stackTable.writeTo(w);
        w.key("frameTable"); frameTable.writeTo(w);
        w.key("funcTable"); funcTable.writeTo(w);
        w.key("resourceTable"); resourceTable.writeTo(w);
        w.key("nativeSymbols").beginObject();
        w.key("libIndex").beginArray().endArray();
        w.key("address").beginArray().endArray();
        w.key("name").beginArray().endArray();
        w.key("functionSize").beginArray().endArray();
        w.keyInt("length", 0);
        w.endObject();
        w.key("sources"); sourceTable.writeTo(w);
        w.endObject();
    }

    // ── String table ─────────────────────────────────────────────────────────

    public static final class StringTableWrapper {
        private final ArrayList<String> strings = new ArrayList<>();
        private final HashMap<String, Integer> map = new HashMap<>();

        public int get(String s) {
            Integer idx = map.get(s);
            if (idx != null) return idx;
            int i = strings.size();
            strings.add(s);
            map.put(s, i);
            return i;
        }

        public List<String> toArray() {
            return strings;
        }

        public int size() { return strings.size(); }
    }

    // ── Source table ─────────────────────────────────────────────────────────

    public static final class SourceTableWrapper {
        private final StringTableWrapper stringTable;
        private final IntList filenames = new IntList();
        private final IntList sourceUrls = new IntList(); // -1 = null
        private final HashMap<Long, Integer> map = new HashMap<>();

        SourceTableWrapper(StringTableWrapper st) { this.stringTable = st; }

        /** Returns the new index, or -1 if filename is null. */
        public int getOrCreate(String filename, String sourceUrl) {
            if (filename == null) return -1;
            int filenameIdx = stringTable.get(filename);
            int urlIdx = sourceUrl != null ? stringTable.get(sourceUrl) : -1;
            long key = ((long) filenameIdx << 32) | (urlIdx & 0xFFFFFFFFL);
            Integer idx = map.get(key);
            if (idx != null) return idx;
            int i = filenames.size();
            filenames.add(filenameIdx);
            sourceUrls.add(urlIdx);
            map.put(key, i);
            return i;
        }

        public int size() { return filenames.size(); }

        void writeTo(JsonWriter w) {
            int len = filenames.size();
            boolean hasUrls = false;
            int[] urlsRaw = sourceUrls.raw();
            for (int i = 0; i < len; i++) {
                if (urlsRaw[i] != -1) { hasUrls = true; break; }
            }
            w.beginObject();
            w.keyInt("length", len);
            w.key("id").repeatedNull(len);
            w.key("filename").intArray(filenames.raw(), len);
            w.key("startLine").repeatedInt(-1, len);
            w.key("startColumn").repeatedInt(-1, len);
            w.key("sourceMapURL").repeatedNull(len);
            if (hasUrls) {
                w.key("sourceUrl").intArrayNullable(urlsRaw, len, -1);
            }
            w.endObject();
        }
    }

    // ── Resource table ───────────────────────────────────────────────────────

    /**
     * Composite (typeName, isJava) key. Reused as a scratch lookup probe to
     * avoid allocating a fresh "typeName:0" / "typeName:1" String on every
     * resource lookup — by far the hottest path during stack-frame conversion.
     */
    private static final class ResourceKey {
        String typeName;
        boolean isJava;
        int hash;

        ResourceKey set(String t, boolean j) {
            this.typeName = t;
            this.isJava = j;
            // Mix in isJava with an off-bit so "Foo" + isJava=true and "Foo" +
            // isJava=false don't collide trivially.
            this.hash = t.hashCode() ^ (j ? 0x55555555 : 0);
            return this;
        }

        ResourceKey copy() {
            ResourceKey k = new ResourceKey();
            k.typeName = this.typeName;
            k.isJava = this.isJava;
            k.hash = this.hash;
            return k;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof ResourceKey k)) return false;
            return isJava == k.isJava && typeName.equals(k.typeName);
        }
    }

    public static final class ResourceTableWrapper {
        private final StringTableWrapper stringTable;
        private final IntList names = new IntList();
        private final IntList hosts = new IntList(); // -1 = null
        private final IntList types = new IntList(); // 0=unknown, 5=url
        private final HashMap<ResourceKey, Integer> map = new HashMap<>();
        private final ResourceKey scratch = new ResourceKey();

        ResourceTableWrapper(StringTableWrapper st) { this.stringTable = st; }

        public int getResource(String typeName, boolean isJava) {
            scratch.set(typeName, isJava);
            Integer idx = map.get(scratch);
            if (idx != null) return idx;
            int i = names.size();
            int dollar = typeName.indexOf('$');
            String base = dollar == -1 ? typeName : typeName.substring(0, dollar);
            names.add(stringTable.get(base));
            if (isJava) {
                hosts.add(stringTable.get(typeName));
                types.add(5);
            } else {
                hosts.add(-1);
                types.add(0);
            }
            map.put(scratch.copy(), i);
            return i;
        }

        public int size() { return names.size(); }

        void writeTo(JsonWriter w) {
            int len = names.size();
            w.beginObject();
            w.key("name").intArray(names.raw(), len);
            w.keyInt("length", len);
            w.key("lib").repeatedNull(len);
            w.key("host").intArrayNullable(hosts.raw(), len, -1);
            w.key("type").intArray(types.raw(), len);
            w.endObject();
        }
    }

    // ── Func table ───────────────────────────────────────────────────────────

    /**
     * Composite (className, methodName, descriptor) key. Reused as a scratch
     * lookup probe across every getFunction() call — by far the hottest path
     * during stack-frame conversion. Replaces the previous approach of
     * concatenating a "className.methodName(desc)" String per lookup, which
     * allocated millions of throwaway Strings on a typical 30s recording.
     */
    private static final class FuncKey {
        String className;
        String methodName;
        String descriptor;
        int hash;

        FuncKey set(String c, String m, String d) {
            this.className = c;
            this.methodName = m;
            this.descriptor = d;
            int h = c.hashCode();
            h = 31 * h + m.hashCode();
            h = 31 * h + (d == null ? 0 : d.hashCode());
            this.hash = h;
            return this;
        }

        FuncKey copy() {
            FuncKey k = new FuncKey();
            k.className = this.className;
            k.methodName = this.methodName;
            k.descriptor = this.descriptor;
            k.hash = this.hash;
            return k;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof FuncKey k)) return false;
            // Identity check first — JFR's parser interns symbol Strings via
            // CachedStringParser so the same constant-pool entry typically
            // returns the same reference. Fall back to .equals() for safety.
            return (className == k.className || className.equals(k.className))
                && (methodName == k.methodName || methodName.equals(k.methodName))
                && (descriptor == k.descriptor
                    || (descriptor != null && descriptor.equals(k.descriptor))
                    || (descriptor == null && k.descriptor == null));
        }
    }

    public static final class FuncTableWrapper {
        private final StringTableWrapper stringTable;
        private final SourceTableWrapper sourceTable;
        private final ResourceTableWrapper resourceTable;

        private final IntList names = new IntList();
        private final BoolList isJss = new BoolList();
        private final BoolList relevantForJss = new BoolList();
        private final IntList resources = new IntList();
        private final IntList sources = new IntList(); // -1 = null
        private final IntList lineNumbers = new IntList();
        private final HashMap<FuncKey, Integer> map = new HashMap<>();
        private final HashMap<String, Integer> miscFunctions = new HashMap<>();
        private final FuncKey scratch = new FuncKey();

        FuncTableWrapper(StringTableWrapper st, SourceTableWrapper src, ResourceTableWrapper res) {
            this.stringTable = st;
            this.sourceTable = src;
            this.resourceTable = res;
        }

        public int getFunction(String className, String methodName, String descriptor,
                               boolean isJava, int lineNumber, String sourceUrl) {
            scratch.set(className, methodName, descriptor);
            Integer idx = map.get(scratch);
            if (idx != null) return idx;
            int i = names.size();
            String displayName = shortClassName(className) + "." + methodName + formatDescriptor(descriptor);
            names.add(stringTable.get(displayName));
            isJss.add(isJava);
            relevantForJss.add(true);
            resources.add(resourceTable.getResource(className, isJava));
            sources.add(sourceTable.getOrCreate(className, sourceUrl));
            lineNumbers.add(lineNumber);
            map.put(scratch.copy(), i);
            return i;
        }

        public int getMiscFunction(String name, boolean isNative, String defaultUrl) {
            Integer idx = miscFunctions.get(name);
            if (idx != null) return idx;
            int i = names.size();
            names.add(stringTable.get(name));
            isJss.add(isNative);
            relevantForJss.add(true);
            resources.add(-1);
            sources.add(sourceTable.getOrCreate(null, defaultUrl));
            lineNumbers.add(-1);
            miscFunctions.put(name, i);
            return i;
        }

        public int size() { return names.size(); }

        void writeTo(JsonWriter w) {
            int len = names.size();
            w.beginObject();
            w.key("name").intArray(names.raw(), len);
            w.key("isJS").boolArray(isJss.raw(), len);
            w.key("relevantForJS").boolArray(relevantForJss.raw(), len);
            w.key("resource").intArray(resources.raw(), len);
            w.key("source").intArrayNullable(sources.raw(), len, -1);
            w.keyInt("length", len);
            w.key("lineNumber").intArray(lineNumbers.raw(), len);
            w.key("columnNumber").repeatedNull(len);
            w.endObject();
        }
    }

    // ── Frame table ──────────────────────────────────────────────────────────

    public static final class FrameTableWrapper {
        private final FuncTableWrapper funcTable;
        private final ConverterConfig config;

        private final IntList categories = new IntList();
        private final IntList subcategories = new IntList();
        private final IntList funcs = new IntList();
        private final IntList lines = new IntList(); // -1 = null
        private final HashMap<Long, Integer> map = new HashMap<>();
        private final HashMap<String, Integer> miscFrames = new HashMap<>();

        FrameTableWrapper(FuncTableWrapper f, ConverterConfig c) {
            this.funcTable = f;
            this.config = c;
        }

        // 1-element cache: most stack-frame lookups are repeated within one
        // event (deep recursion) and across adjacent events from the same
        // thread. Hitting this skips the HashMap probe entirely.
        private long lastKey = Long.MIN_VALUE;
        private int  lastIdx;

        public int getFrame(String className, String methodName, String descriptor,
                            int lineNumber, boolean isJavaFrame, String sourceUrl) {
            int funcIdx = funcTable.getFunction(
                className, methodName, descriptor, isJavaFrame, -1, sourceUrl);
            int line = lineNumber == -1 ? -1 : lineNumber;
            long key = ((long) funcIdx << 32) | (line & 0xFFFFFFFFL);
            if (key == lastKey) return lastIdx;
            Integer idx = map.get(key);
            if (idx != null) {
                lastKey = key; lastIdx = idx;
                return idx;
            }
            int i = funcs.size();
            long cs;
            if (config.useNonProjectCategory && isJavaFrame
                && config.isNonProjectPackage(getPackage(className))) {
                cs = Categories.subPacked(Categories.NON_PROJECT_JAVA, "Other");
            } else if (isJavaFrame) {
                cs = Categories.subPacked(Categories.JAVA, "Other");
            } else {
                cs = Categories.subPacked(Categories.CPP, "Other");
            }
            categories.add(Categories.subCat(cs));
            subcategories.add(Categories.subSub(cs));
            funcs.add(funcIdx);
            lines.add(line);
            map.put(key, i);
            lastKey = key; lastIdx = i;
            return i;
        }

        public int getMiscFrame(String name, String categoryName,
                                String subcategoryName, boolean isNative,
                                String defaultUrl) {
            Integer idx = miscFrames.get(name);
            if (idx != null) return idx;
            Categories.Entry catEntry = Categories.fromCategoryName(categoryName);
            int[] cs = Categories.sub(catEntry, subcategoryName);
            int i = funcs.size();
            categories.add(cs[0]);
            subcategories.add(cs[1]);
            funcs.add(funcTable.getMiscFunction(name, isNative, defaultUrl));
            lines.add(-1);
            miscFrames.put(name, i);
            return i;
        }

        public int size() { return funcs.size(); }

        void writeTo(JsonWriter w) {
            int len = funcs.size();
            w.beginObject();
            w.key("category").intArray(categories.raw(), len);
            w.key("subcategory").intArray(subcategories.raw(), len);
            w.key("func").intArray(funcs.raw(), len);
            w.key("line").intArrayNullable(lines.raw(), len, -1);
            w.keyInt("length", len);
            w.key("address").repeatedInt(-1, len);
            w.key("inlineDepth").repeatedInt(0, len);
            w.key("nativeSymbol").repeatedNull(len);
            w.key("innerWindowID").repeatedNull(len);
            w.key("column").repeatedNull(len);
            w.endObject();
        }
    }

    // ── Stack table ──────────────────────────────────────────────────────────

    public static final class StackTableWrapper {
        private final FrameTableWrapper frameTable;
        private final IntList frames = new IntList();
        private final IntList prefixes = new IntList(); // -1 = null
        private final HashMap<Long, Integer> map = new HashMap<>();
        private final HashMap<String, Integer> miscStacks = new HashMap<>();

        StackTableWrapper(FrameTableWrapper f) { this.frameTable = f; }

        public int getStack(int[] frameIndices) {
            if (frameIndices.length == 0) return -1;
            int prefixIdx = -1;
            int last = -1;
            for (int frameIdx : frameIndices) {
                last = appendFrame(prefixIdx, frameIdx);
                prefixIdx = last;
            }
            return last;
        }

        // 1-element cache: same (prefix, frame) hits constantly when adjacent
        // events share a deep stack prefix. Skips the HashMap lookup.
        private long lastKey = Long.MIN_VALUE;
        private int  lastIdx;

        /** Append one frame to a prefix chain; returns the resulting stack index. */
        public int appendFrame(int prefixIdx, int frameIdx) {
            long key = ((long) (prefixIdx & 0xFFFFFFFFL) << 32) | (frameIdx & 0xFFFFFFFFL);
            if (key == lastKey) return lastIdx;
            Integer stackIdx = map.get(key);
            if (stackIdx != null) {
                lastKey = key; lastIdx = stackIdx;
                return stackIdx;
            }
            int s = frames.size();
            frames.add(frameIdx);
            prefixes.add(prefixIdx);
            map.put(key, s);
            lastKey = key; lastIdx = s;
            return s;
        }

        public int getMiscStack(String name) {
            Integer idx = miscStacks.get(name);
            if (idx != null) return idx;
            int frameIdx = frameTable.getMiscFrame(name, "Misc", "Other", false, null);
            int i = frames.size();
            frames.add(frameIdx);
            prefixes.add(-1);
            miscStacks.put(name, i);
            return i;
        }

        public int size() { return frames.size(); }

        void writeTo(JsonWriter w) {
            int len = frames.size();
            w.beginObject();
            w.key("frame").intArray(frames.raw(), len);
            w.key("prefix").intArrayNullable(prefixes.raw(), len, -1);
            w.keyInt("length", len);
            w.endObject();
        }
    }

    // ── Samples table ────────────────────────────────────────────────────────

    public static final class SamplesTableWrapper {
        private int[] stacks = new int[256];
        private double[] times = new double[256];
        private int n = 0;

        public void processEvent(int stackIndex, double startMs) {
            if (n == stacks.length) {
                stacks = Arrays.copyOf(stacks, stacks.length * 2);
                times = Arrays.copyOf(times, times.length * 2);
            }
            stacks[n] = stackIndex;
            times[n] = startMs;
            n++;
        }

        public int length() { return n; }
        public double timeAt(int i) { return times[i]; }

        /** Sort by time ascending and write the samples table object. */
        public void writeTo(JsonWriter w, java.util.function.DoubleUnaryOperator cpuLoadAtTime) {
            // Indirect sort over indices 0..n-1 by times[]. Avoid Integer[] boxing
            // (n can be hundreds of thousands).
            int[] order = new int[n];
            for (int i = 0; i < n; i++) order[i] = i;
            sortIndicesByDoubleAsc(order, times, 0, n - 1);

            int[] sortedStacks = new int[n];
            double[] sortedTimes = new double[n];
            for (int i = 0; i < n; i++) {
                sortedStacks[i] = stacks[order[i]];
                sortedTimes[i] = times[order[i]];
            }

            double[] threadCPUDelta = new double[n];
            if (n > 0) {
                threadCPUDelta[0] = 0;
                for (int i = 1; i < n; i++) {
                    if (i == n - 1) {
                        threadCPUDelta[i] = 0;
                    } else {
                        double load = cpuLoadAtTime.applyAsDouble(sortedTimes[i]);
                        threadCPUDelta[i] = (sortedTimes[i] - sortedTimes[i - 1]) * 1000.0 * load;
                    }
                }
            }

            w.beginObject();
            w.key("stack").intArray(sortedStacks, n);
            w.key("eventDelay").repeatedInt(0, n);
            w.key("time").doubleArray(sortedTimes, n);
            w.keyNull("weight");
            w.keyString("weightType", "samples");
            w.key("threadCPUDelta").doubleArray(threadCPUDelta, n);
            w.keyInt("length", n);
            w.endObject();
        }
    }

    // ── Marker table ─────────────────────────────────────────────────────────

    /**
     * Marker entry: pre-encoded data JSON to avoid building intermediate maps.
     * Each MarkerItem owns the JSON snippet for its `data` value.
     */
    public static final class MarkerItem {
        public final int name;
        public final double startTime;       // NaN = null
        public final double endTime;         // NaN = null
        public final int phase;              // 0=instant, 1=interval
        public final int category;
        public final String dataJson;        // raw JSON for data field, or null

        public MarkerItem(int name, double startTime, double endTime,
                          int phase, int category, String dataJson) {
            this.name = name;
            this.startTime = startTime;
            this.endTime = endTime;
            this.phase = phase;
            this.category = category;
            this.dataJson = dataJson;
        }
    }

    public static final class RawMarkerTableWrapper {
        private final ArrayList<MarkerItem> items = new ArrayList<>();

        public void add(MarkerItem item) { items.add(item); }

        public int size() { return items.size(); }

        public void writeTo(JsonWriter w) {
            MarkerItem[] sorted = items.toArray(new MarkerItem[0]);
            Arrays.sort(sorted, (a, b) -> {
                double aT = Double.isNaN(a.startTime) ? 0 : a.startTime;
                double bT = Double.isNaN(b.startTime) ? 0 : b.startTime;
                return Double.compare(aT, bT);
            });
            int len = sorted.length;
            w.beginObject();
            w.key("data").beginArray();
            for (MarkerItem m : sorted) {
                if (m.dataJson == null) w.nullValue();
                else w.raw(m.dataJson);
            }
            w.endArray();
            w.key("name").beginArray();
            for (MarkerItem m : sorted) w.value(m.name);
            w.endArray();
            w.key("startTime").beginArray();
            for (MarkerItem m : sorted) {
                if (Double.isNaN(m.startTime)) w.nullValue();
                else w.value(m.startTime);
            }
            w.endArray();
            w.key("endTime").beginArray();
            for (MarkerItem m : sorted) {
                if (Double.isNaN(m.endTime)) w.nullValue();
                else w.value(m.endTime);
            }
            w.endArray();
            w.key("phase").beginArray();
            for (MarkerItem m : sorted) w.value(m.phase);
            w.endArray();
            w.key("category").beginArray();
            for (MarkerItem m : sorted) w.value(m.category);
            w.endArray();
            w.keyInt("length", len);
            w.endObject();
        }
    }

    // ── Bytecode helpers ─────────────────────────────────────────────────────

    public static String shortClassName(String className) {
        int dollar = className.indexOf('$');
        String base = dollar == -1 ? className : className.substring(0, dollar);
        int dot = base.lastIndexOf('.');
        return dot == -1 ? base : base.substring(dot + 1);
    }

    public static String getPackage(String className) {
        int dollar = className.indexOf('$');
        String base = dollar == -1 ? className : className.substring(0, dollar);
        int dot = base.lastIndexOf('.');
        return dot == -1 ? "" : base.substring(0, dot);
    }

    /** Turns "(ILjava/lang/String;)[B" into "(int, String): byte[]".
     *  Single-pass walk, no recursion, no intermediate List. */
    public static String formatDescriptor(String descriptor) {
        int len = descriptor.length();
        if (len < 2 || descriptor.charAt(0) != '(') return descriptor;
        int close = descriptor.indexOf(')');
        if (close == -1) return descriptor;
        StringBuilder sb = new StringBuilder(len + 8);
        sb.append('(');
        int i = 1;
        boolean first = true;
        while (i < close) {
            if (!first) sb.append(", ");
            first = false;
            i = appendType(sb, descriptor, i);
        }
        sb.append(')');
        // Return type
        int retStart = close + 1;
        if (retStart < len) {
            int retLen = sb.length();
            int after = appendType(sb, descriptor, retStart);
            // If the return type rendered as "void", drop it.
            if (after == retStart + 1 && descriptor.charAt(retStart) == 'V') {
                sb.setLength(retLen);
            } else {
                sb.insert(retLen, ": ");
            }
        }
        return sb.toString();
    }

    /** Append the next type in {@code desc} starting at {@code i} to {@code sb}.
     *  Returns the index just past the consumed type. */
    private static int appendType(StringBuilder sb, String desc, int i) {
        int dims = 0;
        while (i < desc.length() && desc.charAt(i) == '[') { i++; dims++; }
        if (i >= desc.length()) return i;
        char c = desc.charAt(i);
        if (c == 'L') {
            int end = desc.indexOf(';', i);
            if (end == -1) end = desc.length() - 1;
            // Append the short class name only.
            // class string = desc.substring(i+1, end), with '/' → '.'
            int classStart = i + 1;
            int classEnd = end;
            // shortClassName equivalent: split on '$' (first), then last '/'-separated segment
            int dollar = desc.indexOf('$', classStart);
            int base = (dollar != -1 && dollar < classEnd) ? dollar : classEnd;
            int slash = -1;
            for (int k = base - 1; k >= classStart; k--) {
                if (desc.charAt(k) == '/') { slash = k; break; }
            }
            int nameStart = slash == -1 ? classStart : slash + 1;
            for (int k = nameStart; k < base; k++) sb.append(desc.charAt(k));
            i = end + 1;
        } else {
            String prim = primitive(c);
            if (prim != null) sb.append(prim);
            else sb.append(c);
            i++;
        }
        for (int k = 0; k < dims; k++) sb.append("[]");
        return i;
    }

    private static String primitive(char c) {
        switch (c) {
            case 'V': return "void";
            case 'Z': return "boolean";
            case 'C': return "char";
            case 'B': return "byte";
            case 'S': return "short";
            case 'I': return "int";
            case 'F': return "float";
            case 'J': return "long";
            case 'D': return "double";
            default:  return null;
        }
    }
}
