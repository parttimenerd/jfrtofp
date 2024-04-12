package me.bechberger.jfrtofp.other

import jdk.jfr.consumer.RecordedMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import me.bechberger.jfrtofp.util.estimateMinInterval
import me.bechberger.jfrtofp.util.realJavaName
import java.nio.file.Path
import me.bechberger.jfrtofp.processor.Config
import kotlin.math.max
import kotlin.math.roundToLong

internal object Speedscope {
    @Serializable
    internal data class Frame(
        val name: String,
        val file: String?,
        val line: Int?,
        val col: Int? = null,
    )

    @Serializable
    internal data class Shared(val frames: MutableList<Frame> = mutableListOf())

    @Serializable
    internal sealed class IProfile

    @Serializable
    internal data class Event(
        /** C or O */
        val type: String,
        val at: Long,
        val frame: Int,
    )

    @Serializable
    @SerialName("evented")
    internal data class EventedProfile(
        val name: String,
        val startValue: Long,
        val endValue: Long,
        val events: List<Event>,
        val unit: String = "microseconds",
    ) : IProfile()

    @Serializable
    internal data class File(
        val version: String = "0.6.0",
        @Suppress("ConstructorParameterNaming")
        val `$schema`: String = "https://www.speedscope.app/file-format-schema.json",
        val shared: Shared = Shared(),
        val profiles: MutableList<IProfile> = mutableListOf(),
        val name: String? = "Program",
        val activeProfileIndex: Int? = null,
        val exporter: String? = "jfrplugin",
    )

    internal data class Stack(val callStack: List<BaseGenerator.HashedMethod> = listOf()) {
        enum class Decision(val short: String) {
            OPEN("O"),
            ClOSE("C"),
        }

        fun computeDecisions(other: Stack): List<Pair<BaseGenerator.HashedMethod, Decision>> {
            this.callStack.forEachIndexed { index, method ->
                if (other.callStack.size > index) {
                    if (method != other.callStack[index]) {
                        return this.callStack.subList(index, this.callStack.size).map { it to Decision.ClOSE }
                            .asReversed() +
                            other.callStack.subList(index, other.callStack.size)
                                .map { it to Decision.OPEN }
                    }
                } else {
                    return this.callStack.subList(index, this.callStack.size).map { it to Decision.ClOSE }
                        .asReversed()
                }
            }
            if (other.callStack.size > this.callStack.size) {
                return other.callStack.subList(this.callStack.size, other.callStack.size)
                    .map { it to Decision.OPEN }
            }
            return mutableListOf()
        }
    }
}

class SpeedscopeGenerator(jfrFile: Path, config: Config) : BaseGenerator(jfrFile, config) {
    /**
     * Returns the traces in a format suitable for speedscope
     * (https://github.com/jlfwong/speedscope/blob/main/src/lib/file-format-spec.ts)
     *
     * Some documentation borrowed from this specification
     */
    override fun generate(): String {
        val (samples, starts, _) = executionSamplesWithStartAndEnd()
        val ovStart = starts.values.min()
        val perThread = samples.perThread()
        val estimatedIntervalInMicros = (perThread.estimateMinInterval() * 1000).roundToLong()

        val frames = mutableListOf<Speedscope.Frame>()
        val framesToIndex = mutableMapOf<HashedMethod, Int>()

        fun getFrame(
            method: RecordedMethod,
            lineNumber: Int?,
        ): Int {
            return framesToIndex.computeIfAbsent(HashedMethod(method)) {
                val fframe = Speedscope.Frame(shortMethodString(method), method.type.pkg, lineNumber)
                frames.add(fframe)
                return@computeIfAbsent frames.size - 1
            }
        }

        val profiles = mutableListOf<Speedscope.IProfile>()
        for ((thread, threadSamples) in perThread) {
            var currentStack = Speedscope.Stack()
            val events = mutableListOf<Speedscope.Event>()
            var lastEndTime = 0L

            fun addEvents(
                actions: List<Pair<HashedMethod, Speedscope.Stack.Decision>>,
                time: Long,
            ) {
                for (action in actions) {
                    events.add(
                        Speedscope.Event(
                            action.second.short,
                            max(0, time),
                            getFrame(action.first.method, null),
                        ),
                    )
                }
            }

            for (
            (sample, startTime, _) in threadSamples.withTiming(estimatedIntervalInMicros).sortedBy { it.startTime }
            ) {
                val newStack = Speedscope.Stack(sample.stackTrace.frames.map { HashedMethod(it.method) }.asReversed())
                val actions = currentStack.computeDecisions(newStack)
                currentStack = newStack
                addEvents(actions, startTime - ovStart)
                lastEndTime = startTime
            }
            if (currentStack.callStack.isNotEmpty()) {
                addEvents(
                    currentStack.computeDecisions(Speedscope.Stack()),
                    lastEndTime - ovStart + estimatedIntervalInMicros,
                )
            }
            profiles.add(
                Speedscope.EventedProfile(
                    name = "Thread ${thread.realJavaName!!}",
                    startValue = 0,
                    endValue = lastEndTime - ovStart + estimatedIntervalInMicros,
                    unit = "microseconds",
                    events = events,
                ),
            )
        }

        return jsonFormat.encodeToString(
            Speedscope.File(
                shared = Speedscope.Shared(frames),
                profiles = profiles,
                activeProfileIndex =
                    profiles.find { it is Speedscope.EventedProfile && it.name == "Thread main" }
                        ?.let { profiles.indexOf(it) } ?: 0,
            ),
        )
    }
}
