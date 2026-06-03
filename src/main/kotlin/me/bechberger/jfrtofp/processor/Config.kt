package me.bechberger.jfrtofp.processor

import jdk.jfr.EventType
import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedObject
import me.bechberger.jfrtofp.types.Milliseconds
import me.bechberger.jfrtofp.types.SampleLikeMarkerConfig
import me.bechberger.jfrtofp.util.toMillis
import org.jline.reader.impl.DefaultParser
import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

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
    }, ;

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

    @CommandLine.Option(names = ["--execution-sample-type"], description = ["Glob pattern (* and | supported) that matches the used execution sample type"])
    var executionSampleType: String = "jdk.ExecutionSample|jdk.NativeMethodSample|jdk.CPUTimeSample"

    @CommandLine.Option(names = ["--include-noisy-events"], description = ["Include high-volume GC/metaspace detail events that are filtered by default"])
    var includeNoisyEvents: Boolean = false

    @CommandLine.Option(names = ["--exclude-event"], description = ["Exclude a specific event type (repeatable)"])
    var extraIgnoredEvents: List<String> = emptyList()

    @CommandLine.Option(names = ["--spill-dir"], description = ["Directory for temporary spill files during conversion (default: OS temp dir)"])
    var spillDir: String? = null

    fun toConfig() =
        Config(
            nonProjectPackagePrefixes = nonProjectPackagePrefixes,
            maxExecutionSamplesPerThread = maxExecutionSamplesPerThread,
            maxMiscSamplesPerThread = maxMiscSamplesPerThread,
            sourceUrl = sourceUrl,
            executionSampleType = executionSampleType.replace(".", "\\.").replace("*", ".*").toRegex(),
            includeNoisyEvents = includeNoisyEvents,
            extraIgnoredEvents = extraIgnoredEvents.toSet(),
            spillDir = spillDir?.let { java.nio.file.Path.of(it) },
        )

    @CommandLine.Command(
        name = "jfrtofp",
        mixinStandardHelpOptions = true,
        description = ["Converting JFR files to Firefox Profiler profiles"],
    )
    class Main : Callable<Int> {
        @CommandLine.Mixin
        var config: ConfigMixin = ConfigMixin()

        override fun call(): Int {
            return 0
        }
    }

    companion object {
        fun parseConfig(args: Array<String>): Config {
            if (args.isEmpty()) {
                return Config()
            }
            val main = Main()
            CommandLine(main).parseArgs(*args)
            return main.config.toConfig()
        }

        fun parseConfig(args: String): Config = parseConfig(DefaultParser().parse(args, 0).words().filter { it.isNotBlank() }.toTypedArray())
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
    val executionSampleType: Regex = "jdk.ExecutionSample|jdk.NativeMethodSample|jdk.CPUTimeSample".toRegex(),
    /** emit the eventDelay array (always zeros for JFR; omitting saves ~36 KB per profile) */
    val emitEventDelay: Boolean = false,
    /**
     * Strip redundant keys from marker data payloads:
     * - data["type"]: duplicates the table-level name column
     * - data["startTime"]: duplicates the table-level startTime column
     * - cause["time"] ISO-8601 strings in STACKTRACE payloads: duplicates marker startTime
     * Saves ~1.6 MB per profile uncompressed. Default on.
     */
    val minimalMarkerPayload: Boolean = true,
    /** Drop JFR sentinel Long values (Long.MIN_VALUE / Long.MAX_VALUE) that signal "no value" (e.g. ThreadPark timeout = no timeout). Default on. */
    val dropSentinelValues: Boolean = true,
    /** Decimal places for timestamp and duration arrays. 4 = 0.1µs resolution, saving ~3 chars/value vs full Double. -1 = disable. */
    val timestampDecimals: Int = 4,
    /** When true, suppress DEFAULT_NOISY_EVENTS from the output (large GC/metaspace detail events with high per-event byte cost). Default on. */
    val includeNoisyEvents: Boolean = false,
    /** Additional event names to exclude from the output, beyond ignoredEvents and (when !includeNoisyEvents) DEFAULT_NOISY_EVENTS. */
    val extraIgnoredEvents: Set<String> = emptySet(),
    /** Directory for spill files; null = OS temp dir (java.io.tmpdir). */
    val spillDir: Path? = null,
) {
    // Per-type name caches to avoid Regex.matches() and set lookups on every event
    private val executionSampleCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val ignoredEventCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isExecutionSample(event: RecordedEvent) = isExecutionSample(event.eventType.name)
    fun isExecutionSample(eventType: String): Boolean =
        executionSampleCache.getOrPut(eventType) { executionSampleType.matches(eventType) }

    fun isIgnoredEvent(eventName: String): Boolean =
        ignoredEventCache.getOrPut(eventName) {
            eventName in ignoredEvents ||
                (!includeNoisyEvents && eventName in DEFAULT_NOISY_EVENTS) ||
                eventName in extraIgnoredEvents
        }

    companion object {
        val DEFAULT_ADDED_MEMORY_PROPERTIES = listOf(MemoryProperty.USED_HEAP, MemoryProperty.COMMITTED_HEAP)
        const val DEFAULT_INITIAL_VISIBLE_THREADS = 10
        const val DEFAULT_INITIAL_SELECTED_THREADS = 10
        val DEFAULT_NON_PROJECT_PACKAGE_PREFIXES =
            listOf(
                "java.", "javax.", "kotlin.", "jdk.",
                "com.google.", "org.apache.", "org.spring.",
                "sun.", "scala.",
            )
        val DEFAULT_IGNORED_EVENTS =
            listOf(
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
                "jdk.ModuleRequire",
            )
        /** High-volume GC/metaspace detail events that inflate JSON size with low visualization value. Off by default; opt in with --include-noisy-events. */
        val DEFAULT_NOISY_EVENTS =
            setOf(
                "jdk.ThreadDump",
                "jdk.MetaspaceChunkFreeListSummary",
                "jdk.MetaspaceSummary",
                "jdk.MetaspaceGCThreshold",
                "jdk.GCPhasePauseLevel1",
                "jdk.GCPhasePauseLevel2",
                "jdk.GCPhasePauseLevel3",
                "jdk.GCPhasePauseLevel4",
                "jdk.GCPhaseConcurrent",
                "jdk.GCPhaseConcurrentLevel1",
                "jdk.GCPhaseParallel",
                "jdk.G1AdaptiveIHOP",
                "jdk.G1BasicIHOP",
                "jdk.G1MMU",
                "jdk.G1HeapSummary",
                "jdk.GCHeapSummary",
                "jdk.G1EvacuationOldStatistics",
                "jdk.G1EvacuationYoungStatistics",
                "jdk.GCReferenceStatistics",
                "jdk.TenuringDistribution",
                "jdk.EvacuationInformation",
                "jdk.PromoteObjectInNewPLAB",
                "jdk.PromoteObjectOutsidePLAB",
                "jdk.GCCPUTime",
                // Legacy TLAB allocation event — superseded by jdk.ObjectAllocationSample in JDK 16+.
                // When both fire (e.g. with -XX:+AlwaysPreTouch + sample profiling) they capture the
                // same allocations; the legacy event is millions of rows. ObjectAllocationSample stays on.
                "jdk.ObjectAllocationInNewTLAB",
                // High-volume G1GC region tracking events (millions per recording on G1)
                "jdk.G1HeapRegionTypeChange",
                "jdk.G1HeapRegionInformation",
                // Other high-volume detail events
                "jdk.ObjectCountAfterGC",
            )
        const val DEFAULT_MIN_ITEMS_PER_THREAD = 3
    }
}
