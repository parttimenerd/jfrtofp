package me.bechberger.jfrtofp.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable(with = MarkerFormatTypeSerializer::class)
sealed interface MarkerFormatType

// Provide different formatting options for Strings.
@Serializable
enum class BasicMarkerFormatType : MarkerFormatType {
    // ----------------------------------------------------
    // String types.

    // Show the URL, and handle PII sanitization
    // TODO Handle PII sanitization. Issue #2757
    @SerialName("url")
    URL,

    // TODO Handle PII sanitization. Issue #2757
    // Show the file path, and handle PII sanitization.
    @SerialName("file-path")
    FILE_PATH,

    // Important, do not put URL or file path information here, as it will not be
    // sanitized. Please be careful with including other types of PII here as well.
    // e.g. "Label: Some String"
    @SerialName("string")
    STRING,

    // ----------------------------------------------------
    // Numeric types

    // Note: All time and durations are stored as milliseconds.

    // For time data that represents a duration of time.
    // e.g. "Label: 5s, 5ms, 5μs"
    @SerialName("duration")
    DURATION,

    // Data that happened at a specific time, relative to the start of
    // the profile. e.g. "Label: 15.5s, 20.5ms, 30.5μs"
    @SerialName("time")
    TIME,

    // The following are alternatives to display a time only in a specific
    // unit of time.
    @SerialName("seconds") // "Label: 5s"
    SECONDS,

    @SerialName("milliseconds") // "Label: 5ms"
    MILLISECONDS,

    @SerialName("microseconds") // "Label: 5μs"
    MICROSECONDS,

    @SerialName("nanoseconds") // "Label: 5ns"
    NANOSECONDS,

    // e.g. "Label: 5.55mb, 5 bytes, 312.5kb"
    @SerialName("bytes")
    BYTES,

    // This should be a value between 0 and 1.
    // "Label: 50%"
    @SerialName("percentage")
    PERCENTAGE,

    // The integer should be used for generic representations of Ints. Do not
    // use it for time information.
    // "Label: 52, 5,323, 1,234,567"
    @SerialName("integer")
    INTEGER,

    // The decimal should be used for generic representations of Ints. Do not
    // use it for time information.
    // "Label: 52.23, 0.0054, 123,456.78"
    @SerialName("decimal")
    DECIMAL,

    @SerialName("list")
    LIST,
}

@Experimental
@Serializable
data class TableColumnFormat(
    val type: MarkerFormatType? = null,
    val label: String? = null
)

@Experimental
@Serializable
data class TableMarkerFormat(val columns: List<TableColumnFormat>, val type: String = "table") : MarkerFormatType

object MarkerFormatTypeSerializer : JsonContentPolymorphicSerializer<MarkerFormatType>(MarkerFormatType::class) {
    override fun selectDeserializer(element: JsonElement) = when (element) {
        is JsonPrimitive -> BasicMarkerFormatType.serializer()
        else -> TableMarkerFormat.serializer()
    }
}

// A list of all the valid locations to surface this marker.
// We can be free to add more UI areas.
@Serializable
enum class MarkerDisplayLocation {
    @SerialName("marker-chart")
    MARKER_CHART,

    @SerialName("marker-table")
    MARKER_TABLE,

    // This adds markers to the main marker timeline in the header.
    @SerialName("timeline-overview")
    TIMELINE_OVERVIEW,

    // In the timeline, this is a section that breaks out markers that are related
// to memory. When memory counters are enabled, this is its own track, otherwise
// it is displayed with the main thread.
    @SerialName("timeline-memory")
    TIMELINE_MEMORY,

    // This adds markers to the IPC timeline area in the header.
    @SerialName("timeline-ipc")
    TIMELINE_IPC,

    // This adds markers to the FileIO timeline area in the header.
    @SerialName("timeline-fileio")
    TIMELINE_FILEIO,

    // TODO - This is not supported yet.
    @SerialName("stack-chart")
    STACK_CHART
}

@Serializable
enum class MarkerTrackConfigLineType {
    @SerialName("bar")
    BAR,
    @SerialName("line")
    LINE,
}

@Serializable
data class MarkerTrackLineConfig(
    val key: String,
    val fillColor: String? = null,
    // magenta, purple, teal, green, yellow, orange, red, transparent, grey, string
    val strokeColor: String? = null,
    val width: Int? = null,
    // "line" or "bar"
    val type: MarkerTrackConfigLineType? = null,
    val isPreScaled: Boolean? = null
)

@Serializable
enum class MarkerTrackConfigLineHeight {
    @SerialName("small")
    SMALL,
    @SerialName("medium")
    MEDIUM,
    @SerialName("large")
    LARGE
}

@Serializable
data class MarkerTrackConfig(
    val label: String,
    val tooltip: String? = null,
    val height: MarkerTrackConfigLineHeight? = null,
    val isPreSelected: Boolean = false,
    val lines: List<MarkerTrackLineConfig>
)

@Serializable
data class MarkerSchema(
    // The unique identifier for this marker.
    val name: String, // e.g. "CC"

    // The label of how this marker should be displayed in the UI.
    // If none is provided, then the name is used.
    val tooltipLabel: String? = null, // e.g. "Cycle Collect"

    // This is how the marker shows up in the Marker Table description.
    // If none is provided, then the name is used.
    val tableLabel: String? = null, // e.g. "{marker.data.eventType} – DOMEvent"

    // This is how the marker shows up in the Marker Chart, where it is drawn
    // on the screen as a bar.
    // If none is provided, then the name is used.
    val chartLabel: String? = null,

    // The locations to display
    val display: List<MarkerDisplayLocation>,

    val data: List<MarkerSchemaData>,

    @Experimental
    val trackConfig: MarkerTrackConfig? = null
)

@Serializable(with = MarkerSchemaDataSerializer::class)
sealed class MarkerSchemaData

@Serializable
data class MarkerSchemaDataString(
    val key: String,
    val label: String? = null,
    val format: MarkerFormatType,
    val searchable: Boolean? = null,
    // hidden in the side bar and tooltips?
    val isHidden: Boolean? = null
) : MarkerSchemaData()

// This type is a static bit of text that will be displayed
@Serializable
data class MarkerSchemaDataStatic(
    val label: String,
    val value: String
) : MarkerSchemaData()

object MarkerSchemaDataSerializer : JsonContentPolymorphicSerializer<MarkerSchemaData>(MarkerSchemaData::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "format" in element.jsonObject -> MarkerSchemaDataString.serializer()
        else -> MarkerSchemaDataStatic.serializer()
    }
}

typealias MarkerSchemaByName = ObjectMap<MarkerSchema>

// These integral values are exported in the JSON of the profile, and are in the
// RawMarkerTable. They represent a C++ class in Gecko that defines the type of
// marker it is. These markers are then combined together to form the Marker[] type.
// See deriveMarkersFromRawMarkerTable for more information. Also see the constants.js
// file for JS values that can be used to refer to the different phases.
//
// From the C++:
//
// enum class MarkerPhase : int {
//   Instant = 0,
//   Interval = 1,
//   IntervalStart = 2,
//   IntervalEnd = 3,
// };
typealias MarkerPhase = Int

@Suppress("unused")
data class MarkerData(
    /** marker schema */
    val type: String,
    val cause: IndexIntoStackTable? = null
)
