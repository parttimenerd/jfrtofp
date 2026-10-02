package me.bechberger.jfrtofp.converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of marker-schemas.ts. Builds the Firefox Profiler marker schema for each
 * JFR event type seen and converts each event's fields into the marker.data JSON.
 */
public final class MarkerSchemas {

    public static final class JFREventTypeInfo {
        public final String name;
        public final String label;       // nullable
        public final String description; // nullable
        public final String[] categoryNames;
        public final FieldInfo[] fields;
        public final boolean hasStackTrace;

        public JFREventTypeInfo(String name, String label, String description,
                                String[] categoryNames, FieldInfo[] fields, boolean hasStackTrace) {
            this.name = name;
            this.label = label;
            this.description = description;
            this.categoryNames = categoryNames;
            this.fields = fields;
            this.hasStackTrace = hasStackTrace;
        }
    }

    public static final class FieldInfo {
        public final String name;
        public final String typeName;
        public final String contentType; // nullable
        public final String label;       // nullable

        public FieldInfo(String name, String typeName, String contentType, String label) {
            this.name = name;
            this.typeName = typeName;
            this.contentType = contentType;
            this.label = label;
        }
    }

    /** Maps either a source field name (sourceName) or a custom accessor to a target. */
    public static final class FieldMapping {
        public final String sourceName;        // nullable when accessor is set
        public final Accessor accessor;        // nullable when sourceName is set
        public final String targetName;
        public final MarkerTypes.Type type;
        public final String label;             // nullable

        public FieldMapping(String sourceName, Accessor accessor, String targetName,
                            MarkerTypes.Type type, String label) {
            this.sourceName = sourceName;
            this.accessor = accessor;
            this.targetName = targetName;
            this.type = type;
            this.label = label;
        }
    }

    public interface Accessor {
        Object read(Map<String, Object> fields);
    }

    public static final class SchemaMapping {
        public final String name;
        public final List<FieldMapping> fields;

        public SchemaMapping(String name, List<FieldMapping> fields) {
            this.name = name;
            this.fields = fields;
        }
    }

    /** Special per-event-type configuration (graphs, track labels, direct fields). */
    static final class SpecialConfig {
        FieldMapping[] directDataFields;     // nullable
        Graph[] graphs;                      // nullable
        String trackLabel;                   // nullable
        String graphHeight;                  // nullable: small/medium/large
        boolean isPreSelected;
    }

    static final class Graph {
        final String key;
        final String type;          // "line", "bar", "line-filled"
        final String strokeColor;   // nullable

        Graph(String key, String type, String strokeColor) {
            this.key = key;
            this.type = type;
            this.strokeColor = strokeColor;
        }
    }

    private static final Map<String, SpecialConfig> SPECIAL = new HashMap<>();
    static {
        // jdk.CPULoad
        SpecialConfig cpu = new SpecialConfig();
        cpu.trackLabel = "CPU Load";
        cpu.graphHeight = "large";
        cpu.isPreSelected = true;
        cpu.graphs = new Graph[] {
            new Graph("jvmSystem", "line", "orange"),
            new Graph("jvmUser", "line", "blue"),
        };
        SPECIAL.put("jdk.CPULoad", cpu);

        // jdk.NetworkUtilization
        SpecialConfig net = new SpecialConfig();
        net.trackLabel = "Network Utilization";
        net.graphHeight = "large";
        net.graphs = new Graph[] {
            new Graph("readRate", "line", "blue"),
            new Graph("writeRate", "line", "orange"),
        };
        SPECIAL.put("jdk.NetworkUtilization", net);

        // jdk.GCHeapSummary
        SpecialConfig heap = new SpecialConfig();
        heap.trackLabel = "GC Heap Summary";
        heap.graphHeight = "large";
        heap.isPreSelected = true;
        heap.directDataFields = new FieldMapping[] {
            new FieldMapping("gcId", null, "gcId", MarkerTypes.INT, "GC Identifier"),
            new FieldMapping("when", null, "when", MarkerTypes.STRING, "When"),
            new FieldMapping("heapUsed", null, "heapUsed", MarkerTypes.BYTES, "Heap Used"),
            new FieldMapping(null,
                fields -> fields.get("heapSpace.committedSize"),
                "heapCommitted", MarkerTypes.BYTES, "Heap Committed"),
            new FieldMapping(null,
                fields -> fields.get("heapSpace.reservedSize"),
                "heapReserved", MarkerTypes.BYTES, "Heap Reserved"),
        };
        heap.graphs = new Graph[] {
            new Graph("heapUsed", "line", "blue"),
            new Graph("heapCommitted", "line", "orange"),
        };
        SPECIAL.put("jdk.GCHeapSummary", heap);
    }

    private static final Set<String> TIMELINE_OVERVIEW_EVENTS = Set.of("jdk.ThreadPark");
    private static final String[] TIMELINE_MEMORY_KEYWORDS = { "memory", "gc", "GarbageCollection" };

    static boolean isMemoryEvent(String name) {
        for (String k : TIMELINE_MEMORY_KEYWORDS) {
            if (name.contains(k)) return true;
        }
        return false;
    }

    // ── Processor ──────────────────────────────────────────────────────────

    public static final class Processor {
        private final ConverterConfig config;
        private final HashMap<String, SchemaMapping> cache = new HashMap<>();
        private final HashSet<String> ignored = new HashSet<>();
        // Preserve order of first appearance for stable schema output
        private final LinkedHashMap<String, SchemaInfo> schemas = new LinkedHashMap<>();
        // Reused across every buildMarkerData() call to avoid allocating a fresh
        // JsonWriter (and its 16-element comma array + 256-char StringBuilder)
        // per marker event — there can be tens of thousands of these.
        private final JsonWriter scratch = new JsonWriter(256);

        public Processor(ConverterConfig config) {
            this.config = config;
        }

        public SchemaMapping getMapping(JFREventTypeInfo info) {
            if (cache.containsKey(info.name)) return cache.get(info.name);
            if (ignored.contains(info.name)) return null;
            ProcessResult r = process(info);
            cache.put(info.name, r.mapping);
            schemas.put(info.name, r.schema);
            return r.mapping;
        }

        private boolean isIgnoredField(String name) {
            return (config.omitEventThreadProperty && "eventThread".equals(name))
                || "startTime".equals(name);
        }

        private static final class SchemaInfo {
            String name;
            String tooltipLabel;
            String tableLabel;
            String description;
            List<String> display;
            List<SchemaField> fields;
            Graph[] graphs;
            String trackLabel;
            String graphHeight;
            boolean isPreSelected;
        }

        private static final class SchemaField {
            String key;
            String label;
            MarkerTypes.Format format;
        }

        private static final class ProcessResult {
            SchemaMapping mapping;
            SchemaInfo schema;
        }

        private ProcessResult process(JFREventTypeInfo info) {
            List<String> display = new ArrayList<>(Arrays.asList("marker-chart", "marker-table"));
            if (TIMELINE_OVERVIEW_EVENTS.contains(info.name)) {
                display.add("timeline-overview");
            } else if (isMemoryEvent(info.name)) {
                display.add("timeline-memory");
            }

            ArrayList<FieldMapping> mapping = new ArrayList<>();
            ArrayList<SchemaField> schemaFields = new ArrayList<>();

            // Always first: startTime
            SchemaField startTimeField = new SchemaField();
            startTimeField.key = "startTime";
            startTimeField.label = "Start Time";
            startTimeField.format = MarkerTypes.SECONDS_FMT;
            schemaFields.add(startTimeField);

            if (info.hasStackTrace) {
                mapping.add(new FieldMapping("stackTrace", null, "cause",
                    MarkerTypes.STACKTRACE, null));
            }

            SpecialConfig special = SPECIAL.get(info.name);
            ArrayList<SchemaField> directSchemaFields = new ArrayList<>();
            if (special != null && special.directDataFields != null) {
                for (FieldMapping f : special.directDataFields) {
                    mapping.add(f);
                    SchemaField sf = new SchemaField();
                    sf.key = f.targetName;
                    sf.label = f.label != null ? f.label : f.targetName;
                    sf.format = f.type.format;
                    directSchemaFields.add(sf);
                }
            } else {
                for (FieldInfo f : info.fields) {
                    if ("stackTrace".equals(f.name) || isIgnoredField(f.name)) continue;
                    MarkerTypes.Type markerType = MarkerTypes.resolveMarkerType(
                        f.name, f.typeName, f.contentType);
                    String targetName = f.name;
                    // Avoid clashing with reserved property names
                    if ("type".equals(f.name)) targetName = "type ";
                    else if ("cause".equals(f.name)) targetName = "cause ";
                    mapping.add(new FieldMapping(f.name, null, targetName, markerType, f.label));
                    SchemaField sf = new SchemaField();
                    sf.key = targetName;
                    sf.label = (f.label != null && f.label.length() < 20) ? f.label : f.name;
                    sf.format = markerType.format;
                    directSchemaFields.add(sf);
                }
            }
            schemaFields.addAll(directSchemaFields);

            // Build tooltip/table label heuristic. Only fields with simple string
            // formats are eligible (i.e. not the TABLE format).
            ArrayList<SchemaField> nonTable = new ArrayList<>();
            for (SchemaField f : directSchemaFields) {
                if (f.format != MarkerTypes.TABLE_FMT) nonTable.add(f);
            }
            StringBuilder tableLabelSb = new StringBuilder();
            int limit = Math.min(3, nonTable.size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) tableLabelSb.append(", ");
                SchemaField f = nonTable.get(i);
                tableLabelSb.append(f.label).append(" = {marker.data.").append(f.key).append('}');
            }
            String tableLabel = tableLabelSb.toString();
            if (nonTable.size() == 2 && "key".equals(nonTable.get(0).key)) {
                tableLabel = "{marker.data.key} = {marker.data." + nonTable.get(1).key + "}";
            } else if (nonTable.size() <= 1 && info.description != null) {
                tableLabel = info.description + ": " + tableLabel;
            }

            SchemaInfo schema = new SchemaInfo();
            schema.name = info.name;
            schema.tooltipLabel = info.label != null ? info.label : info.name;
            schema.tableLabel = tableLabel;
            schema.description = info.description;
            schema.display = display;
            schema.fields = schemaFields;
            if (special != null) {
                schema.graphs = special.graphs;
                schema.trackLabel = special.trackLabel;
                schema.graphHeight = special.graphHeight;
                schema.isPreSelected = special.isPreSelected;
            }

            ProcessResult r = new ProcessResult();
            r.mapping = new SchemaMapping(info.name, mapping);
            r.schema = schema;
            return r;
        }

        /**
         * Build the marker.data JSON for one event, given its field mapping.
         * Returns the JSON snippet (object literal). The stackRefCallback (nullable)
         * receives every stack index referenced via STACKTRACE so the caller can
         * track which stacks the marker table needs.
         */
        public String buildMarkerData(SchemaMapping mapping, String eventType,
                                      double startMs, Map<String, Object> fields,
                                      me.bechberger.jfrtofp.converter.Processor.ParsedEvent event,
                                      Tables tables,
                                      java.util.function.IntConsumer stackRefCallback) {
            JsonWriter w = scratch.truncate(0);
            w.beginObject();

            for (FieldMapping field : mapping.fields) {
                Object raw = field.accessor != null
                    ? field.accessor.read(fields)
                    : fields.get(field.sourceName);
                if (raw == null) continue;

                if (field.type == MarkerTypes.STACKTRACE) {
                    if (event != null && event.stackDepth > 0) {
                        int stackIdx = tables.processFrames(
                            event.frameClassNames, event.frameMethodNames,
                            event.frameDescriptors, event.frameLineNumbers,
                            event.frameIsJava, event.stackDepth, tables.defaultUrl);
                        if (stackRefCallback != null) stackRefCallback.accept(stackIdx);
                        w.key(field.targetName).beginObject();
                        w.keyInt("stack", stackIdx);
                        w.keyDouble("time", startMs);
                        w.endObject();
                    }
                    continue;
                }

                w.key(field.targetName);
                try {
                    field.type.convert.writeValue(w, tables, raw);
                } catch (Exception e) {
                    // Fallback: stringify
                    w.value(String.valueOf(raw));
                }
            }

            w.keyString("type", eventType);
            w.keyDouble("startTime", startMs - tables.startTimeMs);

            // Special: ObjectAllocationSample synthetic class stack
            if ("jdk.ObjectAllocationSample".equals(eventType)) {
                Object className = fields.get("objectClass");
                if (className != null) {
                    String cn = className.toString();
                    if (!cn.isEmpty()) {
                        int miscStackIdx = tables.stackTable.getMiscStack(cn);
                        w.key("_class").beginObject();
                        w.keyInt("stack", miscStackIdx);
                        w.endObject();
                    }
                }
            }

            w.endObject();
            return w.toJson();
        }

        /** Write profile.meta.markerSchema JSON array. */
        public void writeMarkerSchemaList(JsonWriter w) {
            w.beginArray();
            for (SchemaInfo s : schemas.values()) {
                w.beginObject();
                w.keyString("name", s.name);
                if (s.tooltipLabel != null) w.keyString("tooltipLabel", s.tooltipLabel);
                if (s.tableLabel != null) w.keyString("tableLabel", s.tableLabel);
                if (s.description != null) w.keyString("description", s.description);
                w.key("display").beginArray();
                for (String d : s.display) w.value(d);
                w.endArray();
                w.key("fields").beginArray();
                for (SchemaField f : s.fields) {
                    w.beginObject();
                    w.keyString("key", f.key);
                    if (f.label != null) w.keyString("label", f.label);
                    w.key("format");
                    f.format.writeFormat(w);
                    w.endObject();
                }
                w.endArray();
                if (s.graphs != null) {
                    w.key("graphs").beginArray();
                    for (Graph g : s.graphs) {
                        w.beginObject();
                        w.keyString("key", g.key);
                        w.keyString("type", g.type);
                        if (g.strokeColor != null) w.keyString("strokeColor", g.strokeColor);
                        w.endObject();
                    }
                    w.endArray();
                }
                if (s.trackLabel != null) w.keyString("trackLabel", s.trackLabel);
                if (s.graphHeight != null) w.keyString("graphHeight", s.graphHeight);
                if (s.isPreSelected) w.keyBoolean("isPreSelected", true);
                w.endObject();
            }
            w.endArray();
        }
    }

    // ── generateSampleLikeMarkersConfig ──────────────────────────────────────

    public static final class SampleLikeMarkerConfig {
        public final String name;
        public final String label;
        public final String marker;
        public final String weightType;   // nullable
        public final String weightField;  // nullable
        public final String stackField;   // nullable

        public SampleLikeMarkerConfig(String name, String label, String marker,
                                      String weightType, String weightField,
                                      String stackField) {
            this.name = name;
            this.label = label;
            this.marker = marker;
            this.weightType = weightType;
            this.weightField = weightField;
            this.stackField = stackField;
        }

        public void writeTo(JsonWriter w) {
            w.beginObject();
            w.keyString("name", name);
            w.keyString("label", label);
            w.keyString("marker", marker);
            if (weightType != null) w.keyString("weightType", weightType);
            if (weightField != null) w.keyString("weightField", weightField);
            if (stackField != null) w.keyString("stackField", stackField);
            w.endObject();
        }
    }

    /** Mirror of the TS PRIMARY table. */
    private static final Map<String, String[]> PRIMARY = new HashMap<>();
    static {
        // Each value: {weightType, weightField} (null if absent)
        PRIMARY.put("jdk.AllocationRequiringGC", new String[] {"bytes", "size"});
        PRIMARY.put("jdk.ClassDefine", null);
        PRIMARY.put("jdk.ClassLoad", new String[] {"tracing-ms", "duration"});
        PRIMARY.put("jdk.Deoptimization", null);
        PRIMARY.put("jdk.FileRead", new String[] {"bytes", "bytesRead"});
        PRIMARY.put("jdk.FileWrite", new String[] {"bytes", "bytesWritten"});
        PRIMARY.put("jdk.JavaErrorThrow", null);
        PRIMARY.put("jdk.JavaExceptionThrow", null);
        PRIMARY.put("jdk.JavaMonitorEnter", null);
        PRIMARY.put("jdk.JavaMonitorWait", new String[] {"tracing-ms", "timeout"});
        PRIMARY.put("jdk.ObjectAllocationSample", new String[] {"bytes", "weight"});
        PRIMARY.put("jdk.ObjectAllocationInNewTLAB", new String[] {"bytes", "allocationSize"});
        PRIMARY.put("jdk.ObjectAllocationOutsideTLAB", new String[] {"bytes", "allocationSize"});
        PRIMARY.put("jdk.ProcessStart", null);
        PRIMARY.put("jdk.SocketRead", new String[] {"bytes", "bytesRead"});
        PRIMARY.put("jdk.SocketWrite", new String[] {"bytes", "bytesWritten"});
        PRIMARY.put("jdk.SystemGC", null);
        PRIMARY.put("jdk.ThreadPark", new String[] {"tracing-ms", "duration"});
        PRIMARY.put("jdk.ThreadSleep", new String[] {"tracing-ms", "duration"});
        PRIMARY.put("jdk.ThreadStart", null);
    }

    public static List<SampleLikeMarkerConfig> generateSampleLikeMarkersConfig(
            String eventTypeName, String eventLabel) {
        ArrayList<SampleLikeMarkerConfig> out = new ArrayList<>();
        String label = eventLabel != null ? eventLabel : eventTypeName;
        if (PRIMARY.containsKey(eventTypeName)) {
            String[] cfg = PRIMARY.get(eventTypeName);
            String weightType = cfg != null ? cfg[0] : null;
            String weightField = cfg != null ? cfg[1] : null;
            out.add(new SampleLikeMarkerConfig(
                eventTypeName, label, eventTypeName,
                weightType, weightField, null));
        }
        if ("jdk.ObjectAllocationSample".equals(eventTypeName)) {
            out.add(new SampleLikeMarkerConfig(
                eventTypeName + "_class",
                label + " Classes",
                eventTypeName,
                "bytes", "weight", "_class"));
        }
        return out;
    }
}
