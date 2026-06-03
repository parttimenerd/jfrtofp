package me.bechberger.jfrtofp.processor

import jdk.jfr.EventType
import me.bechberger.jfrtofp.types.Milliseconds
import me.bechberger.jfrtofp.util.ByteCodeHelper
import me.bechberger.jfrtofp.util.Percentage
import java.time.Instant

/**
 * Lightweight representation of a JFR event produced by Jafar's streaming parser.
 *
 * Replaces JDK [jdk.jfr.consumer.RecordedEvent] in the hot processing path.
 * Stack frames are parallel arrays to avoid per-frame object allocation.
 * All timestamps are already in milliseconds (Jafar provides nanos; conversion is at parse time).
 */
class ParsedJFREvent(
    val typeName: String,
    val startMs: Milliseconds,
    val endMs: Milliseconds,
    val thread: JFRThread?,
    /** Flattened field map.  Keys are field names (nested objects are dot-joined, e.g. "heapSpace.reservedSize"). */
    val fields: Map<String, Any>,
    /** Number of valid entries in the parallel frame arrays below. */
    val stackDepth: Int,
    val frameClassNames: Array<String>,
    val frameMethodNames: Array<String>,
    val frameDescriptors: Array<String>,
    val frameLineNumbers: IntArray,
    val frameIsJava: BooleanArray,
) {
    val startTime: Instant get() = Instant.ofEpochMilli(startMs.toLong())
    val endTime: Instant get() = Instant.ofEpochMilli(endMs.toLong())

    fun hasField(name: String) = fields.containsKey(name)

    fun getString(name: String): String? = fields[name]?.toString()
    fun getLong(name: String): Long? = when (val v = fields[name]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        null -> null
        else -> v.toString().toLongOrNull()
    }
    fun getInt(name: String): Int? = getLong(name)?.toInt()
    fun getFloat(name: String): Float? = when (val v = fields[name]) {
        is Double -> v.toFloat()
        is Float -> v
        is Long -> v.toFloat()
        null -> null
        else -> v.toString().toFloatOrNull()
    }
    fun getDouble(name: String): Double? = when (val v = fields[name]) {
        is Double -> v
        is Long -> v.toDouble()
        null -> null
        else -> v.toString().toDoubleOrNull()
    }
    fun getBoolean(name: String): Boolean? = when (val v = fields[name]) {
        is Boolean -> v
        null -> null
        else -> v.toString().toBooleanStrictOrNull()
    }
    /** Returns the value as a raw [Any] for the MarkerType converter. */
    fun getValue(name: String): Any? = fields[name]
}

/** Lightweight thread descriptor extracted from a Jafar event. */
data class JFRThread(
    val id: Long,
    val javaName: String?,
    val osName: String?,
    val isVirtual: Boolean = false,
) {
    val realJavaName: String?
        get() = if (javaName.isNullOrEmpty()) (if (isVirtual) "VirtualThread$id" else null) else javaName
    val name: String? get() = realJavaName ?: osName
}

/**
 * Replacement for [jdk.jfr.consumer.RecordedMethod] used as deduplication key in the frame/func tables.
 */
data class MethodKey(
    val className: String,
    val methodName: String,
    val descriptor: String,
) {
    val pkg: String get() = className.split("$")[0].split(".").let { it.subList(0, it.size - 1).joinToString(".") }
    val simpleClassName: String get() = pkg.length.let { p -> if (p == 0) className else className.substring(p + 1) }

    fun formattedWithClass() = "$simpleClassName.$methodName${ByteCodeHelper.formatDescriptor(descriptor)}"

    fun isNonProjectType(nonProjectPackagePrefixes: List<String>): Boolean =
        nonProjectPackagePrefixes.any { className.startsWith(it) }
}

/**
 * Metadata collected from the pre-scan pass (replaces [BasicInformation]'s [jdk.jfr.consumer.RecordedEvent] fields).
 */
data class JFRMetaFields(
    val jvmVersion: String?,
    val jvmArgs: String?,
    val javaArgs: String?,
    val pid: Long,
    val cpuModel: String?,
    val cpuCores: Int?,
    val hwThreads: Int?,
    val osVersion: String?,
)
