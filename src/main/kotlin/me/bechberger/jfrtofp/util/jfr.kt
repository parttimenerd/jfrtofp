package me.bechberger.jfrtofp.util

import jdk.jfr.EventType
import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedThread
import me.bechberger.jfrtofp.types.Milliseconds
import org.objectweb.asm.Type
import java.time.Instant
import kotlin.math.roundToLong

fun Instant.toNanos(): Long = epochSecond * 1_000_000_000 + nano

fun Instant.toMicros(): Long = toNanos() / 1_000

fun Instant.toMillis(): Milliseconds = toMicros() / 1_000.0

fun List<RecordedEvent>.estimateIntervalInMicros() = take(100).map { it.startTime.toMicros() }.let {
    it.zip(it.drop(1)).minOfOrNull { (a, b) -> b - a }
}

fun Map<RecordedThread, List<RecordedEvent>>.estimateIntervalInMicros() =
    values.filter { it.size > 2 }.mapNotNull { it.estimateIntervalInMicros() }.average().roundToLong()

fun estimateIntervalInMillis(startTimesPerThread: Map<Long, List<Milliseconds>>): Milliseconds =
    startTimesPerThread.values.filter { it.size > 2 }.map { it ->
        val sorted = it.sorted()
        val diffs = sorted.zip(sorted.drop(1)).map { (a, b) -> b - a }.sorted()
        diffs.average()
    }.average()

val RecordedEvent.isExecutionSample
    get() = eventType.name.equals("jdk.ExecutionSample") || eventType.name.equals("jdk.NativeMethodSample")

val RecordedClass.pkg
    get() = name.split("$")[0].split(".").let {
        it.subList(0, it.size - 1).joinToString(".")
    }

val RecordedClass.className
    get() = pkg.length.let { p ->
        if (p == 0) name
        else name.substring(pkg.length + 1)
    }

fun RecordedThread.isSystemThread(): Boolean {
    return javaName == null || javaName in listOf(
        "JFR Periodic Tasks",
        "JFR Shutdown Hook",
        "Permissionless thread",
        "Thread Monitor CTRL-C"
    ) || threadGroup?.name == "system" || isGCThread() ||
        javaName.startsWith("JFR ") ||
        javaName == "Monitor Ctrl-Break" || "CompilerThread" in javaName ||
        javaName.startsWith("GC Thread") || javaName == "Notification Thread" ||
        javaName == "Finalizer" || javaName == "Attach Listener"
}

fun RecordedThread.isGCThread() = osName.startsWith("GC Thread") && javaName == null

private val RecordedEvent.isSampledThreadCorrectProperty
    get() = eventType.name == "jdk.NativeMethodSample" || eventType.name == "jdk.ExecutionSample"

val RecordedEvent.sampledThread: RecordedThread
    get() = sampledThreadOrNull!!

val RecordedEvent.sampledThreadOrNull: RecordedThread?
    get() = if (isSampledThreadCorrectProperty) getThread("sampledThread") else thread

fun List<RecordedEvent>.groupByType() =
    groupBy { if (it.eventType.name == "jdk.NativeMethodSample") "jdk.ExecutionSample" else it.eventType.name }

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
    fun formatFunctionWithClass(func: RecordedMethod) =
        "${func.type.className}.${func.name}${formatDescriptor(func.descriptor)}"

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

    fun formatByteCodeType(type: String, omitPackages: Boolean) =
        formatByteCodeType(Type.getType(type), omitPackages)

    fun formatByteCodeType(type: Type, omitPackages: Boolean): String = when (type.sort) {
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
        val byteCodeName = if (name.startsWith("[")) {
            name
        } else {
            "L$name;"
        }
        return formatByteCodeType(byteCodeName, omitPackages = false)
    }
}

fun EventType.hasField(name: String) = getField(name) != null
