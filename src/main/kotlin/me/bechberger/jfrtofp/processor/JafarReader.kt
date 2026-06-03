package me.bechberger.jfrtofp.processor

import io.jafar.parser.api.ArrayType
import io.jafar.parser.api.ComplexType
import io.jafar.parser.api.ParsingContext
import io.jafar.parser.api.UntypedJafarParser
import io.jafar.parser.api.UntypedStrategy
import io.jafar.parser.internal_api.metadata.MetadataClass
import java.nio.file.Path

/**
 * Streaming JFR reader built on Jafar's [UntypedJafarParser].
 *
 * Jafar reuses its internal parse buffers — the [Map<String,Object>] passed to the handler
 * callback is only valid for the duration of that callback. We extract what we need into
 * a [ParsedJFREvent] and hand it to the caller.
 */
object JafarReader {

    /**
     * @param typeFilter Called with the event type name before [buildEvent]. Return false to skip building and handling the event.
     *                   This avoids allocating a [ParsedJFREvent] and all its fields/frame arrays for events that will be dropped.
     * @param skipFieldsFilter Called with the event type name; return true to skip building the fields map (saving HashMap allocations).
     *                         Use for execution samples that only need the stack trace.
     */
    fun read(
        path: Path,
        typeHandler: ((MetadataClass) -> Unit)? = null,
        typeFilter: ((String) -> Boolean)? = null,
        skipFieldsFilter: ((String) -> Boolean)? = null,
        handler: (ParsedJFREvent) -> Unit,
    ) {
        val seenTypes = if (typeHandler != null) HashSet<String>() else null
        // Thread interning: same JFRThread object reused across all events for the same thread id.
        val threadCache = HashMap<Long, JFRThread>(64)
        UntypedJafarParser.open(path, ParsingContext.create(), UntypedStrategy.FULL_ITERATION).use { parser ->
            parser.handle { type, value, _ ->
                val typeName = type.name
                if (typeFilter != null && !typeFilter(typeName)) return@handle
                if (typeHandler != null && seenTypes!!.add(typeName)) {
                    typeHandler(type)
                }
                val skipFields = skipFieldsFilter?.invoke(typeName) ?: false
                val event = buildEvent(type, value, threadCache, skipFields)
                handler(event)
            }
            parser.run()
        }
    }

    private fun buildEvent(type: MetadataClass, value: Map<String, Any>, threadCache: HashMap<Long, JFRThread>, skipFields: Boolean = false): ParsedJFREvent {
        val rawStartNs = getLong(value, "startTime") ?: 0L
        val rawDurationNs = getLong(value, "duration") ?: 0L
        val startMs = rawStartNs / 1_000_000.0
        val endMs = (rawStartNs + rawDurationNs) / 1_000_000.0

        val thread = extractThread(value, threadCache)

        // Pre-compute stack info first so we know if we have frames before allocating
        val stVal = value["stackTrace"]
        val stMap = asMap(stVal)
        val rawFrames = if (stMap != null) asObjectArray(stMap["frames"]) else null
        val n = if (rawFrames != null && rawFrames.isNotEmpty()) rawFrames.size else 0

        // Build fields map — exclude structural fields and stackTrace
        // skipFields=true for execution samples that only need the stack
        val fields: Map<String, Any> = if (skipFields) {
            emptyMap()
        } else {
            val m = HashMap<String, Any>(value.size * 2)
            // Cap to defend against pathological events: a single event whose nested-map payload
            // explodes during flattening (rare, but observed on jdk.ZStatistics* on >250MB files)
            // can otherwise OOM the whole conversion. Truncating here costs only a few payload
            // fields on the offending event and never affects sample/marker counts or timing.
            for ((key, rawVal) in value) {
                if (m.size >= MAX_FIELDS_PER_EVENT) break
                if (key == "startTime" || key == "duration" || key == "eventThread" || key == "stackTrace") continue
                val v = unwrap(rawVal) ?: continue
                if (v is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    flattenInto(m, key, v as Map<String, Any>, MAX_FIELDS_PER_EVENT)
                } else {
                    m[key] = v
                }
            }
            m
        }

        if (n == 0) {
            return ParsedJFREvent(type.name, startMs, endMs, thread, fields, 0,
                emptyArray(), emptyArray(), emptyArray(), IntArray(0), BooleanArray(0))
        }
        val classNames = Array(n) { "" }
        val methodNames = Array(n) { "" }
        val descriptors = Array(n) { "" }
        val lineNumbers = IntArray(n) { -1 }
        val isJava = BooleanArray(n) { true }
        fillFrames(rawFrames!!, n, classNames, methodNames, descriptors, lineNumbers, isJava)
        return ParsedJFREvent(type.name, startMs, endMs, thread, fields, n,
            classNames, methodNames, descriptors, lineNumbers, isJava)
    }

    private const val MAX_FIELDS_PER_EVENT = 512

    private fun extractThread(value: Map<String, Any>, threadCache: HashMap<Long, JFRThread>): JFRThread? {
        val tMap = asMap(value["eventThread"]) ?: return null
        val id = getLong(tMap, "javaThreadId") ?: getLong(tMap, "osThreadId") ?: -1L
        // Fast path: return cached thread object (same thread appears in thousands of events)
        threadCache[id]?.let { return it }
        val javaName = getStr(tMap, "javaName") ?: getStr(tMap, "javaThreadName")
        val osName = getStr(tMap, "osName") ?: getStr(tMap, "osThreadName")
        val isVirtual = tMap["virtual"]?.let { it as? Boolean } ?: false
        val t = JFRThread(id, javaName, osName, isVirtual)
        threadCache[id] = t
        return t
    }

    private fun fillFrames(rawFrames: Array<Any>, n: Int,
                           classNames: Array<String>, methodNames: Array<String>,
                           descriptors: Array<String>, lineNumbers: IntArray, isJava: BooleanArray) {
        for (i in 0 until n) {
            val frame = asMap(rawFrames[i]) ?: continue
            val frameType = getStr(frame, "type")
            isJava[i] = frameType != "Native"
            lineNumbers[i] = getInt(frame, "lineNumber") ?: -1
            val method = asMap(frame["method"]) ?: continue
            methodNames[i] = getStr(method, "name") ?: ""
            descriptors[i] = getStr(method, "descriptor") ?: ""
            val typeMap = asMap(method["type"])
            classNames[i] = if (typeMap != null) getStr(typeMap, "name") ?: "" else method["type"]?.toString() ?: ""
        }
    }

    private fun flattenInto(out: MutableMap<String, Any>, prefix: String, m: Map<String, Any>, cap: Int = Int.MAX_VALUE) {
        val sb = StringBuilder(prefix.length + 32)
        sb.append(prefix)
        flattenIntoSB(out, sb, m, cap)
    }

    private fun flattenIntoSB(out: MutableMap<String, Any>, sb: StringBuilder, m: Map<String, Any>, cap: Int) {
        val baseLen = sb.length
        for ((k, rawVal) in m) {
            if (out.size >= cap) break
            val v = unwrap(rawVal) ?: continue
            sb.setLength(baseLen)
            sb.append('.').append(k)
            if (v is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                flattenIntoSB(out, sb, v as Map<String, Any>, cap)
            } else {
                out[sb.toString()] = v
            }
        }
        sb.setLength(baseLen)
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(v: Any?): Map<String, Any>? {
        val unwrapped = if (v is ComplexType) v.value else v
        return unwrapped as? Map<String, Any>
    }

    private fun asObjectArray(v: Any?): Array<Any>? {
        val unwrapped = if (v is ArrayType) v.array else v
        return unwrapped as? Array<Any>
    }

    private fun unwrap(v: Any?): Any? {
        var u: Any? = v
        if (u is ComplexType) u = u.value
        if (u is ArrayType) return u.array
        // String constant pool: Map{"string" -> actualValue}
        if (u is Map<*, *> && u.size == 1 && u.containsKey("string")) return u["string"]
        return u
    }

    private fun getStr(m: Map<String, Any>, key: String): String? {
        val v = m[key] ?: return null
        if (v is String) return v
        if (v is Map<*, *>) {
            val s = v["string"]
            return s as? String ?: s?.toString()
        }
        if (v is ComplexType) {
            val s = v.value["string"]
            return s as? String ?: s?.toString()
        }
        return null
    }

    private fun getLong(m: Map<String, Any>, key: String): Long? = when (val v = m[key]) {
        is Long -> v
        is Int -> v.toLong()
        null -> null
        else -> null
    }

    private fun getInt(m: Map<String, Any>, key: String): Int? = when (val v = m[key]) {
        is Int -> v
        is Long -> v.toInt()
        null -> null
        else -> null
    }
}
