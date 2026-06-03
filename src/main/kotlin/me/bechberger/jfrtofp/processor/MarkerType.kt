package me.bechberger.jfrtofp.processor

import io.jafar.parser.internal_api.metadata.MetadataField
import jdk.jfr.ValueDescriptor
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
        fieldValue: Any,
    ) -> Any = { _, _, fieldValue ->
        fieldValue.toString()
    },
    val aliases: List<String> = emptyList(),
    val generic: Boolean = false,
) {
    BOOLEAN(BasicMarkerFormatType.STRING),
    BYTES(
        BasicMarkerFormatType.BYTES,
        { _, _, fieldValue ->
            when (fieldValue) {
                is Long -> fieldValue.toLong()
                is Double -> fieldValue.toDouble()
                is Int -> fieldValue.toLong()
                else -> throw IllegalArgumentException("Cannot convert $fieldValue to bytes")
            }
        },
        listOf(
            "dataAmount", "allocated", "totalSize", "usedSize", "initialSize",
            "reservedSize", "nonNMethodSize", "profiledSize", "nonProfiledSize",
            "expansionSize", "minBlockLength", "minSize", "maxSize",
            "osrBytesCompiled", "minTLABSize", "tlabRefillWasteLimit",
        ),
    ),
    ADDRESS(
        BasicMarkerFormatType.STRING,
        { _, _, fieldValue -> "0x" + (fieldValue as Long).toString(16) },
        listOf("baseAddress", "topAddress", "startAddress", "reservedTopAddress", "heapAddressBits", "objectAlignment"),
    ),
    UBYTE(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Long }, generic = true),
    UNSIGNED(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Long }, generic = true),
    INT(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> when (fieldValue) { is Int -> fieldValue.toLong(); else -> fieldValue as Long } }),
    UINT(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Long }, generic = true),
    USHORT(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Long }, generic = true),
    LONG(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Long }, generic = true),
    FLOAT(BasicMarkerFormatType.DECIMAL, { _, _, fieldValue -> when (fieldValue) { is Double -> fieldValue; is Float -> fieldValue.toDouble(); else -> (fieldValue as Number).toDouble() } }, generic = true),
    TABLE(
        TableMarkerFormat(columns = listOf(TableColumnFormat(), TableColumnFormat())),
        { tables, _, fieldValue ->
            // Jafar flattens nested objects into the flat field map; by the time we reach
            // TABLE, fieldValue is already a plain value or a Map from the flat fields.
            // We convert to a two-column list representation.
            when (fieldValue) {
                is Map<*, *> -> fieldValue.entries.map { (k, v) -> listOf(k.toString(), v?.toString() ?: "") }
                else -> fieldValue.toString()
            }
        },
        generic = true,
    ),
    STRING(BasicMarkerFormatType.STRING, { _, _, fieldValue -> fieldValue.toString() }, generic = true),
    ULONG(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Long }, generic = true),
    DOUBLE(BasicMarkerFormatType.DECIMAL, { _, _, fieldValue -> when (fieldValue) { is Double -> fieldValue; else -> (fieldValue as Number).toDouble() } }, generic = true),
    MILLIS(
        BasicMarkerFormatType.MILLISECONDS,
        { tables, _, fieldValue -> (fieldValue as Long) - tables.basicInformation.startTimeMillis },
    ),
    TIMESTAMP(
        BasicMarkerFormatType.INTEGER,
        { tables, _, fieldValue ->
            val startTimeMillis = tables.basicInformation.startTimeMillis
            var longValue = (fieldValue as Long) * 1.0
            while (longValue > startTimeMillis * 100) longValue /= 1000
            longValue - tables.basicInformation.startTimeMillis
        },
    ),
    TIMESPAN(BasicMarkerFormatType.DURATION, { _, _, fieldValue -> (fieldValue as Long) / 1000_000.0 }),
    NANOS(BasicMarkerFormatType.MILLISECONDS, { _, _, fieldValue -> (fieldValue as Long) / 1000.0 }),
    PERCENTAGE(BasicMarkerFormatType.PERCENTAGE, { _, _, fieldValue -> when (fieldValue) { is Double -> fieldValue; is Float -> fieldValue.toDouble(); else -> (fieldValue as Number).toDouble() } }),
    EVENT_THREAD(BasicMarkerFormatType.STRING, { _, _, fieldValue ->
        when (fieldValue) {
            is JFRThread -> "${fieldValue.realJavaName ?: fieldValue.osName ?: "?"} (${fieldValue.id})"
            is Map<*, *> -> {
                val name = fieldValue["javaName"] ?: fieldValue["osName"] ?: "?"
                val id = fieldValue["javaThreadId"] ?: fieldValue["osThreadId"] ?: "?"
                "$name ($id)"
            }
            else -> fieldValue.toString()
        }
    }),
    COMPILER_PHASE_TYPE(STRING),
    COMPILER_TYPE(STRING),
    DEOPTIMIZATION_ACTION(STRING),
    DEOPTIMIZATION_REASON(STRING),
    FLAG_VALUE_ORIGIN(STRING),
    FRAME_TYPE(STRING),
    G1_HEAP_REGION_TYPE(STRING),
    G1_YC_TYPE(STRING),
    GC_CAUSE(STRING),
    GC_NAME(STRING),
    GC_THRESHHOLD_UPDATER(STRING),
    GC_WHEN(STRING),
    INFLATE_CAUSE(STRING),
    MODIFIERS(
        BasicMarkerFormatType.STRING,
        { _, _, fieldValue ->
            val modInt: Int? = when (fieldValue) { is Int -> fieldValue; is Long -> fieldValue.toInt(); else -> null }
            if (modInt == null) {
                fieldValue.toString()
            } else {
                val mods = mutableListOf<String>()
                if (modInt and Modifier.PUBLIC != 0) mods.add("public")
                if (modInt and Modifier.PRIVATE != 0) mods.add("private")
                if (modInt and Modifier.PROTECTED != 0) mods.add("protected")
                if (modInt and Modifier.STATIC != 0) mods.add("static")
                if (modInt and Modifier.FINAL != 0) mods.add("final")
                if (modInt and Modifier.SYNCHRONIZED != 0) mods.add("synchronized")
                if (modInt and Modifier.VOLATILE != 0) mods.add("volatile")
                if (modInt and Modifier.TRANSIENT != 0) mods.add("transient")
                if (modInt and Modifier.NATIVE != 0) mods.add("native")
                if (modInt and Modifier.INTERFACE != 0) mods.add("interface")
                if (modInt and Modifier.ABSTRACT != 0) mods.add("abstract")
                if (modInt and Modifier.STRICT != 0) mods.add("strict")
                mods.joinToString(" ")
            }
        },
    ),
    EPOCH_MILLIS(BasicMarkerFormatType.MILLISECONDS, { _, _, fieldValue -> fieldValue as Long }),
    BYTES_PER_SECOND(BasicMarkerFormatType.BYTES, { _, _, fieldValue -> when (fieldValue) { is Double -> fieldValue; else -> (fieldValue as Number).toDouble() } }),
    BITS_PER_SECOND(BasicMarkerFormatType.BYTES, { _, _, fieldValue -> (when (fieldValue) { is Double -> fieldValue; else -> (fieldValue as Number).toDouble() }) / 8 }),
    METADATA_TYPE(STRING),
    METASPACE_OBJECT_TYPE(STRING),
    NARROW_OOP_MODE(STRING),
    NETWORK_INTERFACE_NAME(STRING),
    OLD_OBJECT_ROOT_TYPE(STRING),
    OLD_OBJECT_ROOT_SYSTEM(STRING),
    REFERENCE_TYPE(STRING),
    ShenandoahHeapRegionState(STRING),
    STACKTRACE(BasicMarkerFormatType.INTEGER, { tables, startTime, fieldValue ->
        // With Jafar: fieldValue is the stack index we already computed (an Int/IndexIntoStackTable),
        // or -1 if no stack. The STACKTRACE MarkerType is no longer called with a RecordedStackTrace —
        // the Tables.getStack() is invoked in RawMarkerTableWrapper.processEvent for the stackTrace field.
        // Here we just wrap the index in the expected {stack, time?} map.
        val stackIdx: Int? = when (fieldValue) {
            is Int -> fieldValue
            is Long -> fieldValue.toInt()
            else -> null
        }
        if (stackIdx == null || stackIdx < 0) {
            0
        } else {
            val map = mutableMapOf<String, Any?>("stack" to stackIdx)
            if (!tables.config.minimalMarkerPayload) {
                map["time"] = startTime
            }
            map
        }
    }),
    SYMBOL(STRING),
    ThreadState(STRING),
    TICKS(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Long }),
    TICKSPAN(BasicMarkerFormatType.INTEGER, { _, _, fieldValue -> fieldValue as Long }),
    VMOperationType(STRING),
    ZPageTypeType(STRING),
    ZStatisticsCounterType(STRING),
    ZStatisticsSamplerType(STRING),
    PATH(BasicMarkerFormatType.FILE_PATH, { _, _, fieldValue -> fieldValue as String }),
    CLASS(
        BasicMarkerFormatType.STRING,
        { _, _, fieldValue ->
            when (fieldValue) {
                is String -> {
                    val byteCodeName = if (fieldValue.startsWith("[")) fieldValue else "L$fieldValue;"
                    ByteCodeHelper.formatByteCodeType(byteCodeName, omitPackages = false)
                }
                is Map<*, *> -> fieldValue["name"]?.toString() ?: fieldValue.toString()
                else -> fieldValue.toString()
            }
        },
    ),
    METHOD(
        BasicMarkerFormatType.STRING,
        { _, _, fieldValue ->
            when (fieldValue) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val m = fieldValue as Map<String, Any?>
                    val className = (m["type"] as? Map<*, *>)?.get("name")?.toString() ?: m["type"]?.toString() ?: ""
                    val methodName = m["name"]?.toString() ?: ""
                    val descriptor = m["descriptor"]?.toString() ?: ""
                    val key = MethodKey(className, methodName, descriptor)
                    ByteCodeHelper.formatFunctionWithClass(key)
                }
                else -> fieldValue.toString()
            }
        },
    ),
    ;

    constructor(type: MarkerType, generic: Boolean = false) : this(
        type.type,
        { tables, startTime, fieldValue -> type.converter(tables, startTime, fieldValue) },
        generic = generic,
    )

    fun convert(tables: Tables, startTime: Instant?, fieldValue: Any): Any {
        return try {
            converter(tables, startTime, fieldValue)
        } catch (e: Exception) {
            LOG.throwing("MarkerType", "convert", e)
            fieldValue.toString()
        }
    }

    companion object {
        private val BYTE_FIELDS = setOf("committed", "reserved", "used", "gcThreshold", "unallocatedCapacity")
        private val map: MutableMap<String, MarkerType> = mutableMapOf()
        private val map2: MutableMap<Triple<String, String, String?>, MarkerType> = java.util.concurrent.ConcurrentHashMap()

        init {
            values().forEach {
                for (name in listOf(it.name) + it.aliases) {
                    map[name.lowercase().replace("_", "")] = it
                }
            }
        }

        /** Resolve from JDK [ValueDescriptor] (legacy/test path). */
        fun fromName(field: ValueDescriptor): MarkerType {
            return map2.computeIfAbsent(Triple(field.typeName, field.name, field.contentType)) {
                if ((field.label ?: field.name).lowercase().endsWith(" pointer")) return@computeIfAbsent ADDRESS
                if (field.name.endsWith("Size") || field.name in BYTE_FIELDS) return@computeIfAbsent BYTES
                val contentTypeResult = field.contentType?.let { map[field.contentType.lowercase().split(".").last()] }
                val otherResult = map[field.name.lowercase()] ?: map[field.typeName.lowercase().split(".").last()] ?: TABLE
                if (otherResult != TABLE && contentTypeResult != null && contentTypeResult.generic) otherResult
                else contentTypeResult ?: otherResult
            }
        }

        /** Resolve from Jafar [MetadataField] (streaming path). */
        fun fromMetadataField(field: MetadataField): MarkerType {
            val typeName = field.type?.name ?: ""
            val contentType = field.annotations?.firstOrNull { it.type?.name == "jdk.jfr.ContentType" }?.value
            val label = field.annotations?.firstOrNull { it.type?.name == "jdk.jfr.Label" }?.value
            val name = field.name
            return map2.computeIfAbsent(Triple(typeName, name, contentType)) {
                if ((label ?: name).lowercase().endsWith(" pointer")) return@computeIfAbsent ADDRESS
                if (name.endsWith("Size") || name in BYTE_FIELDS) return@computeIfAbsent BYTES
                val contentTypeResult = contentType?.let { map[it.lowercase().split(".").last()] }
                val otherResult = map[name.lowercase()] ?: map[typeName.lowercase().split(".").last()] ?: TABLE
                if (otherResult != TABLE && contentTypeResult != null && contentTypeResult.generic) otherResult
                else contentTypeResult ?: otherResult
            }
        }

        private val LOG = Logger.getLogger("MarkerType")
    }
}
