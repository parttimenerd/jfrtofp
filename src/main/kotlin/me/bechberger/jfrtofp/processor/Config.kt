package me.bechberger.jfrtofp.processor

import java.nio.file.Path
import jdk.jfr.EventType
import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedObject
import me.bechberger.jfrtofp.Main
import me.bechberger.jfrtofp.types.Milliseconds
import me.bechberger.jfrtofp.types.SampleLikeMarkerConfig
import me.bechberger.jfrtofp.util.toMillis
import org.jline.reader.impl.DefaultParser
import picocli.CommandLine

/** different types of memory properties that can be shown in the track time line view, currently all have to be part of the GCHeapSummary event */
enum class MemoryProperty(val propName: String, val description: String = propName, val actualProperty: String) {
    RESERVED_HEAP("Reserved heap", actualProperty = "reservedSize") {
        override fun isUsable(event: RecordedEvent): Boolean {
            return event.eventType.name == "jdk.GCHeapSummary"
        }

        override fun getValue(event: RecordedEvent): Long {
            return event.getValue<RecordedObject?>("heapSpace").getLong("reservedSize")
        }
    },
    COMMITTED_HEAP("Committed heap", actualProperty = "committedSize") {
        override fun isUsable(event: RecordedEvent): Boolean {
            return event.eventType.name == "jdk.GCHeapSummary"
        }

        override fun getValue(event: RecordedEvent): Long {
            return event.getValue<RecordedObject?>("heapSpace").getLong("committedSize")
        }
    },
    USED_HEAP("Used heap", actualProperty = "heapUsed") {
        override fun isUsable(event: RecordedEvent): Boolean {
            return event.eventType.name == "jdk.GCHeapSummary"
        }

        override fun getValue(event: RecordedEvent): Long {
            return event.getLong("heapUsed")
        }
    };

    abstract fun isUsable(event: RecordedEvent): Boolean
    abstract fun getValue(event: RecordedEvent): Long

    /** returns [(time in millis, memory in bytes)] */
    fun getValues(events: List<RecordedEvent>): List<Pair<Milliseconds, Long>> {
        return events.filter { isUsable(it) }.map {
            it.startTime.toMillis() to getValue(it)
        }
    }
}

@CommandLine.Command
class ConfigMixin {
    @CommandLine.Option(names = ["-n", "--non-project"], description = ["non project package prefixes"])
    var nonProjectPackagePrefixes: List<String> = listOf("java.", "javax.", "kotlin.", "jdk.", "com.google.", "org.apache.", "org.spring.")

    @CommandLine.Option(names = ["--max-exec-samples"], description = ["Maximum number of exec samples per thread"])
    var maxExecutionSamplesPerThread: Int = -1

    @CommandLine.Option(names = ["--max-misc-samples"], description = ["Maximum number of misc samples per thread"])
    var maxMiscSamplesPerThread: Int = -1

    @CommandLine.Option(names = ["--source-url"], description = ["Source url to use in the profile for Firefox Profiler"])
    var sourceUrl: String? = null

    fun toConfig() = Config(
        nonProjectPackagePrefixes = nonProjectPackagePrefixes,
        maxExecutionSamplesPerThread = maxExecutionSamplesPerThread,
        maxMiscSamplesPerThread = maxMiscSamplesPerThread,
        sourceUrl = sourceUrl
    )

    companion object {
        fun parseConfig(args: Array<String>): Config {
            val main = Main()
            CommandLine(main).parseArgs(*args)
            return main.config.toConfig()
        }

        fun parseConfig(args: String): Config = parseConfig(DefaultParser().parse(args, 0).words().toTypedArray())
    }
}

data class Config(
    val addedMemoryProperties: List<MemoryProperty> = DEFAULT_ADDED_MEMORY_PROPERTIES,
    /** time range of a given sample is at max 2.0 * interval */
    val maxIntervalFactor: Double = 2.0,
    val useNonProjectCategory: Boolean = true,
    val nonProjectPackagePrefixes: List<String> = DEFAULT_NON_PROJECT_PACKAGE_PREFIXES,
    val isNonProjectType: (RecordedClass) -> Boolean = { k ->
        nonProjectPackagePrefixes.any { k.name.startsWith(it) }
    },
    val enableMarkers: Boolean = true,
    /** an objectsample weigth will be associated with the nearest stack trace
     * or the common prefix stack trace of the two nearest if the minimal time distance is > 0.25 * interval */
    val enableAllocations: Boolean = true,

    /** maximum number of stack frames */
    val maxThreads: Int = Int.MAX_VALUE,
    val omitEventThreadProperty: Boolean = true,
    val maxExecutionSamplesPerThread: Int = -1,
    val maxMiscSamplesPerThread: Int = -1,
    val initialVisibleThreads: Int = DEFAULT_INITIAL_VISIBLE_THREADS,
    val selectProcessTrackInitially: Boolean = true,
    val initialSelectedThreads: Int = DEFAULT_INITIAL_SELECTED_THREADS,
    val sourcePath: Path? = null,
    var sourceUrl: String? = null,
    val maxUsedThreads: Int = Runtime.getRuntime().availableProcessors(),
    /** they don't contain that much information, but might appear really often */
    val includeGCThreads: Boolean = false,
    val includeInitialSystemProperty: Boolean = false,
    val includeInitialEnvironmentVariables: Boolean = false,
    val includeSystemProcesses: Boolean = false,
    val sampleMarkerConfigForType: (EventType) -> List<SampleLikeMarkerConfig> = { emptyList() },
    val useFileFinder: Boolean = false,
    val ignoredEvents: Set<String> = DEFAULT_IGNORED_EVENTS.toSet(),
    /** minimum number of samples or markers a event has to have */
    val minRequiredItemsPerThread: Int = DEFAULT_MIN_ITEMS_PER_THREAD,
) {
    companion object {
        val DEFAULT_ADDED_MEMORY_PROPERTIES = listOf(MemoryProperty.USED_HEAP, MemoryProperty.COMMITTED_HEAP)
        const val DEFAULT_INITIAL_VISIBLE_THREADS = 10
        const val DEFAULT_INITIAL_SELECTED_THREADS = 10
        val DEFAULT_NON_PROJECT_PACKAGE_PREFIXES =
            listOf(
                "java.", "javax.", "kotlin.", "jdk.",
                "com.google.", "org.apache.", "org.spring.",
                "sun.", "scala."
            )
        val DEFAULT_IGNORED_EVENTS = listOf(
            "jdk.ActiveSetting",
            "jdk.ActiveRecording",
            "jdk.BooleanFlag",
            "jdk.IntFlag",
            "jdk.DoubleFlag",
            "jdk.LongFlag",
            "jdk.NativeLibrary",
            "jdk.StringFlag",
            "jdk.UnsignedIntFlag",
            "jdk.UnsignedLongFlag",
            "jdk.InitialSystemProperty",
            "jdk.InitialEnvironmentVariable",
            "jdk.SystemProcess",
            "jdk.ModuleExport",
            "jdk.ModuleRequire"
        )
        const val DEFAULT_MIN_ITEMS_PER_THREAD = 3
    }
}
