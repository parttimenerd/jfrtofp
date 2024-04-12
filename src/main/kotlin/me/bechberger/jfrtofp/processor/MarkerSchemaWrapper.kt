package me.bechberger.jfrtofp.processor

import jdk.jfr.EventType
import jdk.jfr.ValueDescriptor
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedObject
import me.bechberger.jfrtofp.types.BasicMarkerFormatType
import me.bechberger.jfrtofp.types.MarkerDisplayLocation
import me.bechberger.jfrtofp.types.MarkerSchema
import me.bechberger.jfrtofp.types.MarkerSchemaDataStatic
import me.bechberger.jfrtofp.types.MarkerSchemaDataString
import me.bechberger.jfrtofp.types.MarkerTrackConfig
import me.bechberger.jfrtofp.types.MarkerTrackConfigLineHeight
import me.bechberger.jfrtofp.types.MarkerTrackConfigLineType
import me.bechberger.jfrtofp.types.MarkerTrackLineConfig
import me.bechberger.jfrtofp.types.TableMarkerFormat
import me.bechberger.jfrtofp.util.hasField
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class Field(
    val sourceName: String? = null,
    val sourceAccessor: ((RecordedEvent) -> Any)? = null,
    val targetName: String = sourceName!!,
    val type: MarkerType,
    val label: String? = null,
) {
    init {
        assert(
            sourceName != null || sourceAccessor != null,
        ) { "Either sourceName or sourceAccessor must be set" }
    }

    fun getValue(event: RecordedEvent): Any? {
        return sourceAccessor?.invoke(event) ?: event.getValue(sourceName!!)
    }
}

data class MarkerSchemaFieldMapping(val name: String, val fields: List<Field>)

/** Concurrent, non-blocking mapping of EventType to field mapping */
class MarkerSchemaProcessor(val config: Config) {
    private val cache = ConcurrentHashMap<String, MarkerSchemaFieldMapping?>()
    private val schemas = ConcurrentLinkedQueue<MarkerSchema>()

    private val timelineOverviewEvents = setOf<String>("jdk.ThreadPark")
    private val timelineMemoryEvents = setOf("memory", "gc", "GarbageCollection")

    private fun isIgnoredEvent(event: String) =
        config.isExecutionSample(event)

    private fun isIgnoredField(field: ValueDescriptor) =
        (config.omitEventThreadProperty && field.name == "eventThread") ||
            field.name == "startTime"

    private fun isMemoryEvent(event: String) = timelineMemoryEvents.any { it in event }

    data class SpecialEventType(val directDataFields: List<Field>? = null, val trackConfig: MarkerTrackConfig? = null)

    private val specialEventTypes =
        mapOf<String, SpecialEventType>(
            "jdk.CPULoad" to
                SpecialEventType(
                    trackConfig =
                        MarkerTrackConfig(
                            label = "CPU Load",
                            height = MarkerTrackConfigLineHeight.LARGE,
                            lines =
                                listOf(
                                    MarkerTrackLineConfig(
                                        key = "jvmSystem",
                                        strokeColor = "orange",
                                        type = MarkerTrackConfigLineType.LINE,
                                    ),
                                    MarkerTrackLineConfig(
                                        key = "jvmUser",
                                        strokeColor = "blue",
                                        type = MarkerTrackConfigLineType.LINE,
                                    ),
                                ),
                            isPreSelected = true,
                        ),
                ),
            "jdk.NetworkUtilization" to
                SpecialEventType(
                    trackConfig =
                        MarkerTrackConfig(
                            label = "Network Utilization",
                            height = MarkerTrackConfigLineHeight.LARGE,
                            lines =
                                listOf(
                                    MarkerTrackLineConfig(
                                        key = "readRate",
                                        strokeColor = "blue",
                                        type = MarkerTrackConfigLineType.LINE,
                                    ),
                                    MarkerTrackLineConfig(
                                        key = "writeRate",
                                        strokeColor = "orange",
                                        type = MarkerTrackConfigLineType.LINE,
                                    ),
                                ),
                        ),
                ),
            "jdk.GCHeapSummary" to
                SpecialEventType(
                    directDataFields =
                        listOf(
                            Field(
                                sourceName = "gcId",
                                type = MarkerType.INT,
                                label = "GC Identifier",
                            ),
                            Field(
                                sourceName = "when",
                                type = MarkerType.STRING,
                                label = "When",
                            ),
                            Field(
                                sourceName = "heapUsed",
                                type = MarkerType.BYTES,
                                label = "Heap Used",
                            ),
                            Field(
                                sourceAccessor = { it.getValue<RecordedObject>("heapSpace").getLong("committedSize") },
                                targetName = "heapCommitted",
                                type = MarkerType.BYTES,
                                label = "Heap Committed",
                            ),
                            Field(
                                sourceAccessor = { it.getValue<RecordedObject>("heapSpace").getLong("reservedSize") },
                                targetName = "heapReserved",
                                type = MarkerType.BYTES,
                                label = "Heap Reserved",
                            ),
                        ),
                    trackConfig =
                        MarkerTrackConfig(
                            label = "GC Heap Summary",
                            height = MarkerTrackConfigLineHeight.LARGE,
                            isPreSelected = true,
                            lines =
                                listOf(
                                    MarkerTrackLineConfig(
                                        key = "heapUsed",
                                        strokeColor = "blue",
                                        type = MarkerTrackConfigLineType.LINE,
                                    ),
                                    MarkerTrackLineConfig(
                                        key = "heapCommitted",
                                        strokeColor = "orange",
                                        type = MarkerTrackConfigLineType.LINE,
                                    ),
                                ),
                        ),
                ),
        )

    operator fun get(eventType: EventType): MarkerSchemaFieldMapping? {
        if (!cache.containsKey(eventType.name)) {
            val (mapping, schema) =
                if (isIgnoredEvent(eventType.name)) {
                    null to null
                } else {
                    processEventType(eventType)
                }
            if (cache.containsKey(eventType.name)) {
                return cache[eventType.name] // added in the meantime
            }
            cache[eventType.name] = mapping
            if (schema != null) {
                schemas.add(schema)
            }
        }
        return cache[eventType.name]
    }

    private fun processEventType(eventType: EventType): Pair<MarkerSchemaFieldMapping, MarkerSchema> {
        val name = eventType.name
        val display =
            mutableListOf(
                MarkerDisplayLocation.MARKER_CHART,
                MarkerDisplayLocation.MARKER_TABLE,
            )
        if (name in timelineOverviewEvents) {
            display.add(MarkerDisplayLocation.TIMELINE_OVERVIEW)
        } else if (isMemoryEvent(name)) {
            display.add(MarkerDisplayLocation.TIMELINE_MEMORY)
        }
        val mapping = mutableListOf<Field>()
        if (eventType.hasField("stackTrace")) {
            mapping.add(Field(sourceName = "stackTrace", targetName = "cause", type = MarkerType.STACKTRACE))
        }
        val addedData =
            listOfNotNull(
                eventType.description?.let {
                    MarkerSchemaDataStatic(
                        "description",
                        eventType.description,
                    )
                },
                MarkerSchemaDataString(
                    key = "startTime",
                    label = "Start Time",
                    format = BasicMarkerFormatType.SECONDS,
                    searchable = true,
                ),
            )

        val specialEventType = specialEventTypes[name] ?: SpecialEventType()

        val directData =
            specialEventType.directDataFields?.let { fields ->
                fields.map { field ->
                    mapping.add(field)
                    MarkerSchemaDataString(
                        key = field.targetName,
                        label = field.label ?: field.targetName,
                        format = field.type.type,
                        searchable = true,
                    )
                }
            } ?: eventType.fields.filter { it.name != "stackTrace" && !isIgnoredField(it) }.map { v ->
                val type = MarkerType.fromName(v)
                val fieldName =
                    when (v.name) {
                        "type" -> "type "
                        "cause" -> "cause "
                        else -> v.name
                    }
                mapping.add(Field(sourceName = v.name, targetName = fieldName, type = type))
                MarkerSchemaDataString(
                    key = fieldName,
                    label = if (v.label != null && v.label.length < 20) v.label else v.name,
                    format = type.type,
                    searchable = true,
                )
            }
        val data = addedData + directData
        // basic heuristic for finding table label:
        // pick the first three non table fields, prepend with the description
        val directNonTableData = directData.filterNot { it.format is TableMarkerFormat }
        val label = directNonTableData.take(3).joinToString(", ") { "${it.label} = {marker.data.${it.key}}" }
        val combinedLabel =
            if (directNonTableData.size == 2 && directNonTableData.first().key == "key") {
                "{marker.data.key} = {marker.data.${directNonTableData.last().key}}"
            } else if (directNonTableData.size <= 1 && eventType.description != null) {
                "${eventType.description}: $label"
            } else {
                label
            }
        val trackConfig = specialEventType.trackConfig
        return MarkerSchemaFieldMapping(name, mapping) to
            MarkerSchema(
                name,
                tooltipLabel = eventType.label ?: name,
                tableLabel = combinedLabel,
                display = display,
                data = data,
                trackConfig = trackConfig,
            )
    }

    fun toMarkerSchemaList() = schemas.distinctBy { it.name }.toList()
}
