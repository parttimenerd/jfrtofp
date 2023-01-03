package me.bechberger.jfrtofp.processor

import jdk.jfr.ValueDescriptor
import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedObject
import me.bechberger.jfrtofp.types.BasicMarkerFormatType
import me.bechberger.jfrtofp.types.MarkerFormatType
import me.bechberger.jfrtofp.types.TableColumnFormat
import me.bechberger.jfrtofp.types.TableMarkerFormat
import me.bechberger.jfrtofp.util.ByteCodeHelper
import me.bechberger.jfrtofp.util.toMillis
import java.lang.reflect.Modifier
import java.util.logging.Logger

enum class MarkerType(
    val type: MarkerFormatType,
    val converter: (
        tables: Tables,
        event: RecordedObject,
        field: String
    ) -> Any = { _, event, field ->
        event.getValue<Any?>(field).toString()
    },
    val aliases: List<String> = emptyList(),
    /* unspecific type */
    val generic: Boolean = false
) {
    // look first for contentType, then for field name and last for actual type
    BOOLEAN(BasicMarkerFormatType.STRING),
    BYTES(
        BasicMarkerFormatType.BYTES,
        { _, event, field ->
            when (val value = event.getValue<Any?>(field)) {
                is Long -> value.toLong()
                is Double -> value.toDouble()
                else -> throw IllegalArgumentException("Cannot convert $value to bytes")
            }
        },
        listOf(
            "dataAmount", "allocated", "totalSize", "usedSize",
            "initialSize", "reservedSize", "nonNMethodSize", "profiledSize",
            "nonProfiledSize", "expansionSize", "minBlockLength", "minSize", "maxSize",
            "osrBytesCompiled", "minTLABSize", "tlabRefillWasteLimit"
        )
    ),
    ADDRESS(
        BasicMarkerFormatType.STRING,
        { _, event, field -> "0x" + event.getLong(field).toString(16) },
        listOf(
            "baseAddress",
            "topAddress",
            "startAddress",
            "reservedTopAddress",
            "heapAddressBits",
            "objectAlignment"
        )
    ),
    UBYTE(
        BasicMarkerFormatType.INTEGER,
        { _, event, field -> event.getLong(field) },
        generic = true
    ),
    UNSIGNED(
        BasicMarkerFormatType.INTEGER,
        { _, event, field -> event.getLong(field) },
        generic = true
    ),
    INT(BasicMarkerFormatType.INTEGER, { _, event, field -> event.getLong(field) }),
    UINT(
        BasicMarkerFormatType.INTEGER,
        { _, event, field -> event.getLong(field) },
        generic = true
    ),
    USHORT(
        BasicMarkerFormatType.INTEGER,
        { _, event, field -> event.getLong(field) },
        generic = true
    ),
    LONG(
        BasicMarkerFormatType.INTEGER,
        { _, event, field -> event.getLong(field) },
        generic = true
    ),
    FLOAT(
        BasicMarkerFormatType.DECIMAL,
        { _, event, field -> event.getDouble(field) },
        generic = true
    ),
    TABLE(
        TableMarkerFormat(columns = listOf(TableColumnFormat(), TableColumnFormat())),
        { tables, event, field -> tableFormatter(tables, event, field) },
        generic = true
    ),
    STRING(
        BasicMarkerFormatType.STRING,
        { _, event, field -> event.getValue<Any?>(field).toString() },
        generic = true
    ),
    ULONG(
        BasicMarkerFormatType.INTEGER,
        { _, event, field -> event.getLong(field) },
        generic = true
    ),
    DOUBLE(
        BasicMarkerFormatType.DECIMAL,
        { _, event, field -> event.getDouble(field) },
        generic = true
    ),
    MILLIS(
        BasicMarkerFormatType.MILLISECONDS,
        { tables, event, field -> event.getLong(field) - tables.basicInformation.startTimeMillis }
    ),
    TIMESTAMP(
        BasicMarkerFormatType.INTEGER,
        { tables, event, field ->
            event.getLong(field) - tables.basicInformation.startTimeMillis
        }
    ),
    TIMESPAN(
        BasicMarkerFormatType.DURATION,
        { _, event, field ->
            event.getLong(field) / 1000_000.0
        }
    ),

    NANOS(BasicMarkerFormatType.MILLISECONDS, { _, event, field -> event.getLong(field) / 1000.0 }),
    PERCENTAGE(
        BasicMarkerFormatType.PERCENTAGE,
        { _, event, field -> event.getDouble(field) }
    ),
    EVENT_THREAD(BasicMarkerFormatType.STRING, { _, event, field ->
        event.getThread(field).let {
            "${it.javaName} (${it.id})"
        }
    }),

    COMPILER_PHASE_TYPE("phase", STRING), COMPILER_TYPE("compiler", STRING), DEOPTIMIZATION_ACTION(
        "action",
        STRING
    ),
    DEOPTIMIZATION_REASON("reason", STRING), FLAG_VALUE_ORIGIN("origin", STRING), FRAME_TYPE(
        "description",
        STRING
    ),
    G1_HEAP_REGION_TYPE("type", STRING), G1_YC_TYPE("type", STRING), GC_CAUSE("cause", STRING), GC_NAME(
        "name",
        STRING
    ),
    GC_THRESHHOLD_UPDATER("updater", STRING), GC_WHEN("when", STRING), INFLATE_CAUSE("cause", STRING), MODIFIERS(
        BasicMarkerFormatType.STRING,
        { _, event, field ->
            val modInt = event.getInt(field)
            val mods = mutableListOf<String>()
            if (modInt and Modifier.PUBLIC != 0) {
                mods.add("public")
            }
            if (modInt and Modifier.PRIVATE != 0) {
                mods.add("private")
            }
            if (modInt and Modifier.PROTECTED != 0) {
                mods.add("protected")
            }
            if (modInt and Modifier.STATIC != 0) {
                mods.add("static")
            }
            if (modInt and Modifier.FINAL != 0) {
                mods.add("final")
            }
            if (modInt and Modifier.SYNCHRONIZED != 0) {
                mods.add("synchronized")
            }
            if (modInt and Modifier.VOLATILE != 0) {
                mods.add("volatile")
            }
            if (modInt and Modifier.TRANSIENT != 0) {
                mods.add("transient")
            }
            if (modInt and Modifier.NATIVE != 0) {
                mods.add("native")
            }
            if (modInt and Modifier.INTERFACE != 0) {
                mods.add("interface")
            }
            if (modInt and Modifier.ABSTRACT != 0) {
                mods.add("abstract")
            }
            if (modInt and Modifier.STRICT != 0) {
                mods.add("strict")
            }
            mods.joinToString(" ")
        }
    ),
    EPOCH_MILLIS(
        BasicMarkerFormatType.MILLISECONDS,
        { _, event, field -> event.getLong(field) }
    ),
    BYTES_PER_SECOND(BasicMarkerFormatType.BYTES, { _, event, field -> event.getDouble(field) }),
    BITS_PER_SECOND(
        BasicMarkerFormatType.BYTES,
        { _, event, field -> event.getDouble(field) / 8 }
    ),
    METADATA_TYPE("type", STRING), METASPACE_OBJECT_TYPE("type", STRING), NARROW_OOP_MODE(
        "mode",
        STRING
    ),
    NETWORK_INTERFACE_NAME("networkInterface", STRING), OLD_OBJECT_ROOT_TYPE(
        "type",
        STRING
    ),
    OLD_OBJECT_ROOT_SYSTEM("system", STRING), REFERENCE_TYPE("type", STRING), ShenandoahHeapRegionState(
        "state",
        STRING
    ),
    STACKTRACE(BasicMarkerFormatType.INTEGER, { tables, event, field ->
        val st = (event as RecordedEvent).stackTrace
        if (st.frames.isEmpty()) {
            0
        } else {
            mutableMapOf<String, Any>(
                "stack" to tables.stackTraceTable.getStack(st, CategoryE.MISC_OTHER, tables.config.maxStackTraceFrames),
                "time" to event.startTime.toMillis()
            )
        }
    }),
    SYMBOL("string", STRING), ThreadState("name", STRING), TICKS(
        BasicMarkerFormatType.INTEGER,
        { _, event, field -> event.getLong(field) }
    ),
    TICKSPAN(BasicMarkerFormatType.INTEGER, { _, event, field -> event.getLong(field) }), VMOperationType(
        "type",
        STRING
    ),
    ZPageTypeType("type", STRING), ZStatisticsCounterType("type", STRING), ZStatisticsSamplerType(
        "type",
        STRING
    ),
    PATH(
        BasicMarkerFormatType.FILE_PATH,
        { _, event, field -> event.getString(field) }
    ),
    CLASS(
        BasicMarkerFormatType.STRING,
        { _, event, field -> ByteCodeHelper.formatRecordedClass(event.getValue<RecordedClass>(field)) }
    ),
    METHOD(
        BasicMarkerFormatType.STRING,
        { _, event, field -> ByteCodeHelper.formatFunctionWithClass(event.getValue<RecordedMethod>(field)) }
    );

    constructor(childField: String, type: MarkerType, generic: Boolean = false) : this(
        type.type,
        { tables, event, field ->
            type.converter(tables, event, field)
        },
        generic = generic
    )

    fun convert(
        tables: Tables,
        event: RecordedObject,
        field: String
    ): Any {
        return try {
            converter(tables, event, field)
        } catch (e: Exception) {
            LOG.throwing("MarkerType", "convert", e)
            TABLE.converter(tables, event, field)
        }
    }

    companion object {
        private val map: MutableMap<String, MarkerType> = mutableMapOf()
        private val map2: MutableMap<Triple<String, String, String?>, MarkerType> = mutableMapOf()

        init {
            values().forEach {
                for (name in listOf(it.name) + it.aliases) {
                    map[name.lowercase().replace("_", "")] = it
                }
            }
        }

        fun fromName(field: ValueDescriptor): MarkerType {
            return map2.computeIfAbsent(Triple(field.typeName, field.name, field.contentType)) {
                val contentTypeResult = field.contentType?.let {
                    map[field.contentType.lowercase().split(".").last()]
                }
                val otherResult = map[field.name.lowercase()]
                    ?: map[field.typeName.lowercase().split(".").last()] ?: TABLE
                val result = if (otherResult != TABLE &&
                    contentTypeResult != null && contentTypeResult.generic
                ) {
                    otherResult
                } else {
                    contentTypeResult ?: otherResult
                }
                result
            }
        }

        fun tableFormatter(
            tables: Tables,
            event: RecordedObject,
            field: String
        ): Any {
            // idea: transform arbitrary objects into tables:
            // --------
            // key path | value
            // --------
            try {
                val fields = mutableListOf<Pair<List<String>, String>>()

                fun fieldName(field: ValueDescriptor) =
                    if (field.label != null && field.label.length < 20) {
                        field.label
                    } else field.name

                fun addField(path: List<String>, field: ValueDescriptor, base: RecordedObject) {
                    val fieldValue = base.getValue<Any?>(field.name)
                    if (fieldValue is RecordedObject) {
                        fields.add(path + "type" to field.typeName)
                        fieldValue.fields.map { it to fieldValue.getValue<Any?>(it.name) }
                            .filter { it.second != null }
                            .forEach { (field, value) -> addField(path + fieldName(field), field, fieldValue) }
                    } else {
                        fields.add(
                            path to (
                                fromName(
                                    field
                                ).converter(tables, base, field.name)
                                ).toString()
                        )
                    }
                }
                addField(emptyList(), event.fields.find { it.name == field }!!, event)
                return fields.map { (path, value) -> listOf(path.joinToString("."), value) }.toList()
            } catch (e: Exception) {
                println("Error getting value for field=$field $event: ${e.message}")
                throw e
            }
        }

        private val LOG = Logger.getLogger("MarkerType")
    }
}
