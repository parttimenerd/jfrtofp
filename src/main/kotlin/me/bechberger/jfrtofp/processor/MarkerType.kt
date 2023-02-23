package me.bechberger.jfrtofp.processor

import jdk.jfr.ValueDescriptor
import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedObject
import jdk.jfr.consumer.RecordedThread
import me.bechberger.jfrtofp.types.BasicMarkerFormatType
import me.bechberger.jfrtofp.types.MarkerFormatType
import me.bechberger.jfrtofp.types.TableColumnFormat
import me.bechberger.jfrtofp.types.TableMarkerFormat
import me.bechberger.jfrtofp.util.ByteCodeHelper
import me.bechberger.jfrtofp.util.formatBytes
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.logging.Logger

enum class MarkerType(
    val type: MarkerFormatType,
    val converter: (
        tables: Tables,
        /** only required for stack traces */
        startTime: Instant?,
        fieldValue: Any
    ) -> Any = { _, _, fieldValue ->
        fieldValue.toString()
    },
    val aliases: List<String> = emptyList(),
    /* unspecific type */
    val generic: Boolean = false
) {
    // look first for contentType, then for field name and last for actual type
    BOOLEAN(BasicMarkerFormatType.STRING),
    BYTES(
        BasicMarkerFormatType.BYTES,
        { _, _, fieldValue ->
            when (fieldValue) {
                is Long -> fieldValue.toLong()
                is Double -> fieldValue.toDouble()
                else -> throw IllegalArgumentException("Cannot convert $fieldValue to bytes")
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
        { _, _, fieldValue -> "0x" + (fieldValue as Long).toString(16) },
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
        { _, _, fieldValue -> fieldValue as Long },
        generic = true
    ),
    UNSIGNED(
        BasicMarkerFormatType.INTEGER,
        { _, _, fieldValue -> fieldValue as Long },
        generic = true
    ),
    INT(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Int }),
    UINT(
        BasicMarkerFormatType.INTEGER,
        { _, _, fieldValue -> fieldValue as Long },
        generic = true
    ),
    USHORT(
        BasicMarkerFormatType.INTEGER,
        { _, _, fieldValue -> fieldValue as Long },
        generic = true
    ),
    LONG(
        BasicMarkerFormatType.INTEGER,
        { _, _, fieldValue -> fieldValue as Long },
        generic = true
    ),
    FLOAT(
        BasicMarkerFormatType.DECIMAL,
        { _, _, fieldValue -> fieldValue as Double },
        generic = true
    ),
    TABLE(
        TableMarkerFormat(columns = listOf(TableColumnFormat(), TableColumnFormat())),
        { tables, _, fieldValue ->
            when (fieldValue) {
                is RecordedObject -> tableFormatter(tables, fieldValue)
                else -> fieldValue.toString()
            }
        },
        generic = true
    ),
    STRING(
        BasicMarkerFormatType.STRING,
        { _, _, fieldValue -> fieldValue.toString() },
        generic = true
    ),
    ULONG(
        BasicMarkerFormatType.INTEGER,
        { _, _, fieldValue -> fieldValue as Long },
        generic = true
    ),
    DOUBLE(
        BasicMarkerFormatType.DECIMAL,
        { _, _, fieldValue -> fieldValue as Double },
        generic = true
    ),
    MILLIS(
        BasicMarkerFormatType.MILLISECONDS,
        { tables, _, fieldValue -> (fieldValue as Long) - tables.basicInformation.startTimeMillis }
    ),
    TIMESTAMP(
        BasicMarkerFormatType.INTEGER,
        { tables, _, fieldValue ->
            val startTimeMillis = tables.basicInformation.startTimeMillis
            var longValue = (fieldValue as Long) * 1.0
            while (longValue > startTimeMillis * 100) { // get it in the same ball-park, works with timestamps but not with time spans
                longValue /= 1000
            }
            longValue - tables.basicInformation.startTimeMillis
        }
    ),
    TIMESPAN(
        BasicMarkerFormatType.DURATION,
        { _, _, fieldValue ->
            (fieldValue as Long) / 1000_000.0
        }
    ),

    NANOS(BasicMarkerFormatType.MILLISECONDS, { _, _, fieldValue -> (fieldValue as Long) / 1000.0 }),
    PERCENTAGE(
        BasicMarkerFormatType.PERCENTAGE,
        { _, _, fieldValue -> fieldValue as Double },
    ),
    EVENT_THREAD(BasicMarkerFormatType.STRING, { _, _, fieldValue ->
        (fieldValue as RecordedThread).let {
            "${it.javaName} (${it.id})"
        }
    }),

    COMPILER_PHASE_TYPE(STRING), COMPILER_TYPE(STRING), DEOPTIMIZATION_ACTION(
        STRING
    ),
    DEOPTIMIZATION_REASON(STRING), FLAG_VALUE_ORIGIN(STRING), FRAME_TYPE(
        STRING
    ),
    G1_HEAP_REGION_TYPE(STRING), G1_YC_TYPE(STRING), GC_CAUSE(STRING), GC_NAME(
        STRING
    ),
    GC_THRESHHOLD_UPDATER(STRING), GC_WHEN(STRING), INFLATE_CAUSE(STRING), MODIFIERS(
        BasicMarkerFormatType.STRING,
        { _, _, fieldValue ->
            val modInt = fieldValue as Int
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
        { _, _, fieldValue -> fieldValue as Long }
    ),
    BYTES_PER_SECOND(BasicMarkerFormatType.BYTES, { _, _, fieldValue -> fieldValue as Double }),
    BITS_PER_SECOND(
        BasicMarkerFormatType.BYTES,
        { _, _, fieldValue -> (fieldValue as Double) / 8 }
    ),
    METADATA_TYPE(STRING), METASPACE_OBJECT_TYPE(STRING), NARROW_OOP_MODE(
        STRING
    ),
    NETWORK_INTERFACE_NAME(STRING), OLD_OBJECT_ROOT_TYPE(
        STRING
    ),
    OLD_OBJECT_ROOT_SYSTEM(STRING), REFERENCE_TYPE(STRING), ShenandoahHeapRegionState(
        STRING
    ),
    STACKTRACE(BasicMarkerFormatType.INTEGER, { tables, startTime, fieldValue ->
        val st = fieldValue as jdk.jfr.consumer.RecordedStackTrace
        if (st.frames.isEmpty()) {
            0
        } else {
            mutableMapOf<String, Any?>(
                "stack" to tables.stackTraceTable.getStack(st, Int.MAX_VALUE),
                "time" to startTime
            )
        }
    }),
    SYMBOL(STRING), ThreadState(STRING), TICKS(
        BasicMarkerFormatType.INTEGER,
        { _, _, fieldValue -> fieldValue as Long }
    ),
    TICKSPAN(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Long }), VMOperationType(
        STRING
    ),
    ZPageTypeType(STRING), ZStatisticsCounterType(STRING), ZStatisticsSamplerType(
        STRING
    ),
    PATH(
        BasicMarkerFormatType.FILE_PATH,
        { _, _, fieldValue -> fieldValue as String }
    ),
    CLASS(
        BasicMarkerFormatType.STRING,
        { _, _, fieldValue -> ByteCodeHelper.formatRecordedClass(fieldValue as RecordedClass) }
    ),
    METHOD(
        BasicMarkerFormatType.STRING,
        { _, _, fieldValue -> ByteCodeHelper.formatFunctionWithClass(fieldValue as RecordedMethod) }
    );

    constructor(type: MarkerType, generic: Boolean = false) : this(
        type.type,
        { tables, startTime, fieldValue ->
            type.converter(tables, startTime, fieldValue)
        },
        generic = generic
    )

    fun convert(
        tables: Tables,
        startTime: Instant?,
        fieldValue: Any
    ): Any {
        return try {
            converter(tables, startTime, fieldValue)
        } catch (e: Exception) {
            LOG.throwing("MarkerType", "convert", e)
            TABLE.converter(tables, startTime, fieldValue)
        }
    }

    companion object {
        private val BYTE_FIELDS = setOf("committed", "reserved", "used", "gcThreshold", "unallocatedCapacity")
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
                if ((field.label ?: field.name).lowercase().endsWith(" pointer")) {
                    return@computeIfAbsent ADDRESS
                }
                if (field.name.endsWith("Size") || field.name in BYTE_FIELDS) {
                    return@computeIfAbsent BYTES
                }
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
            value: RecordedObject
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
                            .forEach { (field, _) -> addField(path + fieldName(field), field, fieldValue) }
                    } else {
                        var type = fromName(
                            field
                        )
                        if (type == TABLE) {
                            // prevent infinite recursion
                            type = STRING
                        }
                        fields.add(
                            path to (
                                base.getValue<Any>(field.name)?.let {
                                    if (type == BYTES) {
                                        (it as Long).formatBytes()
                                    } else {
                                        type.convert(tables, null, it).toString()
                                    }
                                } ?: ""
                                )
                        )
                    }
                }

                for (field in value.fields) {
                    addField(listOf(fieldName(field)), field, value)
                }

                return fields.map { (path, value) -> listOf(path.joinToString("."), value) }.toList()
            } catch (e: Exception) {
                println("Error getting value for $value: ${e.message}")
                throw e
            }
        }

        private val LOG = Logger.getLogger("MarkerType")
    }
}
