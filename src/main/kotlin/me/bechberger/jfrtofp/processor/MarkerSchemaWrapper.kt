package me.bechberger.jfrtofp.processor

import io.jafar.parser.internal_api.metadata.MetadataClass
import io.jafar.parser.internal_api.metadata.MetadataField
import jdk.jfr.EventType
import jdk.jfr.ValueDescriptor
import me.bechberger.jfrtofp.types.BasicMarkerFormatType
import me.bechberger.jfrtofp.types.MarkerDisplayLocation
import me.bechberger.jfrtofp.types.MarkerGraph
import me.bechberger.jfrtofp.types.MarkerGraphHeight
import me.bechberger.jfrtofp.types.MarkerGraphType
import me.bechberger.jfrtofp.types.MarkerSchema
import me.bechberger.jfrtofp.types.MarkerSchemaField
import me.bechberger.jfrtofp.types.TableMarkerFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class Field(
    val sourceName: String? = null,
    val sourceAccessor: ((ParsedJFREvent) -> Any?)? = null,
    val targetName: String = sourceName!!,
    val type: MarkerType,
    val label: String? = null,
) {
    init {
        assert(sourceName != null || sourceAccessor != null) { "Either sourceName or sourceAccessor must be set" }
    }

    fun getValue(event: ParsedJFREvent): Any? {
        return sourceAccessor?.invoke(event) ?: event.getValue(sourceName!!)
    }
}

data class MarkerSchemaFieldMapping(
    val name: String,
    val fields: List<Field>,
    val categoryName: String? = null,
)

/** Concurrent, non-blocking mapping of event type name to field mapping */
class MarkerSchemaProcessor(val config: Config) {
    private val cache = ConcurrentHashMap<String, MarkerSchemaFieldMapping>()
    private val ignoredTypes = ConcurrentHashMap.newKeySet<String>()
    private val schemas = ConcurrentLinkedQueue<MarkerSchema>()

    private val timelineOverviewEvents = setOf<String>("jdk.ThreadPark")
    private val timelineMemoryEvents = setOf("memory", "gc", "GarbageCollection")

    private fun isIgnoredEvent(name: String) = config.isExecutionSample(name)

    private fun isMemoryEvent(name: String) = timelineMemoryEvents.any { it in name }

    data class SpecialEventType(
        val directDataFields: List<Field>? = null,
        val graphs: List<MarkerGraph>? = null,
        val trackLabel: String? = null,
        val graphHeight: MarkerGraphHeight? = null,
        val isPreSelected: Boolean? = null,
    )

    private val specialEventTypes =
        mapOf<String, SpecialEventType>(
            "jdk.CPULoad" to
                SpecialEventType(
                    trackLabel = "CPU Load",
                    graphHeight = MarkerGraphHeight.LARGE,
                    isPreSelected = true,
                    graphs =
                        listOf(
                            MarkerGraph(key = "jvmSystem", type = MarkerGraphType.LINE, strokeColor = "orange"),
                            MarkerGraph(key = "jvmUser", type = MarkerGraphType.LINE, strokeColor = "blue"),
                        ),
                ),
            "jdk.NetworkUtilization" to
                SpecialEventType(
                    trackLabel = "Network Utilization",
                    graphHeight = MarkerGraphHeight.LARGE,
                    graphs =
                        listOf(
                            MarkerGraph(key = "readRate", type = MarkerGraphType.LINE, strokeColor = "blue"),
                            MarkerGraph(key = "writeRate", type = MarkerGraphType.LINE, strokeColor = "orange"),
                        ),
                ),
            "jdk.GCHeapSummary" to
                SpecialEventType(
                    directDataFields =
                        listOf(
                            Field(sourceName = "gcId", type = MarkerType.INT, label = "GC Identifier"),
                            Field(sourceName = "when", type = MarkerType.STRING, label = "When"),
                            Field(sourceName = "heapUsed", type = MarkerType.BYTES, label = "Heap Used"),
                            Field(
                                sourceAccessor = { it.getLong("heapSpace.committedSize") },
                                targetName = "heapCommitted",
                                type = MarkerType.BYTES,
                                label = "Heap Committed",
                            ),
                            Field(
                                sourceAccessor = { it.getLong("heapSpace.reservedSize") },
                                targetName = "heapReserved",
                                type = MarkerType.BYTES,
                                label = "Heap Reserved",
                            ),
                        ),
                    trackLabel = "GC Heap Summary",
                    graphHeight = MarkerGraphHeight.LARGE,
                    isPreSelected = true,
                    graphs =
                        listOf(
                            MarkerGraph(key = "heapUsed", type = MarkerGraphType.LINE, strokeColor = "blue"),
                            MarkerGraph(key = "heapCommitted", type = MarkerGraphType.LINE, strokeColor = "orange"),
                        ),
                ),
        )

    /** Look up by event type name (used from the Jafar streaming path). */
    operator fun get(typeName: String): MarkerSchemaFieldMapping? =
        if (ignoredTypes.contains(typeName)) null else cache[typeName]

    /** Register metadata from Jafar's [MetadataClass] — called once per new event type. */
    fun registerFromMetadata(type: MetadataClass) {
        val name = type.name
        if (cache.containsKey(name) || ignoredTypes.contains(name)) return
        if (isIgnoredEvent(name)) {
            ignoredTypes.add(name)
            return
        }
        val mfields = type.fields ?: emptyList()
        val hasStack = mfields.any { it.name == "stackTrace" }
        val hasEventThread = mfields.any { it.name == "eventThread" }
        val fieldDescs = mfields.filter {
            it.name != "startTime" && it.name != "duration" && it.name != "stackTrace" &&
                !(config.omitEventThreadProperty && it.name == "eventThread")
        }

        val labelAnn = type.annotations?.firstOrNull { it.type?.name == "jdk.jfr.Label" }?.value
        val descAnn = type.annotations?.firstOrNull { it.type?.name == "jdk.jfr.Description" }?.value
        val catAnn = type.annotations?.firstOrNull { it.type?.name == "jdk.jfr.Category" }?.value

        val display = mutableListOf(MarkerDisplayLocation.MARKER_CHART, MarkerDisplayLocation.MARKER_TABLE)
        if (name in timelineOverviewEvents) display.add(MarkerDisplayLocation.TIMELINE_OVERVIEW)
        else if (isMemoryEvent(name)) display.add(MarkerDisplayLocation.TIMELINE_MEMORY)

        val mapping = mutableListOf<Field>()
        if (hasStack) {
            mapping.add(Field(sourceName = "stackTrace", targetName = "cause", type = MarkerType.STACKTRACE))
        }

        val specialEventType = specialEventTypes[name] ?: SpecialEventType()
        val addedData = listOf(MarkerSchemaField(key = "startTime", label = "Start Time", format = BasicMarkerFormatType.SECONDS))

        val directData = specialEventType.directDataFields?.let { fields ->
            fields.map { field ->
                mapping.add(field)
                MarkerSchemaField(key = field.targetName, label = field.label ?: field.targetName, format = field.type.type)
            }
        } ?: fieldDescs.map { f ->
            val type = MarkerType.fromMetadataField(f)
            val fieldName = when (f.name) {
                "type" -> "type "
                "cause" -> "cause "
                else -> f.name
            }
            mapping.add(Field(sourceName = f.name, targetName = fieldName, type = type))
            val fLabel = f.annotations?.firstOrNull { it.type?.name == "jdk.jfr.Label" }?.value
            MarkerSchemaField(key = fieldName, label = if (fLabel != null && fLabel.length < 20) fLabel else f.name, format = type.type)
        }

        val fields = addedData + directData
        val directNonTableData = directData.filterNot { it.format is TableMarkerFormat }
        val label = directNonTableData.take(3).joinToString(", ") { "${it.label} = {marker.data.${it.key}}" }
        val combinedLabel = when {
            directNonTableData.size == 2 && directNonTableData.first().key == "key" ->
                "{marker.data.key} = {marker.data.${directNonTableData.last().key}}"
            directNonTableData.size <= 1 && descAnn != null -> "$descAnn: $label"
            else -> label
        }

        val fieldMapping = MarkerSchemaFieldMapping(name, mapping, catAnn)
        val schema = MarkerSchema(
            name,
            tooltipLabel = labelAnn ?: name,
            tableLabel = combinedLabel,
            display = display,
            fields = fields,
            description = descAnn,
            graphs = specialEventType.graphs,
            trackLabel = specialEventType.trackLabel,
            graphHeight = specialEventType.graphHeight,
            isPreSelected = specialEventType.isPreSelected,
        )
        cache[name] = fieldMapping
        schemas.add(schema)
    }

    /** Legacy path: register from JDK [EventType] (kept for tests and non-streaming fallback). */
    operator fun get(eventType: EventType): MarkerSchemaFieldMapping? {
        val name = eventType.name
        if (ignoredTypes.contains(name)) return null
        if (!cache.containsKey(name)) {
            if (isIgnoredEvent(name)) {
                ignoredTypes.add(name)
                return null
            }
            val (mapping, schema) = processEventType(eventType)
            cache.putIfAbsent(name, mapping)
            if (schema != null) schemas.add(schema)
        }
        return cache[name]
    }

    private fun isIgnoredField(field: ValueDescriptor) =
        (config.omitEventThreadProperty && field.name == "eventThread") || field.name == "startTime"

    private fun processEventType(eventType: EventType): Pair<MarkerSchemaFieldMapping, MarkerSchema> {
        val name = eventType.name
        val display = mutableListOf(MarkerDisplayLocation.MARKER_CHART, MarkerDisplayLocation.MARKER_TABLE)
        if (name in timelineOverviewEvents) display.add(MarkerDisplayLocation.TIMELINE_OVERVIEW)
        else if (isMemoryEvent(name)) display.add(MarkerDisplayLocation.TIMELINE_MEMORY)

        val mapping = mutableListOf<Field>()
        if (eventType.hasField("stackTrace")) {
            mapping.add(Field(sourceName = "stackTrace", targetName = "cause", type = MarkerType.STACKTRACE))
        }
        val addedData = listOf(MarkerSchemaField(key = "startTime", label = "Start Time", format = BasicMarkerFormatType.SECONDS))
        val specialEventType = specialEventTypes[name] ?: SpecialEventType()

        val directData = specialEventType.directDataFields?.let { fields ->
            fields.map { field ->
                mapping.add(field)
                MarkerSchemaField(key = field.targetName, label = field.label ?: field.targetName, format = field.type.type)
            }
        } ?: eventType.fields.filter { it.name != "stackTrace" && !isIgnoredField(it) }.map { v ->
            val type = MarkerType.fromName(v)
            val fieldName = when (v.name) { "type" -> "type "; "cause" -> "cause "; else -> v.name }
            mapping.add(Field(sourceName = v.name, targetName = fieldName, type = type))
            MarkerSchemaField(key = fieldName, label = if (v.label != null && v.label.length < 20) v.label else v.name, format = type.type)
        }

        val fields = addedData + directData
        val directNonTableData = directData.filterNot { it.format is TableMarkerFormat }
        val label = directNonTableData.take(3).joinToString(", ") { "${it.label} = {marker.data.${it.key}}" }
        val combinedLabel = when {
            directNonTableData.size == 2 && directNonTableData.first().key == "key" ->
                "{marker.data.key} = {marker.data.${directNonTableData.last().key}}"
            directNonTableData.size <= 1 && eventType.description != null -> "${eventType.description}: $label"
            else -> label
        }

        return MarkerSchemaFieldMapping(name, mapping, eventType.categoryNames.firstOrNull()) to
            MarkerSchema(
                name,
                tooltipLabel = eventType.label ?: name,
                tableLabel = combinedLabel,
                display = display,
                fields = fields,
                description = eventType.description,
                graphs = specialEventType.graphs,
                trackLabel = specialEventType.trackLabel,
                graphHeight = specialEventType.graphHeight,
                isPreSelected = specialEventType.isPreSelected,
            )
    }

    fun toMarkerSchemaList() = schemas.distinctBy { it.name }.toList()
}

private fun EventType.hasField(name: String) = getField(name) != null
