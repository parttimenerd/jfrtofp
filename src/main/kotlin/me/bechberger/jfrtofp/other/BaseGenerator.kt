package me.bechberger.jfrtofp.other

import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedThread
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.Json
import me.bechberger.jfrtofp.util.sampledThread
import me.bechberger.jfrtofp.util.sampledThreadOrNull
import me.bechberger.jfrtofp.util.toMicros
import java.nio.file.Path
import java.util.Objects
import me.bechberger.jfrtofp.processor.Config
import me.bechberger.jfrtofp.processor.ConfigMixin

internal val jsonFormat =
    Json {
        prettyPrint = false
        encodeDefaults = true
    }

abstract class BaseGenerator(jfrFile: Path, val config: Config) {
    private val events = RecordingFile.readAllEvents(jfrFile)

    internal fun List<RecordedEvent>.perThread(): Map<RecordedThread, List<RecordedEvent>> = groupBy { it.sampledThread }

    internal fun executionSamples() = events.filter { isExecutionSample(it) }

    internal fun executionSamplesWithStartAndEnd(): Triple<List<RecordedEvent>, Map<Long, Long>, Map<Long, Long>> {
        val grouped = events.filter { it.sampledThreadOrNull != null }.groupBy { it.sampledThread.id }
        return Triple(
            events.filter { isExecutionSample(it) },
            grouped.mapValues { it.value.minOf { v -> v.startTime.toMicros() } },
            grouped.mapValues { it.value.maxOf { v -> v.endTime.toMicros() } },
        )
    }

    /** timing in micro seconds */
    internal data class RecordedEventWithTiming(
        val event: RecordedEvent,
        val startTime: Long,
        val endTime: Long,
    )

    internal fun List<RecordedEvent>.withTiming(defaultTiming: Long): List<RecordedEventWithTiming> {
        if (isEmpty()) {
            return emptyList()
        }
        if (size == 1) {
            return listOf(
                RecordedEventWithTiming(
                    this[0],
                    this[0].startTime.toMicros(),
                    this[0].startTime.toMicros() + defaultTiming,
                ),
            )
        }
        return map { RecordedEventWithTiming(it, it.startTime.toMicros(), it.endTime.toMicros() + defaultTiming) }
    }

    @Suppress("unused")
    internal class HashedFrame(val frame: RecordedFrame) {
        override fun equals(other: Any?): Boolean {
            return super.equals(other) || other is HashedFrame &&
                frame.type == other.frame.type &&
                frame.method.name == other.frame.method.name && frame.method.descriptor == other.frame.method.descriptor
        }

        override fun hashCode(): Int {
            return Objects.hash(frame.type, frame.method.name, frame.method.descriptor)
        }
    }

    internal class HashedMethod(val method: RecordedMethod) {
        override fun equals(other: Any?): Boolean {
            return super.equals(other) || other is HashedMethod &&
                method.name == other.method.name &&
                method.descriptor == other.method.descriptor && method.type.name == other.method.type.name
        }

        override fun hashCode(): Int {
            return Objects.hash(method.name, method.descriptor, method.type.name)
        }
    }

    abstract fun generate(): String

    fun isExecutionSample(event: RecordedEvent) = config.executionSampleType.matches(event.eventType.name)

    companion object {

        fun shortMethodString(method: RecordedMethod): String {
            val nameParts = method.type.name.split(".")
            return (
                nameParts.subList(0, nameParts.size - 1)
                    .map { it.substring(0, 1) } + nameParts[nameParts.size - 1] + method.name
            ).joinToString(".")
        }

        val RecordedClass.pkg
            get() =
                name.split(".").let {
                    it.subList(0, it.size - 1).joinToString(".")
                }

        @Suppress("unused")
        val RecordedClass.className
            get() = name.split(".").last()
    }
}
