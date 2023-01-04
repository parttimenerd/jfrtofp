package me.bechberger.jfrtofp.processor

import MarkerSchemaProcessor
import jdk.jfr.EventType
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedThread
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.bechberger.jfrtofp.FileFinder
import me.bechberger.jfrtofp.types.BasicMarkerFormatType
import me.bechberger.jfrtofp.types.Counter
import me.bechberger.jfrtofp.types.CounterSamplesTable
import me.bechberger.jfrtofp.types.ExtraProfileInfoEntry
import me.bechberger.jfrtofp.types.Milliseconds
import me.bechberger.jfrtofp.types.NativeSymbolTable
import me.bechberger.jfrtofp.types.Profile
import me.bechberger.jfrtofp.types.ProfileMeta
import me.bechberger.jfrtofp.types.SampleGroup
import me.bechberger.jfrtofp.types.SampleLikeMarkerConfig
import me.bechberger.jfrtofp.types.SampleUnits
import me.bechberger.jfrtofp.types.TableColumnFormat
import me.bechberger.jfrtofp.types.TableMarkerFormat
import me.bechberger.jfrtofp.types.ThreadCPUDeltaUnit
import me.bechberger.jfrtofp.types.ThreadIndex
import me.bechberger.jfrtofp.types.WeightType
import me.bechberger.jfrtofp.util.Percentage
import me.bechberger.jfrtofp.util.encodeToJSONStream
import me.bechberger.jfrtofp.util.encodeToZippedStream
import me.bechberger.jfrtofp.util.estimateIntervalInMicros
import me.bechberger.jfrtofp.util.isExecutionSample
import me.bechberger.jfrtofp.util.isGCThread
import me.bechberger.jfrtofp.util.isSystemThread
import me.bechberger.jfrtofp.util.realThread
import me.bechberger.jfrtofp.util.sampledThread
import me.bechberger.jfrtofp.util.toMicros
import me.bechberger.jfrtofp.util.toMillis
import me.bechberger.jfrtofp.util.toNanos
import java.io.OutputStream
import java.nio.file.Path
import java.time.Instant
import java.util.NavigableMap
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.LongStream
import me.bechberger.jfrtofp.types.PauseReason
import me.bechberger.jfrtofp.types.PausedRange
import me.bechberger.jfrtofp.util.estimateIntervalInMillis
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo
import kotlin.math.roundToLong
import kotlin.streams.toList

fun EventType.generateSampleLikeMarkersConfig(config: Config): List<SampleLikeMarkerConfig> {
    val label = label ?: name
    return listOfNotNull(
        when (name) {
            "jdk.AllocationRequiringGC" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "size")
            "jdk.ClassDefine" -> SampleLikeMarkerConfig(name, label, name)
            "jdk.ClassLoad" -> SampleLikeMarkerConfig(name, label, name, WeightType.TRACING, "duration")
            "jdk.Deoptimization" -> SampleLikeMarkerConfig(name, label, name)
            "jdk.FileRead" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "bytesRead")
            "jdk.FileWrite" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "bytesWritten")
            "jdk.JavaErrorThrow" -> SampleLikeMarkerConfig(name, label, name)
            "jdk.JavaExceptionThrow" -> SampleLikeMarkerConfig(name, label, name)
            "jdk.JavaMonitorEnter" -> SampleLikeMarkerConfig(name, label, name)
            "jdk.JavaMonitorWait" -> SampleLikeMarkerConfig(name, label, name, WeightType.TRACING, "timeout")
            "jdk.ObjectAllocationSample" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "weight")
            "jdk.ObjectAllocationInNewTLAB" -> SampleLikeMarkerConfig(
                name,
                label,
                name,
                WeightType.BYTES,
                "allocationSize"
            )

            "jdk.ObjectAllocationOutsideTLAB" -> SampleLikeMarkerConfig(
                name,
                label,
                name,
                WeightType.BYTES,
                "allocationSize"
            )

            "jdk.ProcessStart" -> SampleLikeMarkerConfig(name, label, name)
            "jdk.SocketRead" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "bytesRead")
            "jdk.SocketWrite" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "bytesWritten")
            "jdk.SystemGC" -> SampleLikeMarkerConfig(name, label, name)
            "jdk.ThreadPark" -> SampleLikeMarkerConfig(name, label, name, WeightType.TRACING, "duration")
            "jdk.ThreadSleep" -> SampleLikeMarkerConfig(name, label, name, WeightType.TRACING, "duration")
            "jdk.ThreadStart" -> SampleLikeMarkerConfig(name, label, name)
            else -> null
        }
    ) + listOfNotNull(
        when (name) {
            "jdk.ObjectAllocationSample" -> SampleLikeMarkerConfig(
                name,
                "$label Classes",
                "${name}_class",
                WeightType.BYTES,
                "weight",
                "_class"
            )

            else -> null
        }
    ) + config.sampleMarkerConfigForType(this)
}

/** assumes that the events come in sorted order */
class ThreadProcessor(
    val config: Config,
    val isParentProcessThread: Boolean,
    val threadId: Long,
    val basicInformation: BasicInformation,
    val markerSchema: MarkerSchemaProcessor
) {

    private var start: Instant = Instant.MIN

    private var end: Instant = Instant.MAX

    private val cpuLoads: NavigableMap<Long, Percentage> = TreeMap()

    private val eventTypes: MutableSet<EventType> = mutableSetOf()

    private val tables: Tables = Tables(config, basicInformation, markerSchema, basicInformation::classToUrl)

    private val samplesTable: SamplesTableWrapper = SamplesTableWrapper(tables)

    private var threadStartEvent: RecordedEvent? = null
    private var threadEndEvent: RecordedEvent? = null
    private var thread: RecordedThread? = null
    private var pausedRanges: MutableList<PausedRange> = mutableListOf()

    private fun processExecutionSample(event: RecordedEvent) {
        samplesTable.processEvent(event)
    }

    private fun processThreadCPULoad(event: RecordedEvent) {
        val user = event.getFloat("user")
        val system = event.getFloat("system")
        cpuLoads[event.startTime.toMicros()] = (user + system) * basicInformation.hwThreads
    }

    private fun generateSampleLikeMarkersConfig() = eventTypes.distinctBy { it.name }.flatMap {
        it.generateSampleLikeMarkersConfig(
            markerSchema.config
        )
    }

    /** approximates the cpu load at a given time for this thread */
    internal fun getCpuLoad(time: Milliseconds): Float {
        if (cpuLoads.isEmpty()) {
            return 1.0f
        }
        val micros: Long = (time * 1000L).toLong()
        val floor = cpuLoads.floorEntry(micros)
        var ceil = cpuLoads.ceilingEntry(micros)
        if (floor == null) {
            return ceil!!.value
        }
        if (ceil == null) {
            return floor.value
        }
        if (micros - floor.value < ceil.value - micros) {
            return floor.value
        }
        return ceil.value
    }

    fun processEvent(event: RecordedEvent) {
        if (start == Instant.MIN) {
            start = if (isParentProcessThread) basicInformation.startTime else event.startTime
        }
        end = event.endTime
        eventTypes.add(event.eventType)
        if (thread == null && event.thread != null) {
            thread = event.thread
        }
        if (event.isExecutionSample) {
            processExecutionSample(event)
        } else {
            if (config.enableMarkers) {
                tables.rawMarkerTable.processEvent(event)
            }
            when (event.eventType.name) {
                "jdk.ThreadCPULoad" -> processThreadCPULoad(event)
                "jdk.ThreadStart" -> threadStartEvent = event
                "jdk.ThreadEnd" -> threadEndEvent = event
                "jdk.ThreadPark" -> pausedRanges.add(PausedRange(event.startTime.toMillis(), event.endTime.toMillis(), PauseReason.PARKED))
            }
        }
    }

    fun toThread(): me.bechberger.jfrtofp.types.Thread {
        return me.bechberger.jfrtofp.types.Thread(
            processType = if (isParentProcessThread) "tab" else "default",
            processStartupTime = start.toMillis(),
            processShutdownTime = end.toMillis(),
            registerTime = threadStartEvent?.startTime?.toMillis() ?: start.toMillis(),
            unregisterTime = threadEndEvent?.startTime?.toMillis() ?: end.toMillis(),
            pausedRanges = pausedRanges.sortedBy { it.startTime!! },
            // the global process track has to have type "tab" and name "GeckoMain"
            name = if (isParentProcessThread) "GeckoMain" else thread?.let { it.javaName ?: it.osName } ?: "<unknown>",
            processName = "Parent Process",
            pid = basicInformation.pid,
            tid = if (isParentProcessThread) 0 else threadId,
            samples = samplesTable.toSamplesTable(this::getCpuLoad),
            jsAllocations = null,
            nativeAllocations = null,
            markers = tables.rawMarkerTable.toRawMarkerTable(),
            stackTable = tables.stackTraceTable.toStackTable(),
            frameTable = tables.frameTable.toFrameTable(),
            gTable = listOf(),
            funcTable = tables.funcTable.toFuncTable(),
            stringArray = tables.stringTable.toStringTable(),
            resourceTable = tables.resourceTable.toResourceTable(),
            nativeSymbols = NativeSymbolTable(listOf(), listOf(), listOf(), listOf()),
            sampleLikeMarkersConfig = generateSampleLikeMarkersConfig()
        )
    }

    fun store(stream: OutputStream) {
    }
}

/** Event accepting worker */
abstract class WorkerThread(val processor: ThreadProcessor) : Thread() {
    val shouldStop = AtomicBoolean(false)
    private val queue = ConcurrentLinkedQueue<RecordedEvent>()

    open abstract fun acceptable(event: RecordedEvent): Boolean

    fun acceptEvent(event: RecordedEvent) {
        queue.add(event)
    }

    fun signalStop() {
        shouldStop.set(true)
    }

    override fun run() {
        while (!shouldStop.get()) {
            val event = queue.poll()
            if (event != null) {
                processEvent(event)
            } else {
                yield()
            }
        }
    }

    open abstract fun processEvent(event: RecordedEvent)
}

class Executor(private val path: Path, private val workerThreads: List<WorkerThread>) {

    fun process() {
        RecordingFile(path).use { file ->
            while (file.hasMoreEvents()) {
                val event = file.readEvent()
                workerThreads.filter { it.acceptable(event) }.forEach { it.acceptEvent(event) }
            }
            workerThreads.forEach { it.signalStop() }
            workerThreads.forEach { it.join() }
        }
    }
}

/** Basic information that can be obtained fast from a JFR file */
data class BasicInformation(
    val config: Config,
    /** thread.id */
    val mainThreadId: Long,
    val startTime: Instant,
    val interval: Instant,
    /** [JVMInformation](https://sap.github.io/SapMachine/jfrevents/#jvminformation) */
    val jvmInformation: RecordedEvent?,
    val cpuInformation: RecordedEvent?,
    val osInformation: RecordedEvent?,
    val initialSystemProperties: Map<String, String>,
    val initialEnvironmentVariables: Map<String, String>,
    val systemProcesses: List<RecordedEvent>
) {
    val startTimeMillis = startTime.toMillis()
    val intervalMillis = interval.toEpochMilli()
    val intervalNanos = interval.toNanos()
    val pid = jvmInformation?.getLong("pid") ?: -1
    val fileFinder = if (config.useFileFinder) {
        FileFinder().also { finder ->
            config.sourcePath?.let { sourcePath -> finder.addFolder(sourcePath) }
        }
    } else {
        null
    }

    val oscpu: String?
        get() = osInformation?.getString("osVersion")?.let {
            val os = Regex("[A-Za-z0-9]+ [0-9.]+").find(osInformation.getString("osVersion"))?.groups?.first()?.value
            val cpu = cpuInformation?.getString("cpu")?.split(" ")
                ?.getOrNull(0)
            return listOfNotNull(os, cpu).joinToString(" ")
        }

    val platform
        get() = osInformation?.getString("osVersion")?.let {
            if ("Android" in it) {
                "Android"
            } else if ("Mac OS X" in it) {
                "Macintosh"
            } else if ("Windows" in it) {
                "Windows"
            } else {
                "X11"
            }
        }

    val hwThreads = cpuInformation?.getInt("hwThreads") ?: 1

    fun classToUrl(packageName: String, className: String) = fileFinder?.findFile(packageName, className)?.let { file ->
        config.sourceUrl?.let {
            config.sourcePath!!.let { sourcePath ->
                val relativePath = file.relativeTo(sourcePath)
                config.sourceUrl + "/" + relativePath
            }
        }
    }

    companion object {
        /** read the first few events of the file to get the basic information */
        fun obtain(
            jfrFile: Path,
            config: Config,
            maxEventsConsidered: Int = 100000,
            maxRecordedEventsConsideredForIntervalEstimation: Int = 1000
        ): BasicInformation {
            // assumption: system properties, ... come before the first ExecutionSample event
            var mainThreadId: Long? = null
            var startTime: Instant? = null
            var backupMainThreadId: Long? = null
            var backupStartTime: Instant? = null
            var eventCount = 0
            var jvmInformation: RecordedEvent? = null
            var cpuInformation: RecordedEvent? = null
            var osInformation: RecordedEvent? = null
            val sampledStartTimesPerThread: MutableMap<Long, MutableList<Milliseconds>> = mutableMapOf()
            var sampledStartTimesCount = 0
            val initialSystemProperties: MutableMap<String, String> = mutableMapOf()
            val initialEnvironmentVariables: MutableMap<String, String> = mutableMapOf()
            val systemProcesses: MutableList<RecordedEvent> = mutableListOf()
            RecordingFile(jfrFile).use { file ->
                while (file.hasMoreEvents() && (
                    mainThreadId == null || cpuInformation == null || jvmInformation == null || osInformation == null ||
                        sampledStartTimesCount < maxRecordedEventsConsideredForIntervalEstimation
                    )
                ) {
                    val event = file.readEvent()
                    if (event.thread != null && event.thread.javaName == "main") {
                        mainThreadId = event.thread.id
                    }
                    if (jvmInformation == null && event.eventType.name == "jdk.JVMInformation") {
                        startTime = event.getInstant("jvmStartTime")
                        jvmInformation = event
                    } else if (cpuInformation == null && event.eventType.name == "jdk.CPUInformation") {
                        cpuInformation = event
                    } else if (osInformation == null && event.eventType.name == "jdk.OSInformation") {
                        osInformation = event
                    } else if (event.isExecutionSample) {
                        if (backupMainThreadId == null) {
                            backupMainThreadId = event.sampledThread.id
                            backupStartTime = event.startTime
                        }
                        val sampleStartTimes = sampledStartTimesPerThread.getOrPut(event.sampledThread.id) { mutableListOf() }
                        if (sampledStartTimesCount < maxRecordedEventsConsideredForIntervalEstimation) {
                            sampleStartTimes.add(event.startTime.toMillis())
                            sampledStartTimesCount++
                        } else if (eventCount > maxEventsConsidered) { // we break only if we have enough events
                            // so we don't miss the main thread or the JVMInformation event
                            break
                        }
                    } else if ((config.includeInitialSystemProperty && event.eventType.name == "jdk.InitialSystemProperty") || (config.includeInitialEnvironmentVariables && event.eventType.name == "jdk.InitialEnvironmentVariable")) {
                        initialSystemProperties[event.getString("key")] = event.getString("value")
                    } else if (config.includeSystemProcesses && event.eventType.name == "jdk.SystemProcess") {
                        systemProcesses.add(event)
                    }
                    eventCount++
                }
            }
            if (mainThreadId == null) {
                mainThreadId = backupMainThreadId
            }
            if (startTime == null) {
                startTime = backupStartTime
            }
            if (mainThreadId == null || startTime == null) {
                error("Could not find main thread or start time")
            }
            val estimatedIntervalInMillis = estimateIntervalInMillis(sampledStartTimesPerThread)
            val estimatedInterval = Instant.ofEpochSecond(
                (estimatedIntervalInMillis / 1_000).toLong(),
                ((estimatedIntervalInMillis % 1_000) * 1_000_000).toLong()
            )
            return BasicInformation(
                config,
                mainThreadId!!,
                startTime!!,
                estimatedInterval,
                jvmInformation,
                cpuInformation,
                osInformation,
                initialSystemProperties,
                initialEnvironmentVariables,
                systemProcesses
            )
        }
    }
}

internal sealed class AbstractThreadInfo(val startTime: Instant)

internal class BasicThreadInfo(
    startTime: Instant,
    val recordedThread: RecordedThread,
    val isMainThread: Boolean,
    internal var executionSampleCount: Int = 0,
    internal val otherSampleCount: Int = 0
) : AbstractThreadInfo(startTime), Comparable<BasicThreadInfo> {
    val id = recordedThread.id
    val name = recordedThread.javaName
    val isSystemThread = recordedThread.isSystemThread()
    val isGCThread = recordedThread.isGCThread()

    val hasExecutionSamples
        get() = executionSampleCount > 0

    val score: Long
        get() = if (isMainThread) Long.MAX_VALUE else executionSampleCount * 2L + otherSampleCount

    override fun compareTo(other: BasicThreadInfo) = if (score > other.score) -1 else if (score < other.score) 1 else 0
}

internal class ParentThreadInfo(
    startTime: Instant,
) : AbstractThreadInfo(startTime)

data class ProcessCPULoad(
    val time: Instant,
    val jvmUser: Percentage,
    val jvmSystem: Percentage,
    val machineTotal: Percentage
)

internal class ProcessCounterProcessor(
    val basicInformation: BasicInformation,
    val config: Config
) {

    /** collected cpu load information */
    val cpuLoads = mutableListOf<ProcessCPULoad>()
    val memoryProperties = mutableMapOf<MemoryProperty, MutableList<Pair<Milliseconds, Long>>>()

    init {
        config.addedMemoryProperties.forEach { memoryProperties[it] = mutableListOf() }
    }

    fun processEvent(event: RecordedEvent) {
        if (event.eventType.name == "jdk.CPULoad") {
            cpuLoads.add(
                ProcessCPULoad(
                    event.startTime,
                    event.getFloat("jvmUser"),
                    event.getFloat("jvmSystem"),
                    event.getFloat("machineTotal")
                )
            )
        } else {
            for ((memoryProperty, values) in memoryProperties) {
                if (memoryProperty.isUsable(event)) {
                    values.add(
                        event.startTime.toMillis() to memoryProperty.getValue(event)
                    )
                }
            }
        }
    }

    private fun generateCPUCounters(endTime: Instant) = if (cpuLoads.size > 0) {
        listOf( // TODO: does not work
            Counter(
                name = "processCPU",
                category = "CPU",
                description = "Process CPU utilization",
                pid = basicInformation.pid,
                mainThreadIndex = 0,
                sampleGroups = listOf(
                    SampleGroup(
                        0,
                        CounterSamplesTable(
                            time = cpuLoads.map { it.time.toMillis() },
                            count = cpuLoads.map {
                                ((it.jvmUser + it.jvmSystem) * 1_000_000.0)
                                    .roundToLong()
                            }
                        )
                    )
                )
            )
        )
    } else {
        generateGenericCPUCounters(endTime)
    }

    private fun generateMemoryCounters() = memoryProperties.entries.map { (prop, samples) ->
        val sortedSamples = samples.sortedBy { it.first }
        Counter(
            name = prop.propName,
            category = "Memory",
            description = prop.description,
            pid = basicInformation.pid,
            mainThreadIndex = 0,
            sampleGroups = listOf(
                SampleGroup(
                    0,
                    CounterSamplesTable(
                        time = sortedSamples.map { (t, _) -> t },
                        count = sortedSamples.mapIndexed { i, (_, value) ->
                            if (i == 0) {
                                value
                            } else {
                                value - sortedSamples[i - 1].second
                            }
                        }
                    )
                )
            )
        )
    }.filter { it.sampleGroups[0].samples.length > 0 }

    private fun generateGenericCPUCounters(endTime: Instant): List<Counter> {
        val slices =
            LongStream.range(
                (basicInformation.startTimeMillis / 100).roundToLong(),
                (endTime.toMillis() / 100).roundToLong()
            ).mapToDouble {
                it * 100.0
            }.toList()
        return listOf(
            Counter(
                name = "processCPU",
                category = "CPU",
                description = "Process CPU utilization",
                pid = basicInformation.pid,
                mainThreadIndex = 0,
                sampleGroups = listOf(
                    SampleGroup(
                        0,
                        CounterSamplesTable(
                            time = slices,
                            count = List(slices.size) { 10 }
                        )
                    )
                )
            )
        )
    }

    fun generateCounters(endTime: Instant): List<Counter> {
        return /*generateMemoryCounters() + */generateCPUCounters(endTime)
    }
}

/** Processes events to create a [ProfileMeta] object */
internal class MetaProcessor(
    val jfrFile: Path,
    val basicInformation: BasicInformation,
    val markerSchema: MarkerSchemaProcessor,
    val config: Config
) {
    var endTime: Instant = basicInformation.startTime
    val threads: MutableMap<Long, BasicThreadInfo> = mutableMapOf()
    val parentThreadInfo = ParentThreadInfo(basicInformation.startTime)
    val gcThreads = mutableSetOf<Long>()

    fun processEvent(event: RecordedEvent) {
        val thread = event.realThread
        if (thread != null) {
            val threadInfo = threads.getOrPut(thread.id) {
                BasicThreadInfo(
                    event.startTime,
                    thread,
                    thread.id == basicInformation.mainThreadId
                )
            }.also {
                if (it.isGCThread) {
                    gcThreads.add(it.id)
                }
            }
            if (event.isExecutionSample) {
                threadInfo.executionSampleCount++
            }
        }
        val time = if (event.hasField("endTime")) {
            event.endTime
        } else {
            event.startTime
        }
        if (time.isAfter(endTime)) {
            endTime = time
        }
    }

    fun isGCThread(threadId: Long) = gcThreads.contains(threadId)

    private fun environmentVariablesEntry() =
        generateTableEntry(
            basicInformation.initialEnvironmentVariables,
            "Environment Variables"
        )

    private fun initialSystemPropertyEntry() =
        generateTableEntry(
            basicInformation.initialSystemProperties,
            "System Property"
        )

    private fun generateSystemProcessEntry() =
        generateTableEntry(
            basicInformation.systemProcesses,
            "System Process",
            listOf(CC("ProcessId", "pid"), CC("Command Line", "commandLine"))
        )

    private data class CC(
        val name: String,
        val key: String,
        val type: BasicMarkerFormatType = BasicMarkerFormatType.STRING
    )

    private fun generateTableEntry(events: List<RecordedEvent>, label: String, columns: List<CC>): ExtraProfileInfoEntry? {
        if (events.isEmpty()) {
            return null
        }
        val format = TableMarkerFormat(columns.map { column -> TableColumnFormat(column.type, column.name) })
        val value = JsonArray(
            events.map { e ->
                JsonArray(columns.map { c -> JsonPrimitive(e.getString(c.key)) })
            }
        )
        return ExtraProfileInfoEntry(label, format, value)
    }

    private fun generateTableEntry(
        map: Map<String, String>,
        label: String,
        valueType: BasicMarkerFormatType = BasicMarkerFormatType.STRING
    ): ExtraProfileInfoEntry? {
        if (map.isEmpty()) {
            return null
        }
        val format = TableMarkerFormat(
            listOf(TableColumnFormat(BasicMarkerFormatType.STRING, "Name"), TableColumnFormat(valueType, "Value"))
        )
        val value = JsonArray(
            map.entries.sortedBy { it.key }.map { e ->
                JsonArray(listOf(JsonPrimitive(e.key), JsonPrimitive(e.value)))
            }
        )
        return ExtraProfileInfoEntry(label, format, value)
    }

    fun isValidThread(threadInfo: BasicThreadInfo) =
        if (threadInfo.isMainThread) {
            true
        } else if (threadInfo.isGCThread) {
            config.includeGCThreads
        } else {
            if (!threadInfo.isSystemThread) {
                threadInfo.hasExecutionSamples
            } else {
                true
            }
        }

    fun sortedThreads(): List<AbstractThreadInfo> {
        return listOf(parentThreadInfo) + threads.values.filter {
            isValidThread(it)
        }.sorted()
    }

    fun toMeta(): ProfileMeta {
        val threads = sortedThreads()
        val initialVisibleThreadIds = List(
            threads.filterNot { t ->
                t is BasicThreadInfo && t.isSystemThread
            }.size
        ) { index -> index }.take(config.initialVisibleThreads + 1)
        val initialSelectedThreadIds: List<ThreadIndex> =
            (if (config.selectProcessTrackInitially) listOf(0) else listOf()) +
                initialVisibleThreadIds.drop(1).take(config.initialSelectedThreads)
        return ProfileMeta(
            interval = basicInformation.interval.toMillis(),
            startTime = basicInformation.startTimeMillis,
            endTime = endTime.toMillis(),
            categories = CategoryE.toCategoryList(),
            product = basicInformation.jvmInformation?.getString("javaArguments") ?: "JVM Application",
            stackwalk = 0,
            misc = basicInformation.jvmInformation?.let { "JVM Version ${it.getString("jvmVersion")}" },
            oscpu = basicInformation.oscpu,
            cpuName = basicInformation.cpuInformation?.getString("cpu"),
            platform = basicInformation.platform,
            markerSchema = markerSchema.toMarkerSchemaList(),
            arguments = basicInformation.jvmInformation?.let {
                "jvm=${it.getString("jvmArguments")}  --  java=${
                it.getString(
                    "javaArguments"
                )
                }"
            } ?: "<unknown>",
            physicalCPUs = basicInformation.cpuInformation?.getInt("cores"),
            logicalCPUs = basicInformation.cpuInformation?.getInt("hwThreads"),
            sampleUnits = SampleUnits(threadCPUDelta = ThreadCPUDeltaUnit.US),
            importedFrom = jfrFile.toString(),
            extra = listOf(
                /*ExtraProfileInfoSection(
                    "Extra Environment Information",
                    listOfNotNull(
                        initialSystemPropertyEntry(),
                        environmentVariablesEntry(),
                        generateSystemProcessEntry()
                    )
                )*/
            ),
            initialVisibleThreads = initialVisibleThreadIds,
            initialSelectedThreads = initialSelectedThreadIds,
            keepProfileThreadOrder = true
        )
    }
}

class Processor(val config: Config, val jfrFile: Path) {

    val basicInformation = BasicInformation.obtain(jfrFile, config)
    val markerSchema = MarkerSchemaProcessor(config)

    fun process(outputStream: OutputStream) {
        // if (jfrFile.fileSize() <= MAX_JFR_SIZE_FOR_SINGLE_THREAD || config.maxThreads == 1) {
        processSingleThreaded().encodeToJSONStream(outputStream)
        // } else {
        //   processMultiThreaded(outputStream)
        // }
    }

    internal fun processSingleThreaded(): Profile {
        val threadToProcessor = mutableMapOf<Long, ThreadProcessor>()
        val metaProcessor = MetaProcessor(jfrFile, basicInformation, markerSchema, config)
        val processCounterProcessor = ProcessCounterProcessor(basicInformation, config)
        val parentThreadProcessor = ThreadProcessor(config, true, -1, basicInformation, markerSchema)
        RecordingFile(jfrFile).use { file ->
            while (file.hasMoreEvents()) {
                val event = file.readEvent()
                if (event.eventType.name in config.ignoredEvents) {
                    continue
                }
                metaProcessor.processEvent(event)
                processCounterProcessor.processEvent(event)
                val processor: ThreadProcessor
                val realThread = event.realThread
                if (realThread != null) {
                    if (!config.includeGCThreads && metaProcessor.isGCThread(realThread.id)) {
                        continue
                    }
                    processor = threadToProcessor.getOrPut(realThread.id) {
                        ThreadProcessor(
                            config,
                            false,
                            realThread.id,
                            basicInformation,
                            markerSchema
                        )
                    }
                } else {
                    processor = parentThreadProcessor
                }
                processor.processEvent(event)
            }
        }
        return Profile(
            libs = listOf(),
            meta = metaProcessor.toMeta(),
            threads = metaProcessor.sortedThreads().map {
                (
                    when (it) {
                        is ParentThreadInfo -> parentThreadProcessor
                        is BasicThreadInfo -> threadToProcessor[it.id]!!
                    }
                    ).toThread()
            },
            counters = processCounterProcessor.generateCounters(metaProcessor.endTime)
        )
    }

    companion object {
        const val MAX_JFR_SIZE_FOR_SINGLE_THREAD = 5_000_000L
    }
}

fun main() {
    /*val processor = Processor(Config(), Path.of("samples/small_profile.jfr"))
    processor.processSingleThreaded().encodeToZippedStream(Path.of("samples/small_profile.json.gz").outputStream())*/
    val processor = Processor(Config(), Path.of("samples/flight_large.jfr"))
     processor.processSingleThreaded().encodeToZippedStream(Path.of("samples/flight_large.json.gz").outputStream())
}
