package me.bechberger.jfrtofp.processor

import io.jafar.parser.internal_api.metadata.MetadataClass
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import me.bechberger.jfrtofp.FileFinder
import me.bechberger.jfrtofp.types.BasicMarkerFormatType
import me.bechberger.jfrtofp.types.Counter
import me.bechberger.jfrtofp.types.CounterDisplayConfig
import me.bechberger.jfrtofp.types.CounterGraphType
import me.bechberger.jfrtofp.types.CounterSamplesTable
import me.bechberger.jfrtofp.types.ExtraProfileInfoEntry
import me.bechberger.jfrtofp.types.MarkerDisplayLocation
import me.bechberger.jfrtofp.types.Milliseconds
import me.bechberger.jfrtofp.types.NativeSymbolTable
import me.bechberger.jfrtofp.types.PauseReason
import me.bechberger.jfrtofp.types.PausedRange
import me.bechberger.jfrtofp.types.Pid
import me.bechberger.jfrtofp.types.Profile
import me.bechberger.jfrtofp.types.ProfileMeta
import me.bechberger.jfrtofp.types.SampleLikeMarkerConfig
import me.bechberger.jfrtofp.types.SampleUnits
import me.bechberger.jfrtofp.types.SharedData
import me.bechberger.jfrtofp.types.TableColumnFormat
import me.bechberger.jfrtofp.types.TableMarkerFormat
import me.bechberger.jfrtofp.types.ThreadCPUDeltaUnit
import me.bechberger.jfrtofp.types.ThreadIndex
import me.bechberger.jfrtofp.types.Tid
import me.bechberger.jfrtofp.types.WeightType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.bechberger.jfrtofp.util.Percentage
import me.bechberger.jfrtofp.util.estimateIntervalInMillis
import me.bechberger.jfrtofp.util.isGCThread
import me.bechberger.jfrtofp.util.isSystemThread
import me.bechberger.jfrtofp.util.jsonFormat
import me.bechberger.jfrtofp.util.realThread
import me.bechberger.jfrtofp.util.sampledThreadOrNull
import me.bechberger.jfrtofp.util.toMicros
import me.bechberger.jfrtofp.util.toMillis
import me.bechberger.jfrtofp.util.toNanos
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.NavigableMap
import java.util.TreeMap
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo
import kotlin.math.roundToLong
import java.util.zip.GZIPOutputStream
import kotlin.streams.toList
import java.util.stream.LongStream
import me.bechberger.jfrtofp.processor.SampleSpiller
import me.bechberger.jfrtofp.processor.MarkerSpiller
import me.bechberger.jfrtofp.util.BasicJSONGenerator

fun String.generateSampleLikeMarkersConfig(config: Config): List<SampleLikeMarkerConfig> {
    val name = this
    val label = name
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
            "jdk.ObjectAllocationInNewTLAB" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "allocationSize")
            "jdk.ObjectAllocationOutsideTLAB" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "allocationSize")
            "jdk.ProcessStart" -> SampleLikeMarkerConfig(name, label, name)
            "jdk.SocketRead" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "bytesRead")
            "jdk.SocketWrite" -> SampleLikeMarkerConfig(name, label, name, WeightType.BYTES, "bytesWritten")
            "jdk.SystemGC" -> SampleLikeMarkerConfig(name, label, name)
            "jdk.ThreadPark" -> SampleLikeMarkerConfig(name, label, name, WeightType.TRACING, "duration")
            "jdk.ThreadSleep" -> SampleLikeMarkerConfig(name, label, name, WeightType.TRACING, "duration")
            "jdk.ThreadStart" -> SampleLikeMarkerConfig(name, label, name)
            else -> null
        },
    ) + listOfNotNull(
        when (name) {
            "jdk.ObjectAllocationSample" ->
                SampleLikeMarkerConfig("${name}_class", "$label Classes", name, WeightType.BYTES, "weight", "_class")
            else -> null
        },
    ) + emptyList<SampleLikeMarkerConfig>()
}

abstract class EventProcessor {
    abstract fun processEvent(event: ParsedJFREvent)
    open fun isFinished(): Boolean = true
}

/**
 * Per-thread processor. All [ThreadProcessor]s share one [Tables] instance.
 */
class ThreadProcessor(
    val config: Config,
    val isParentProcessThread: Boolean,
    val threadId: Long,
    val basicInformation: BasicInformation,
    val markerSchema: MarkerSchemaProcessor,
    val tables: Tables,
    private val spillDir: Path? = null,
) : EventProcessor() {
    // Lazily created on first use — avoids allocating temp dirs for threads with 0 or few events
    private var sampleSpiller: SampleSpiller? = null
    private var markerSpiller: MarkerSpiller? = null

    private fun getSampleSpiller(): SampleSpiller? {
        if (spillDir == null) return null
        return sampleSpiller ?: SampleSpiller(Files.createTempDirectory(spillDir, "samples-$threadId-")).also { sampleSpiller = it }
    }

    private fun getMarkerSpiller(): MarkerSpiller? {
        if (spillDir == null) return null
        return markerSpiller ?: MarkerSpiller(Files.createTempDirectory(spillDir, "markers-$threadId-")).also { markerSpiller = it }
    }
    private var start: Milliseconds = Double.MAX_VALUE
    private var end: Milliseconds = 0.0

    private val cpuLoads: NavigableMap<Long, Percentage> = TreeMap()

    private val eventTypeNames: MutableSet<String> = mutableSetOf()

    private var _items = 0
    val items: Int get() = _items

    // Lazily initialized so they pick up the spiller when first needed
    private val samplesTable: SamplesTableWrapper by lazy { SamplesTableWrapper(tables, getSampleSpiller()) }
    private val rawMarkerTable: RawMarkerTableWrapper by lazy {
        RawMarkerTableWrapper(tables, basicInformation, markerSchema, getMarkerSpiller())
    }

    private var threadStartMs: Milliseconds? = null
    private var threadEndMs: Milliseconds? = null
    private var thread: JFRThread? = null
    private var pausedRanges: MutableList<PausedRange> = mutableListOf()

    private fun processExecutionSample(event: ParsedJFREvent) {
        samplesTable.processEvent(event)
    }

    private fun processThreadCPULoad(event: ParsedJFREvent) {
        val user = event.getFloat("jvmUser") ?: 0f
        val system = event.getFloat("jvmSystem") ?: 0f
        cpuLoads[(event.startMs * 1000L).toLong()] = (user + system) * basicInformation.hwThreads
    }

    private fun generateSampleLikeMarkersConfig() =
        eventTypeNames.flatMap { it.generateSampleLikeMarkersConfig(markerSchema.config) }

    internal fun getCpuLoad(time: Milliseconds): Float {
        if (cpuLoads.isEmpty()) return 1.0f
        val micros: Long = (time * 1000L).toLong()
        val floor = cpuLoads.floorEntry(micros)
        val ceil = cpuLoads.ceilingEntry(micros)
        if (floor == null) return ceil!!.value
        if (ceil == null) return floor.value
        return if (micros - floor.value < ceil.value - micros) floor.value else ceil.value
    }

    override fun processEvent(event: ParsedJFREvent) {
        if (start == Double.MAX_VALUE) {
            start = if (isParentProcessThread) basicInformation.startTimeMillis else event.startMs
        }
        end = event.endMs
        eventTypeNames.add(event.typeName)
        if (thread == null) {
            event.realThread?.let { thread = it }
        }
        if (config.isExecutionSample(event.typeName)) {
            processExecutionSample(event)
            _items++
        } else {
            if (config.enableMarkers) {
                _items++
                rawMarkerTable.processEvent(event)
            }
            when (event.typeName) {
                "jdk.ThreadCPULoad" -> processThreadCPULoad(event)
                "jdk.ThreadStart" -> threadStartMs = event.startMs
                "jdk.ThreadEnd" -> threadEndMs = event.startMs
                "jdk.ThreadPark" -> pausedRanges.add(PausedRange(event.startMs, event.endMs, PauseReason.PARKED))
            }
        }
    }

    private val processType: String
        get() = if (isParentProcessThread) "tab" else "default"

    private val registerTime: Milliseconds
        get() = threadStartMs ?: start

    private val unregisterTime: Milliseconds
        get() = threadEndMs ?: end

    private val name: String
        get() = if (isParentProcessThread) "GeckoMain" else thread?.let { it.realJavaName ?: it.osName } ?: "<unknown>"

    private val pid: Pid get() = basicInformation.pid.toString()
    private val tid: Tid get() = if (isParentProcessThread) 0 else threadId

    @OptIn(ExperimentalSerializationApi::class)
    fun writeTo(json: BasicJSONGenerator) {
        json.writeStartObject()
        json.writeSimpleField("processType", processType)
        json.writeSimpleField("processStartupTime", basicInformation.startTimeMillis)
        json.writeSimpleField("processShutdownTime", end)
        json.writeSimpleField("registerTime", registerTime)
        json.writeSimpleField("unregisterTime", unregisterTime)
        val sortedRanges = pausedRanges.sortedBy { it.startTime!! }
        json.writeFieldName("pausedRanges")
        jsonFormat.encodeToStream(sortedRanges, json.output)
        json.writeFieldSep()
        json.writeSimpleField("name", name)
        json.writeSimpleField("isMainThread", name == "GeckoMain")
        json.writeSimpleField("processName", "Parent Process")
        json.writeSimpleField("pid", pid)
        json.writeSimpleField("tid", tid)
        json.writeFieldName("samples")
        samplesTable.write(json, this::getCpuLoad)
        json.writeFieldSep()
        json.writeFieldName("markers")
        rawMarkerTable.write(json)
        val slmConfig = generateSampleLikeMarkersConfig()
        if (slmConfig.isNotEmpty()) {
            json.writeFieldSep()
            json.writeFieldName("sampleLikeMarkersConfig")
            jsonFormat.encodeToStream(slmConfig, json.output)
        }
        json.writeEndObject()
        sampleSpiller?.deleteChunks()
        markerSpiller?.deleteChunks()
    }

    override fun isFinished() = threadEndMs != null
}

/** Basic information collected from the pre-scan pass */
data class BasicInformation(
    val config: Config,
    val mainThreadId: Long,
    val startTime: Instant,
    val interval: Instant,
    val metaFields: JFRMetaFields?,
    val initialSystemProperties: Map<String, String>,
    val initialEnvironmentVariables: Map<String, String>,
    val systemProcesses: List<Map<String, String>>,
) {
    val startTimeMillis = startTime.toMillis()
    val intervalMillis = interval.toEpochMilli()
    val intervalNanos = interval.toNanos()
    val pid: Long get() = metaFields?.pid ?: -1
    val fileFinder =
        if (config.useFileFinder) {
            FileFinder().also { finder ->
                config.sourcePath?.let { sourcePath -> finder.addFolder(sourcePath) }
            }
        } else null

    val oscpu: String?
        get() = metaFields?.osVersion?.let { os ->
            val osMatch = Regex("[A-Za-z0-9]+ [0-9.]+").find(os)?.groups?.first()?.value
            val cpu = metaFields.cpuModel?.split(" ")?.getOrNull(0)
            listOfNotNull(osMatch, cpu).joinToString(" ").ifEmpty { null }
        }

    val platform: String?
        get() = metaFields?.osVersion?.let {
            when {
                "Android" in it -> "Android"
                "Mac OS X" in it -> "Macintosh"
                "Windows" in it -> "Windows"
                else -> "X11"
            }
        }

    val hwThreads = metaFields?.hwThreads ?: 1

    fun classToUrl(packageName: String, className: String) =
        fileFinder?.findFile(packageName, className)?.let { file ->
            config.sourceUrl?.let {
                config.sourcePath?.let { sourcePath ->
                    val relativePath = file.relativeTo(sourcePath)
                    config.sourceUrl + "/" + relativePath
                } ?: it
            }
        } ?: config.sourceUrl?.let { it + "/" + packageName + "." + className + (if ("Kt" in className) ".kt" else ".java") }

    companion object {
        fun obtain(jfrFile: Path, config: Config): BasicInformation {
            var mainThreadId: Long? = null
            var backupMainThreadId: Long? = null
            var startTimeMs: Double? = null
            var backupStartTimeMs: Double? = null
            var activeRecordingStartMs: Double? = null
            var firstEventStartMs: Double? = null
            var jvmVersion: String? = null
            var jvmArgs: String? = null
            var javaArgs: String? = null
            var pid: Long = -1
            var cpuModel: String? = null
            var cpuCores: Int? = null
            var hwThreads: Int? = null
            var osVersion: String? = null
            val sampledStartTimesPerThread: MutableMap<Long, MutableList<Milliseconds>> = mutableMapOf()
            val initialSystemProperties: MutableMap<String, String> = mutableMapOf()
            val initialEnvironmentVariables: MutableMap<String, String> = mutableMapOf()
            val systemProcesses: MutableList<Map<String, String>> = mutableListOf()

            JafarReader.read(jfrFile) { event ->
                if (firstEventStartMs == null && event.startMs > 0) firstEventStartMs = event.startMs
                event.realThread?.let {
                    if (it.realJavaName == "main" && mainThreadId == null) {
                        mainThreadId = it.id
                    }
                }
                when (event.typeName) {
                    "jdk.JVMInformation" -> {
                        if (startTimeMs == null) {
                            val rawJvmStart = event.getLong("jvmStartTime")
                            // jvmStartTime is in milliseconds (not nanoseconds like event startTime)
                            startTimeMs = rawJvmStart?.toDouble()
                        }
                        jvmVersion = event.getString("jvmVersion")
                        jvmArgs = event.getString("jvmArguments")
                        javaArgs = event.getString("javaArguments")
                        pid = event.getLong("pid") ?: -1
                    }
                    "jdk.ActiveRecording" -> {
                        if (activeRecordingStartMs == null)
                            // recordingStart is @Timestamp(MILLISECONDS_SINCE_EPOCH)
                            activeRecordingStartMs = event.getLong("recordingStart")?.toDouble()
                    }
                    "jdk.CPUInformation" -> {
                        cpuModel = event.getString("cpu")
                        cpuCores = event.getInt("cores")
                        hwThreads = event.getInt("hwThreads")
                    }
                    "jdk.OSInformation" -> {
                        osVersion = event.getString("osVersion")
                    }
                    else -> {
                        if (config.isExecutionSample(event.typeName)) {
                            val sampleThread = event.sampledThreadOrNull
                            if (sampleThread != null) {
                                if (backupMainThreadId == null) {
                                    backupMainThreadId = sampleThread.id
                                    backupStartTimeMs = event.startMs
                                }
                                sampledStartTimesPerThread.getOrPut(sampleThread.id) { mutableListOf() }
                                    .add(event.startMs)
                            }
                        } else if (config.includeInitialSystemProperty && event.typeName == "jdk.InitialSystemProperty") {
                            val key = event.getString("key") ?: return@read
                            val value = event.getString("value") ?: return@read
                            initialSystemProperties[key] = value
                        } else if (config.includeInitialEnvironmentVariables && event.typeName == "jdk.InitialEnvironmentVariable") {
                            val key = event.getString("key") ?: return@read
                            val value = event.getString("value") ?: return@read
                            initialEnvironmentVariables[key] = value
                        } else if (config.includeSystemProcesses && event.typeName == "jdk.SystemProcess") {
                            systemProcesses.add(mapOf(
                                "pid" to (event.getString("pid") ?: ""),
                                "commandLine" to (event.getString("commandLine") ?: ""),
                            ))
                        }
                    }
                }
            }

            if (mainThreadId == null) mainThreadId = backupMainThreadId
            val effectiveStartMs = startTimeMs ?: backupStartTimeMs ?: activeRecordingStartMs ?: firstEventStartMs
                ?: error("Could not find start time")
            val startInstant = Instant.ofEpochMilli(effectiveStartMs.toLong())

            val estimatedIntervalInMillis = if (sampledStartTimesPerThread.isEmpty()) 10.0
                else estimateIntervalInMillis(sampledStartTimesPerThread)
            val estimatedInterval = Instant.ofEpochSecond(
                (estimatedIntervalInMillis / 1_000).toLong(),
                ((estimatedIntervalInMillis % 1_000) * 1_000_000).toLong(),
            )

            val metaFields = JFRMetaFields(jvmVersion, jvmArgs, javaArgs, pid, cpuModel, cpuCores, hwThreads, osVersion)

            return BasicInformation(
                config,
                mainThreadId ?: -1,
                startInstant,
                estimatedInterval,
                metaFields,
                initialSystemProperties,
                initialEnvironmentVariables,
                systemProcesses,
            )
        }
    }
}

sealed class AbstractThreadInfo(val startTimeMs: Milliseconds)

class BasicThreadInfo(
    startTimeMs: Milliseconds,
    val thread: JFRThread,
    val isMainThread: Boolean,
    internal var executionSampleCount: Int = 0,
    internal var otherSampleCount: Int = 0,
) : AbstractThreadInfo(startTimeMs), Comparable<BasicThreadInfo> {
    val id = thread.id
    val name = thread.name
    val isSystemThread = thread.isSystemThread()
    val isGCThread = thread.isGCThread()

    val hasExecutionSamples get() = executionSampleCount > 0
    val combinedSampleCount get() = executionSampleCount + otherSampleCount
    val score: Long get() = if (isMainThread) Long.MAX_VALUE else executionSampleCount * 2L + otherSampleCount

    override fun compareTo(other: BasicThreadInfo) = when {
        score > other.score -> -1
        score < other.score -> 1
        else -> 0
    }
}

class ParentThreadInfo(startTimeMs: Milliseconds) : AbstractThreadInfo(startTimeMs)

data class ProcessCPULoad(
    val timeMs: Milliseconds,
    val jvmUser: Percentage,
    val jvmSystem: Percentage,
    val machineTotal: Percentage,
)

internal class ProcessCounterProcessor(val basicInformation: BasicInformation, val config: Config) {
    val cpuLoads = mutableListOf<ProcessCPULoad>()
    val memoryProperties = mutableMapOf<MemoryProperty, MutableList<Pair<Milliseconds, Long>>>()

    init {
        config.addedMemoryProperties.forEach { memoryProperties[it] = mutableListOf() }
    }

    fun processEvent(event: ParsedJFREvent) {
        if (event.typeName == "jdk.CPULoad") {
            cpuLoads.add(ProcessCPULoad(
                event.startMs,
                event.getFloat("jvmUser") ?: 0f,
                event.getFloat("jvmSystem") ?: 0f,
                event.getFloat("machineTotal") ?: 0f,
            ))
        }
        // Memory properties handled separately if needed
    }

    private val cpuDisplay = CounterDisplayConfig(
        graphType = CounterGraphType.LINE_RATE, unit = "%", color = "grey",
    )

    private fun generateCPUCounters(endTimeMs: Milliseconds) =
        if (cpuLoads.size > 0) {
            listOf(Counter(
                name = "processCPU",
                category = "CPU",
                description = "Process CPU utilization",
                pid = basicInformation.pid.toString(),
                mainThreadIndex = 0,
                samples = CounterSamplesTable(
                    time = cpuLoads.map { it.timeMs },
                    count = cpuLoads.map { ((it.jvmUser + it.jvmSystem) * 1_000_000.0).roundToLong() },
                ),
                display = cpuDisplay,
            ))
        } else {
            generateGenericCPUCounters(endTimeMs)
        }

    private fun generateGenericCPUCounters(endTimeMs: Milliseconds): List<Counter> {
        val startSlice = (basicInformation.startTimeMillis / 100).roundToLong()
        val endSlice = (endTimeMs / 100).roundToLong()
        val slices = LongStream.range(startSlice, endSlice).mapToDouble { it * 100.0 }.toList()
        return listOf(Counter(
            name = "processCPU",
            category = "CPU",
            description = "Process CPU utilization",
            pid = basicInformation.pid.toString(),
            mainThreadIndex = 0,
            samples = CounterSamplesTable(time = slices, count = List(slices.size) { 10 }),
            display = cpuDisplay,
        ))
    }

    fun generateCounters(endTimeMs: Milliseconds): List<Counter> = generateCPUCounters(endTimeMs)
}

internal class MetaProcessor(
    val jfrFile: Path,
    val basicInformation: BasicInformation,
    val markerSchema: MarkerSchemaProcessor,
    val config: Config,
) {
    var endTimeMs: Milliseconds = basicInformation.startTimeMillis
    val threads: MutableMap<Long, BasicThreadInfo> = mutableMapOf()
    val parentThreadInfo = ParentThreadInfo(basicInformation.startTimeMillis)
    val gcThreads = mutableSetOf<Long>()

    fun processEvent(event: ParsedJFREvent) {
        val thread = event.realThread
        if (thread != null) {
            val threadInfo = threads.getOrPut(thread.id) {
                BasicThreadInfo(event.startMs, thread, thread.id == basicInformation.mainThreadId)
            }.also {
                if (it.isGCThread) gcThreads.add(it.id)
            }
            if (config.isExecutionSample(event.typeName)) threadInfo.executionSampleCount++
            else threadInfo.otherSampleCount++
        }
        val timeMs = event.endMs.takeIf { it > event.startMs } ?: event.startMs
        if (timeMs > endTimeMs) endTimeMs = timeMs
    }

    fun isGCThread(threadId: Long) = gcThreads.contains(threadId)

    private fun generateSystemProcessEntry(): ExtraProfileInfoEntry? {
        if (basicInformation.systemProcesses.isEmpty()) return null
        val format = TableMarkerFormat(listOf(
            TableColumnFormat(BasicMarkerFormatType.STRING, "ProcessId"),
            TableColumnFormat(BasicMarkerFormatType.STRING, "Command Line"),
        ))
        val value = JsonArray(basicInformation.systemProcesses.map { m ->
            JsonArray(listOf(JsonPrimitive(m["pid"] ?: ""), JsonPrimitive(m["commandLine"] ?: "")))
        })
        return ExtraProfileInfoEntry("System Process", format, value)
    }

    private fun generateTableEntry(map: Map<String, String>, label: String): ExtraProfileInfoEntry? {
        if (map.isEmpty()) return null
        val format = TableMarkerFormat(listOf(
            TableColumnFormat(BasicMarkerFormatType.STRING, "Name"),
            TableColumnFormat(BasicMarkerFormatType.STRING, "Value"),
        ))
        val value = JsonArray(map.entries.sortedBy { it.key }.map { e ->
            JsonArray(listOf(JsonPrimitive(e.key), JsonPrimitive(e.value)))
        })
        return ExtraProfileInfoEntry(label, format, value)
    }

    fun isValidThread(threadInfo: BasicThreadInfo) =
        if (threadInfo.isMainThread) true
        else if (threadInfo.isGCThread) config.includeGCThreads
        else if (threadInfo.combinedSampleCount >= config.minRequiredItemsPerThread) {
            if (!threadInfo.isSystemThread) threadInfo.hasExecutionSamples else true
        } else false

    fun sortedThreads(): List<AbstractThreadInfo> =
        listOf(parentThreadInfo) + threads.values.filter { isValidThread(it) }.sorted()

    fun toMeta(): ProfileMeta {
        val threads = sortedThreads()
        val initialVisibleThreadIds = List(
            threads.filterNot { t -> t is BasicThreadInfo && t.isSystemThread }.size,
        ) { index -> index }.take(config.initialVisibleThreads + 1)
        val initialSelectedThreadIds: List<ThreadIndex> =
            (if (config.selectProcessTrackInitially) listOf(0) else listOf()) +
                initialVisibleThreadIds.drop(1).take(config.initialSelectedThreads)
        val meta = basicInformation.metaFields
        return ProfileMeta(
            interval = basicInformation.interval.toMillis(),
            startTime = basicInformation.startTimeMillis,
            endTime = endTimeMs,
            categories = CategoryE.toCategoryList(),
            product = meta?.javaArgs ?: "JVM Application",
            stackwalk = 0,
            misc = meta?.jvmVersion?.let { "JVM Version $it" },
            oscpu = basicInformation.oscpu,
            cpuName = meta?.cpuModel,
            platform = basicInformation.platform,
            markerSchema = markerSchema.toMarkerSchemaList(),
            arguments = meta?.let { "jvm=${it.jvmArgs}  --  java=${it.javaArgs}" } ?: "<unknown>",
            physicalCPUs = meta?.cpuCores,
            logicalCPUs = meta?.hwThreads,
            sampleUnits = SampleUnits(threadCPUDelta = ThreadCPUDeltaUnit.US),
            importedFrom = jfrFile.toString(),
            extra = listOf(),
            initialVisibleThreads = initialVisibleThreadIds,
            initialSelectedThreads = initialSelectedThreadIds,
            keepProfileThreadOrder = true,
        )
    }
}

abstract class Processor(val config: Config, val jfrFile: Path) {
    val basicInformation = BasicInformation.obtain(jfrFile, config)
    val markerSchema = MarkerSchemaProcessor(config)

    abstract fun process(outputStream: OutputStream)

    fun processZipped(outputStream: OutputStream) {
        GZIPOutputStream(outputStream).use { process(it) }
    }

    companion object {
        const val MAX_JFR_SIZE_FOR_SINGLE_THREAD = 5_000_000L

        fun create(config: Config, jfrFile: Path): Processor = SimpleProcessor(config, jfrFile)
    }
}

class SimpleProcessor(config: Config, jfrFile: Path) : Processor(config, jfrFile) {
    @OptIn(ExperimentalSerializationApi::class)
    override fun process(outputStream: OutputStream) {
        val tables = Tables(config, basicInformation, markerSchema, basicInformation::classToUrl, config.sourceUrl)
        val threadToProcessor = mutableMapOf<Long, ThreadProcessor>()
        val metaProcessor = MetaProcessor(jfrFile, basicInformation, markerSchema, config)
        val processCounterProcessor = ProcessCounterProcessor(basicInformation, config)
        val lock = Any()

        val spillRoot = Files.createTempDirectory(config.spillDir ?: Path.of(System.getProperty("java.io.tmpdir")), "jfrtofp-")
        val parentThreadProcessor = ThreadProcessor(config, true, -1, basicInformation, markerSchema, tables, spillRoot)

        try {
            JafarReader.read(
                jfrFile,
                typeHandler = { type -> markerSchema.registerFromMetadata(type) },
                typeFilter = { typeName -> !config.isIgnoredEvent(typeName) },
                skipFieldsFilter = { typeName -> config.isExecutionSample(typeName) },
            ) { event ->
                synchronized(lock) {
                metaProcessor.processEvent(event)
                processCounterProcessor.processEvent(event)
                val realThread = event.realThread
                if (realThread != null) {
                    if (!config.includeGCThreads && metaProcessor.isGCThread(realThread.id)) return@synchronized
                    val processor = threadToProcessor.getOrPut(realThread.id) {
                        ThreadProcessor(config, false, realThread.id, basicInformation, markerSchema, tables, spillRoot)
                    }
                    processor.processEvent(event)
                } else {
                    parentThreadProcessor.processEvent(event)
                }
                }
            }

            val shared = SharedData(
                stringArray = tables.stringTable.toStringTable(),
                stackTable = tables.stackTraceTable.toStackTable(),
                frameTable = tables.frameTable.toFrameTable(),
                funcTable = tables.funcTable.toFuncTable(),
                resourceTable = tables.resourceTable.toResourceTable(),
                nativeSymbols = NativeSymbolTable(listOf(), listOf(), listOf(), listOf()),
                sources = tables.sourceTable.toSourceTable(),
            )

            val sortedThreadInfos = metaProcessor.sortedThreads()

            val json = BasicJSONGenerator(outputStream)
            json.writeStartObject()
            json.writeFieldName("meta")
            jsonFormat.encodeToStream(metaProcessor.toMeta(), json.output)
            json.writeFieldSep()
            json.writeFieldName("libs")
            json.writeEmptyArray()
            json.writeFieldSep()
            json.writeFieldName("shared")
            jsonFormat.encodeToStream(shared, json.output)
            json.writeFieldSep()
            json.writeFieldName("counters")
            jsonFormat.encodeToStream(processCounterProcessor.generateCounters(metaProcessor.endTimeMs), json.output)
            json.writeFieldSep()
            json.writeFieldName("threads")
            json.writeStartArray()
            var firstThread = true
            for (info in sortedThreadInfos) {
                val processor = when (info) {
                    is ParentThreadInfo -> parentThreadProcessor
                    is BasicThreadInfo -> threadToProcessor[info.id]
                        ?: error("Thread ${info.id} ${info.name} not found")
                }
                if (!firstThread) json.writeFieldSep()
                firstThread = false
                processor.writeTo(json)
            }
            json.writeEndArray()
            json.writeEndObject()
        } finally {
            spillRoot.toFile().deleteRecursively()
        }
    }
}

fun main() {
    val jfrFilePart = "small_profile"
    val processor = SimpleProcessor(Config(), Path.of("samples/$jfrFilePart.jfr"))
    Path.of("samples/$jfrFilePart.json.gz").outputStream().use {
        processor.processZipped(it)
    }
}
