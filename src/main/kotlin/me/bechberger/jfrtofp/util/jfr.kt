package me.bechberger.jfrtofp.util

import jdk.jfr.EventType
import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedThread
import me.bechberger.jfrtofp.types.Milliseconds
import org.objectweb.asm.Type
import java.nio.file.Path
import java.time.Instant

const val MAX_INTERVAL: Milliseconds = 1000.0

fun Instant.toNanos(): Long = epochSecond * 1_000_000_000 + nano

fun Instant.toMicros(): Long = toNanos() / 1_000

fun Instant.toMillis(): Milliseconds = toMicros() / 1_000.0

fun List<Milliseconds>.estimateInterval(): Milliseconds {
    val sortedList = sorted()
    val differences = sorted().zip(sortedList.drop(1)).map { (a, b) -> b - a }.filter { it > 0 && it < MAX_INTERVAL }
    // remove all differences that are more than double the size of sliding average of the previous five
    // but don't take into account skipped differences when calculating the average
    // with an efficient algorithm
    val filteredDifferences = mutableListOf<Milliseconds>()
    var sum = 0.0
    var count = 0
    for (i in 0 until differences.size) {
        val diff = differences[i]
        if (i >= 5) {
            val avg = sum / count
            if (diff > avg * 2) {
                continue
            }
        }
        filteredDifferences.add(diff)
        sum += diff
        count++
        if (count > 5) {
            sum -= differences[i - 5]
            count--
        }
    }
    // take the middle 80% to throw away outliers
    val subset = filteredDifferences.sorted().let { it.subList((it.size * 0.1).toInt(), (it.size * 0.8).toInt()) }
    // take the average
    return subset.average()
}

fun Collection<List<Milliseconds>>.estimateInterval(): Milliseconds {
    // return the average of the averages weighted by the number of samples
    val filtered = filter { it.size > 5 }
    val averages = filtered.map { it.estimateInterval() }
    val weights = filtered.map { it.size }
    val weightedAverages = averages.zip(weights).map { (avg, weight) -> avg * weight }
    val sumOfWeights = weights.sum()
    return weightedAverages.sum() / sumOfWeights
}

fun Collection<List<Milliseconds>>.estimateMinInterval(): Milliseconds {
    // take the minimum of the 10% smallest intervals of all lists with more than 2 samples
    // compute the intervals for each list by list[i] - list[i-1] and ignoring intervals
    // larger than MAX_INTERVAL and <= 0
    val filtered = filter { it.size > 2 }
    val intervals =
        filtered.map { list ->
            list.sorted().zip(list.sorted().drop(1)).map { (a, b) -> b - a }.filter { it > 0 && it < MAX_INTERVAL }
        }
    val smallestIntervals = intervals.map { it.sorted().subList(0, (it.size * 0.1).toInt()) }
    val minIntervals = smallestIntervals.map { it.minOrNull() }.filterNotNull()
    return minIntervals.minOrNull() ?: 0.0
}

fun Map<RecordedThread, List<RecordedEvent>>.estimateMinInterval(): Milliseconds {
    val startTimesPerThread =
        values.map { events ->
            events.map { it.startTime.toMillis() }
        }
    return startTimesPerThread.estimateMinInterval()
}

fun estimateIntervalInMillis(startTimesPerThread: Map<Long, List<Milliseconds>>): Milliseconds {
    return startTimesPerThread.values.estimateInterval()
}

val RecordedEvent.isExecutionSample
    get() = eventType.name.equals("jdk.ExecutionSample") || eventType.name.equals("jdk.NativeMethodSample")

val RecordedClass.pkg
    get() =
        name.split("$")[0].split(".").let {
            it.subList(0, it.size - 1).joinToString(".")
        }

val RecordedClass.className
    get() =
        pkg.length.let { p ->
            if (p == 0) {
                name
            } else {
                name.substring(pkg.length + 1)
            }
        }

fun RecordedThread.isSystemThread(): Boolean {
    return !isVirtualThread() && (
        javaName == null || javaName in
            listOf(
                "JFR Periodic Tasks",
                "JFR Shutdown Hook",
                "Permissionless thread",
                "Thread Monitor CTRL-C",
            ) || threadGroup?.name == "system" || isGCThread() ||
            javaName.startsWith("JFR ") ||
            javaName == "Monitor Ctrl-Break" || "CompilerThread" in javaName ||
            javaName.startsWith("GC Thread") || javaName == "Notification Thread" ||
            javaName == "Finalizer" || javaName == "Attach Listener"
    )
}

fun RecordedThread.isVirtualThread(): Boolean {
    return this.hasField("virtual") && this.getBoolean("virtual")
}

val RecordedThread.realJavaName: String?
    get() = if (javaName.isNullOrEmpty()) (if (isVirtualThread()) "VirtualThread$javaThreadId" else null) else javaName

val RecordedThread.name: String?
    get() = realJavaName ?: osName

fun RecordedThread.isGCThread() = !isVirtualThread() && osName.startsWith("GC Thread") && javaName == null

private val RecordedEvent.isSampledThreadCorrectProperty
    get() = eventType.name == "jdk.NativeMethodSample" || eventType.name == "jdk.ExecutionSample"

val RecordedEvent.sampledThread: RecordedThread
    get() = sampledThreadOrNull!!

val RecordedEvent.sampledThreadOrNull: RecordedThread?
    get() = if (isSampledThreadCorrectProperty) getThread("sampledThread") else thread

fun List<RecordedEvent>.groupByType() = groupBy { if (it.eventType.name == "jdk.NativeMethodSample") "jdk.ExecutionSample" else it.eventType.name }

val RecordedEvent.realThread: RecordedThread?
    get() = thread ?: sampledThreadOrNull ?: (if (hasField("thread")) getThread("thread") else null)

/** -1 if not thread present */
val RecordedEvent.realThreadId: Long
    get() = realThread?.id ?: PROCESS_THREAD_ID

/** the real thread id associated with events without any thread, like jdk.EnvironmentVariable */
const val PROCESS_THREAD_ID = -1L

typealias Percentage = Float

/** Helps to format types and other byte code related things */
object ByteCodeHelper {
    fun formatFunctionWithClass(func: RecordedMethod) = "${func.type.className}.${func.name}${formatDescriptor(func.descriptor)}"

    fun formatDescriptor(descriptor: String): String {
        val args = "(${
            Type.getArgumentTypes(descriptor).joinToString(", ") {
                formatByteCodeType(it, omitPackages = true)
            }})"
        if (Type.getReturnType(descriptor) == Type.VOID_TYPE) {
            return args
        }
        return args + ": " + formatByteCodeType(Type.getReturnType(descriptor), omitPackages = true)
    }

    fun shortenClassName(className: String): String {
        val lastDot = className.lastIndexOf('.')
        return if (lastDot == -1) {
            className
        } else {
            className.substring(lastDot + 1)
        }
    }

    fun formatByteCodeType(
        type: String,
        omitPackages: Boolean,
    ) = formatByteCodeType(Type.getType(type), omitPackages)

    fun formatByteCodeType(
        type: Type,
        omitPackages: Boolean,
    ): String =
        when (type.sort) {
            Type.VOID -> "void"
            Type.BOOLEAN -> "boolean"
            Type.CHAR -> "char"
            Type.BYTE -> "byte"
            Type.SHORT -> "short"
            Type.INT -> "int"
            Type.FLOAT -> "float"
            Type.LONG -> "long"
            Type.DOUBLE -> "double"
            Type.ARRAY -> formatByteCodeType(type.elementType, omitPackages) + "[]".repeat(type.dimensions)
            Type.OBJECT -> if (omitPackages) shortenClassName(type.className) else type.className
            else -> throw IllegalArgumentException("Unknown type sort: ${type.sort}")
        }

    fun formatRecordedClass(klass: RecordedClass): String {
        val name = klass.getString("name")
        val byteCodeName =
            if (name.startsWith("[")) {
                name
            } else {
                "L$name;"
            }
        return formatByteCodeType(byteCodeName, omitPackages = false)
    }
}

fun EventType.hasField(name: String) = getField(name) != null

val Path.fileExtension: String
    get() = fileName.toString().substringAfterLast('.', "")

/**
 * The RecordMethods are per chunk, it isn't possible, therefore, to use them as keys in maps directly
 *
 * Using their identity caused https://github.com/parttimenerd/jfrtofp/issues/6
 */
data class HashableRecordedMethod(val method: RecordedMethod, private var hash: Int = 0) {
    init {
        hash = method.name.hashCode() * 31 + method.type.name.hashCode() * 31 + method.descriptor.hashCode()
    }

    override fun hashCode(): Int {
        return hash
    }

    override fun equals(other: Any?): Boolean {
        if (other is RecordedMethod) {
            return method.name == other.name && method.type.name == other.type.name && method.descriptor == other.descriptor
        } else if (other !is HashableRecordedMethod) {
            return false
        }
        return equals(other.method)
    }
}
