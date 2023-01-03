import jdk.jfr.EventType
import jdk.jfr.ValueDescriptor
import me.bechberger.jfrtofp.processor.Config
import me.bechberger.jfrtofp.processor.MarkerType
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class MarkerSchemaFieldMapping(val name: String, private val fields: Map<String, String>) {
    operator fun get(key: String) = fields[key] ?: key

    operator fun contains(key: String) = key in fields
}

/** Concurrent, non-blocking mapping of EventType to field mapping */
class MarkerSchemaProcessor(val config: Config) {
    private val cache = ConcurrentHashMap<String, MarkerSchemaFieldMapping?>()
    private val schemas = ConcurrentLinkedQueue<MarkerSchema>()

    private val timelineOverviewEvents = setOf<String>()
    private val timelineMemoryEvents = setOf("memory", "gc", "GarbageCollection")

    private fun isIgnoredEvent(event: String) = event.equals("jdk.ExecutionSample") || event.equals(
        "jdk.NativeMethodSample"
    )

    private fun isIgnoredField(field: ValueDescriptor) =
        (config.omitEventThreadProperty && field.name == "eventThread") ||
            field.name == "startTime"

    private fun isMemoryEvent(event: String) = timelineMemoryEvents.any { it in event }

    operator fun get(eventType: EventType): MarkerSchemaFieldMapping? {
        if (!cache.containsKey(eventType.name)) {
            val (mapping, schema) = if (isIgnoredEvent(eventType.name)) {
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
        val display = mutableListOf(
            MarkerDisplayLocation.MARKER_CHART,
            MarkerDisplayLocation.MARKER_TABLE
        )
        if (name in timelineOverviewEvents) {
            display.add(MarkerDisplayLocation.TIMELINE_OVERVIEW)
        } else if (isMemoryEvent(name)) {
            display.add(MarkerDisplayLocation.TIMELINE_MEMORY)
        }
        val mapping = mutableMapOf("stackTrace" to "cause")
        val addedData = listOfNotNull(
            eventType.description?.let {
                MarkerSchemaDataStatic(
                    "description",
                    eventType.description
                )
            },
            MarkerSchemaDataString(
                key = "startTime",
                label = "Start Time",
                format = BasicMarkerFormatType.SECONDS,
                searchable = true
            )
        )
        val directData = eventType.fields.filter { it.name != "stackTrace" && !isIgnoredField(it) }.map { v ->
            val type = MarkerType.fromName(v)
            val fieldName = when (v.name) {
                "type" -> "type "
                "cause" -> "cause "
                else -> v.name
            }
            mapping[v.name] = fieldName
            MarkerSchemaDataString(
                key = fieldName,
                label = if (v.label != null && v.label.length < 20) v.label else v.name,
                format = type.type,
                searchable = true
            )
        }
        val data = addedData + directData
        // basic heuristic for finding table label:
        // pick the first three non table fields, prepend with the description
        val directNonTableData = directData.filterNot { it.format is TableMarkerFormat }
        val label = directNonTableData.take(3).joinToString(", ") { "${it.label} = {marker.data.${it.key}}" }
        val combinedLabel = if (directNonTableData.size == 2 && directNonTableData.first().key == "key") {
            "{marker.data.key} = {marker.data.${directNonTableData.last().key}}"
        } else if (directNonTableData.size <= 1 && eventType.description != null) {
            "${eventType.description}: $label"
        } else {
            label
        }
        val trackConfig = when (name) {
            "jdk.CPULoad" -> MarkerTrackConfig(
                label = "CPU Load",
                height = MarkerTrackConfigLineHeight.LARGE,
                lines = listOf(
                    MarkerTrackLineConfig(
                        key = "jvmSystem",
                        strokeColor = "orange",
                        type = MarkerTrackConfigLineType.LINE
                    ),
                    MarkerTrackLineConfig(
                        key = "jvmUser",
                        strokeColor = "blue",
                        type = MarkerTrackConfigLineType.LINE
                    )
                ),
                isPreSelected = true
            )
            "jdk.NetworkUtilization" -> MarkerTrackConfig(
                label = "Network Utilization",
                height = MarkerTrackConfigLineHeight.LARGE,
                lines = listOf(
                    MarkerTrackLineConfig(
                        key = "readRate",
                        strokeColor = "blue",
                        type = MarkerTrackConfigLineType.LINE
                    ),
                    MarkerTrackLineConfig(
                        key = "writeRate",
                        strokeColor = "orange",
                        type = MarkerTrackConfigLineType.LINE
                    )
                )
            )
            else -> null
        }
        return MarkerSchemaFieldMapping(name, mapping) to MarkerSchema(
            name,
            tooltipLabel = eventType.label ?: name,
            tableLabel = combinedLabel,
            display = display,
            data = data,
            trackConfig = trackConfig
        )
    }

    fun toMarkerSchemaList() = schemas.distinctBy { it.name }.toList()
}
