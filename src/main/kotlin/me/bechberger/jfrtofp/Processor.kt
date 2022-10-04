package me.bechberger.jfrtofp

import jdk.jfr.ValueDescriptor
import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedObject
import jdk.jfr.consumer.RecordedStackTrace
import jdk.jfr.consumer.RecordedThread
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToStream
import me.bechberger.jfrtofp.CategoryE.Companion.MISC_OTHER
import me.bechberger.jfrtofp.FirefoxProfileGenerator.ByteCodeHelper.formatDescriptor
import me.bechberger.jfrtofp.FirefoxProfileGenerator.ByteCodeHelper.formatFunctionWithClass
import me.bechberger.jfrtofp.FirefoxProfileGenerator.ByteCodeHelper.formatRecordedClass
import me.bechberger.jfrtofp.types.BasicMarkerFormatType
import me.bechberger.jfrtofp.types.Bytes
import me.bechberger.jfrtofp.types.Category
import me.bechberger.jfrtofp.types.Counter
import me.bechberger.jfrtofp.types.CounterSamplesTable
import me.bechberger.jfrtofp.types.ExtraProfileInfoEntry
import me.bechberger.jfrtofp.types.ExtraProfileInfoSection
import me.bechberger.jfrtofp.types.FrameTable
import me.bechberger.jfrtofp.types.FuncTable
import me.bechberger.jfrtofp.types.IndexIntoCategoryList
import me.bechberger.jfrtofp.types.IndexIntoFrameTable
import me.bechberger.jfrtofp.types.IndexIntoFuncTable
import me.bechberger.jfrtofp.types.IndexIntoResourceTable
import me.bechberger.jfrtofp.types.IndexIntoStackTable
import me.bechberger.jfrtofp.types.IndexIntoStringTable
import me.bechberger.jfrtofp.types.IndexIntoSubcategoryListForCategory
import me.bechberger.jfrtofp.types.JsAllocationsTable
import me.bechberger.jfrtofp.types.MarkerDisplayLocation
import me.bechberger.jfrtofp.types.MarkerFormatType
import me.bechberger.jfrtofp.types.MarkerPhase
import me.bechberger.jfrtofp.types.MarkerSchema
import me.bechberger.jfrtofp.types.MarkerSchemaDataStatic
import me.bechberger.jfrtofp.types.MarkerSchemaDataString
import me.bechberger.jfrtofp.types.MarkerTrackConfig
import me.bechberger.jfrtofp.types.MarkerTrackLineConfig
import me.bechberger.jfrtofp.types.Milliseconds
import me.bechberger.jfrtofp.types.NativeAllocationsTable
import me.bechberger.jfrtofp.types.NativeSymbolTable
import me.bechberger.jfrtofp.types.Profile
import me.bechberger.jfrtofp.types.ProfileMeta
import me.bechberger.jfrtofp.types.RawMarkerTable
import me.bechberger.jfrtofp.types.ResourceTable
import me.bechberger.jfrtofp.types.SampleGroup
import me.bechberger.jfrtofp.types.SampleLikeMarkerConfig
import me.bechberger.jfrtofp.types.SampleUnits
import me.bechberger.jfrtofp.types.SamplesTable
import me.bechberger.jfrtofp.types.StackTable
import me.bechberger.jfrtofp.types.StringTable
import me.bechberger.jfrtofp.types.TableColumnFormat
import me.bechberger.jfrtofp.types.TableMarkerFormat
import me.bechberger.jfrtofp.types.Thread
import me.bechberger.jfrtofp.types.ThreadCPUDeltaUnit
import me.bechberger.jfrtofp.types.ThreadIndex
import me.bechberger.jfrtofp.types.WeightType
import me.bechberger.jfrtofp.types.resourceTypeEnum
import org.jline.reader.impl.DefaultParser
import org.objectweb.asm.Type
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.NavigableMap
import java.util.Optional
import java.util.TreeMap
import java.util.logging.Logger
import java.util.stream.LongStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.extension
import kotlin.io.path.relativeTo
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.streams.toList

fun Instant.toMicros(): Long = epochSecond * 1_000_000 + nano / 1_000

fun Instant.toMillis(): Milliseconds = toMicros() / 1_000.0

fun List<RecordedEvent>.estimateIntervalInMicros() = take(100).map { it.startTime.toMicros() }.let {
    it.zip(it.drop(1)).minOfOrNull { (a, b) -> b - a }
}

fun Map<RecordedThread, List<RecordedEvent>>.estimateIntervalInMicros() =
    values.filter { it.size > 2 }.mapNotNull { it.estimateIntervalInMicros() }.average().roundToLong()

val RecordedEvent.isExecutionSample
    get() = eventType.name.equals("jdk.ExecutionSample") || eventType.name.equals("jdk.NativeMethodSample")

val RecordedClass.pkg
    get() = name.split(".").let {
        it.subList(0, it.size - 1).joinToString(".")
    }

val RecordedClass.className
    get() = name.split(".").last()

/** different types of memory properties that can be shown in the track time line view */
enum class MemoryProperty(val propName: String, val description: String = propName) {
    USED_PHYSICAL_MEMORY("Used physical memory") {
        override fun isUsable(event: RecordedEvent): Boolean {
            return event.eventType.name == "jdk.PhysicalMemory"
        }

        override fun getValue(event: RecordedEvent): Long {
            return event.getLong("usedSize")
        }
    },
    RESERVED_HEAP("Reserved heap") {
        override fun isUsable(event: RecordedEvent): Boolean {
            return event.eventType.name == "jdk.GCHeapSummary"
        }

        override fun getValue(event: RecordedEvent): Long {
            return event.getValue<RecordedObject?>("heapSpace").getLong("reservedSize")
        }
    },
    COMMITTED_HEAP("Committed heap") {
        override fun isUsable(event: RecordedEvent): Boolean {
            return event.eventType.name == "jdk.GCHeapSummary"
        }

        override fun getValue(event: RecordedEvent): Long {
            return event.getValue<RecordedObject?>("heapSpace").getLong("committedSize")
        }
    },
    USED_HEAP("Used heap") {
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
    @Option(names = ["-n", "--non-project"], description = ["non project package prefixes"])
    var nonProjectPackagePrefixes: List<String> =
        listOf("java.", "javax.", "kotlin.", "jdk.", "com.google.", "org.apache.", "org.spring.")

    @Option(names = ["--max-exec-samples"], description = ["Maximum number of exec samples per thread"])
    var maxExecutionSamplesPerThread: Int = -1

    @Option(names = ["--max-misc-samples"], description = ["Maximum number of misc samples per thread"])
    var maxMiscSamplesPerThread: Int = -1

    @Option(names = ["--source"], description = ["SOURCE|SOURCE_URL"])
    var source: String = ""

    fun toConfig() = Config(
        nonProjectPackagePrefixes = nonProjectPackagePrefixes,
        maxExecutionSamplesPerThread = maxExecutionSamplesPerThread,
        maxMiscSamplesPerThread = maxMiscSamplesPerThread,
        sourcePath = if (source.isNotEmpty()) Path.of(source.split("|")[0]) else null,
        sourceUrl = if (source.isNotEmpty()) source.split("|")[1] else null
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
    val addedMemoryProperties: List<MemoryProperty> = listOf(MemoryProperty.USED_HEAP),
    /** time range of a given sample is at max 2.0 * interval */
    val maxIntervalFactor: Double = 2.0,
    val useNonProjectCategory: Boolean = true,
    val nonProjectPackagePrefixes: List<String> =
        listOf("java.", "javax.", "kotlin.", "jdk.", "com.google.", "org.apache.", "org.spring."),
    val isNonProjectType: (RecordedClass) -> Boolean = { k ->
        nonProjectPackagePrefixes.any { k.name.startsWith(it) }
    },
    val enableMarkers: Boolean = true,
    /** an objectsample weigth will be associated with the nearest stack trace
     * or the common prefix stack trace of the two nearest if the minimal time distance is > 0.25 * interval */
    val enableAllocations: Boolean = true,
    /** use native allocations view to show per allocated class allocations */
    val useNativeAllocViewForAllocations: Boolean = true,
    /** maximum number of stack frames */
    val maxStackTraceFrames: Int = Int.MAX_VALUE,
    val maxThreads: Int = Int.MAX_VALUE,
    val omitEventThreadProperty: Boolean = true,
    val maxExecutionSamplesPerThread: Int = -1,
    val maxMiscSamplesPerThread: Int = -1,
    val initialVisibleThreads: Int = 10,
    val selectProcessTrackInitially: Boolean = true,
    val initialSelectedThreads: Int = 10,
    val sourcePath: Path? = null,
    val sourceUrl: String? = null
) {

    fun isRelevantForJava(func: RecordedMethod) = false

    fun toUrl(func: RecordedMethod): String {
        return func.type.name + "#" + func.name + func.descriptor
    }
}

enum class CategoryE(
    val displayName: String,
    val color: String,
    val subcategories: MutableList<String> = mutableListOf()
) {
    OTHER("Other", "grey", mutableListOf("Profiling", "Waiting")), JAVA(
        "Java",
        "blue",
        mutableListOf("Other", "Interpreted", "Compiled", "Native", "Inlined")
    ),
    NON_PROJECT_JAVA(
        "Java (non-project)",
        "darkgray",
        mutableListOf("Other", "Interpreted", "Compiled", "Native", "Inlined")
    ),
    GC("GC", "orange", mutableListOf("Other")), CPP("Native", "red", mutableListOf("Other")),

    // JFR related categories
    JFR("Flight Recorder", "lightgrey"), JAVA_APPLICATION(
        "Java Application",
        "red"
    ),
    JAVA_APPLICATION_STATS(
        "Java Application, Statistics",
        "grey"
    ),
    JVM_CLASSLOADING("Java Virtual Machine, Class Loading", "brown"), JVM_CODE_CACHE(
        "Java Virtual Machine, Code Cache",
        "lightbrown"
    ),
    JVM_COMPILATION_OPT(
        "Java Virtual Machine, Compiler, Optimization",
        "lightblue"
    ),
    JVM_COMPILATION("Java Virtual Machine, Compiler", "lightblue"), JVM_DIAGNOSTICS(
        "Java Virtual Machine, Diagnostics",
        "lightgrey"
    ),
    JVM_FLAG("Java Virtual Machine, Flag", "lightgrey"), JVM_GC_COLLECTOR(
        "Java Virtual Machine, GC, Collector",
        "orange"
    ),
    JVM_GC_CONF(
        "Java Virtual Machine, GC, Configuration",
        "lightgrey"
    ),
    JVM_GC_DETAILED("Java Virtual Machine, GC, Detailed", "lightorange"), JVM_GC_HEAP(
        "Java Virtual Machine, GC, Heap",
        "lightorange"
    ),
    JVM_GC_METASPACE("Java Virtual Machine, GC, Metaspace", "lightorange"), // add another category for errors (OOM)
    JVM_GC_PHASES(
        "Java Virtual Machine, GC, Phases",
        "lightorange"
    ),
    JVM_GC_REFERENCE(
        "Java Virtual Machine, GC, Reference",
        "lightorange"
    ),
    JVM_INTERNAL("Java Virtual Machine, Internal", "lightgrey"), JVM_PROFILING(
        "Java Virtual Machine, Profiling",
        "lightgrey"
    ),
    JVM_RUNTIME_MODULES(
        "Java Virtual Machine, Runtime, Modules",
        "lightgrey"
    ),
    JVM_RUNTIME_SAFEPOINT(
        "Java Virtual Machine, Runtime, Safepoint",
        "yellow"
    ),
    JVM_RUNTIME_TABLES(
        "Java Virtual Machine, Runtime, Tables",
        "lightgrey"
    ),
    JVM_RUNTIME("Java Virtual Machine, Runtime", "green"), JVM(
        "Java Virtual Machine",
        "lightgrey"
    ),
    OS_MEMORY("Operating System, Memory", "lightgrey"), OS_NETWORK(
        "Operating System, Network",
        "lightgrey"
    ),
    OS_PROCESS("Operating System, Processor", "lightgrey"), OS("Operating System", "lightgrey"),
    MISC("Misc", "lightgrey", mutableListOf("Other"));

    val index: Int = ordinal

    fun sub(sub: String) = subcategories.indexOf(sub).let {
        index to if (it == -1) {
            subcategories.add(sub)
            subcategories.size - 1
        } else it
    }

    fun toCategory() = Category(displayName, color, subcategories)

    companion object {
        fun toCategoryList() = values().map { it.toCategory() }

        internal val map by lazy {
            values().associateBy { it.displayName }
        }

        fun fromName(displayName: String) = map[displayName] ?: OTHER

        val MISC_OTHER = MISC.sub("Other")
    }
}

/** generates JSON for the profile.firefox.com viewer */
class FirefoxProfileGenerator(
    val events: List<RecordedEvent>,
    val config: Config,
    intervalSeconds: Double? = null,
    val jfrFile: Path? = null
) {

    constructor(jfrFile: Path, config: Config = Config()) : this(
        RecordingFile.readAllEvents(jfrFile).sortedBy { it.startTime },
        config,
        jfrFile = jfrFile
    )

    private val eventsPerType: Map<String, List<RecordedEvent>> = events.groupByType()

    private val intervalMicros =
        round(
            (
                intervalSeconds ?: (
                    eventsPerType["jdk.ExecutionSample"]!!.take(1000).groupBy { it.sampledThread }
                        .estimateIntervalInMicros() / 1_000_000.0
                    )
                ) * 1_000_000.0
        )

    /*
    <Event name="JVMInformation" category="Java Virtual Machine" label="JVM Information"
         description="Description of JVM and the Java application"
         period="endChunk">
    <Field type="string" name="jvmName" label="JVM Name" />
    <Field type="string" name="jvmVersion" label="JVM Version" />
    <Field type="string" name="jvmArguments" label="JVM Command Line Arguments" />
    <Field type="string" name="jvmFlags" label="JVM Settings File Arguments" />
    <Field type="string" name="javaArguments" label="Java Application Arguments" />
    <Field type="long" contentType="epochmillis" name="jvmStartTime" label="JVM Start Time" />
    <Field type="long" name="pid" label="Process Identifier" />
     </Event>
     */

    private val jvmInformation
        get() = eventsPerType["jdk.JVMInformation"]!![0]

    private val cpuInformation
        get() = eventsPerType["jdk.CPUInformation"]!![0]

    private val startTimeMillis
        get() = jvmInformation.getLong("jvmStartTime") * 1.0

    private val endTimeMillis
        get() = eventsPerType["jdk.ShutDown"]?.getOrNull(0)?.startTime?.toMillis() ?: events.last().startTime.toMillis()

    val pid
        get() = jvmInformation.getLong("pid")

    private val executionSamples
        get() = eventsPerType["jdk.ExecutionSample"]!!
    private val extensionSamplesTreeMap by lazy {
        executionSamples.map { it.startTime.toMillis() to it }.toMap(TreeMap())
    }

    val platform
        get() = eventsPerType["jdk.OSInformation"]?.getOrNull(0)?.getString("osVersion")?.let {
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

    val oscpu
        get() = eventsPerType["jdk.OSInformation"]?.getOrNull(0)?.getString("osVersion")?.let {
            it.split("uname:")[1].split("\n").getOrNull(0)?.let { distId ->
                eventsPerType["jdk.CPUInformation"]?.getOrNull(0)
                    ?.getString("cpu")?.split(" ")
                    ?.getOrNull(0)?.let { cpu ->
                        "$cpu $distId"
                    } ?: distId
            } ?: it
        }

    private val mainThread by lazy {
        executionSamples.stream().limit(1000).sorted(Comparator.comparingLong { it.startTime.toMicros() }).findFirst()
            .get().sampledThread
    }

    private val mainThreadId
        get() = mainThread.javaThreadId

    /*
    <Event name="CPULoad" category="Operating System, Processor" label="CPU Load" description="OS CPU Load" period="everyChunk">
    <Field type="float" contentType="percentage" name="jvmUser" label="JVM User" />
    <Field type="float" contentType="percentage" name="jvmSystem" label="JVM System" />
    <Field type="float" contentType="percentage" name="machineTotal" label="Machine Total" />
  </Event>

  <Event name="ThreadCPULoad" category="Operating System, Processor" label="Thread CPU Load" period="everyChunk" thread="true">
    <Field type="float" contentType="percentage" name="user" label="User Mode CPU Load" description="User mode thread CPU load" />
    <Field type="float" contentType="percentage" name="system" label="System Mode CPU Load" description="System mode thread CPU load" />
  </Event>
     */
    /** os thread id / empty for process -> time micros -> cpu usage (system and user) */
    private val cpuLoads: Map<Optional<Long>, NavigableMap<Long, Float>> =
        mutableMapOf<Optional<Long>, NavigableMap<Long, Float>>().also { res ->
            events.filter { it.eventType.name == "jdk.CPULoad" || it.eventType.name == "jdk.ThreadCPULoad" }.forEach {
                val time = it.startTime.toMicros()
                if (it.eventType.name == "jdk.CPULoad") {
                    val user = it.getFloat("jvmUser")
                    val system = it.getFloat("jvmSystem")
                    res.computeIfAbsent(Optional.empty()) { TreeMap() }[time] = user + system
                } else {
                    val user = it.getFloat("user")
                    val system = it.getFloat("system")
                    res.computeIfAbsent(Optional.of(it.sampledThread.javaThreadId)) { TreeMap() }[time] = user + system
                }
            }
        }

    /** approximates the cpu load at a given time for a given thread */
    private fun getCpuLoad(thread: RecordedThread?, timeInMicros: Long): Float {
        return cpuLoads[Optional.ofNullable(thread?.javaThreadId)]?.let { loads ->
            loads.lowerEntry(timeInMicros)?.value?.let {
                (loads.ceilingEntry(timeInMicros)?.value ?: it) + (it / 2f)
            } ?: 0f
        } ?: 1f
    }

    private fun generateMemoryCounters() = config.addedMemoryProperties.map { prop ->
        Counter(
            name = prop.propName,
            category = "Memory",
            description = prop.description,
            pid = pid,
            mainThreadIndex = 0,
            sampleGroups = listOf(
                SampleGroup(
                    0,
                    prop.getValues(this.events).let { timed ->
                        CounterSamplesTable(
                            time = timed.map { (t, _) -> t },
                            number = timed.map { -1 },
                            count = timed.mapIndexed { i, (_, value) ->
                                if (i == 0) {
                                    value
                                } else {
                                    value - timed[i - 1].second
                                }
                            }
                        )
                    }
                )
            )
        )
    }.filter { it.sampleGroups[0].samples.length > 0 }

    private fun generateCPUCounters() = eventsPerType["jdk.CPULoad"]?.let { cpuLoads ->
        listOf( // TODO: does not work
            Counter(
                name = "processCPU",
                category = "CPU",
                description = "Process CPU utilization",
                pid = pid,
                mainThreadIndex = 0,
                sampleGroups = listOf(
                    SampleGroup(
                        0,
                        CounterSamplesTable(
                            time = cpuLoads.map { it.startTime.toMillis() },
                            number = List(cpuLoads.size) { 0 },
                            count = cpuLoads.map {
                                ((it.getFloat("jvmUser") + it.getFloat("jvmSystem")) * 1_000_000.0)
                                    .roundToLong()
                            }
                        )
                    )
                )
            )
        )
    } ?: generateGenericCPUCounters() // TODO also use if not enough info

    private fun generateGenericCPUCounters(): List<Counter> {
        val slices =
            LongStream.range((startTimeMillis / 100).roundToLong(), (endTimeMillis / 100).roundToLong()).mapToDouble {
                it * 100.0
            }.toList()
        return listOf(
            Counter(
                name = "processCPU",
                category = "CPU",
                description = "Process CPU utilization",
                pid = pid,
                mainThreadIndex = 0,
                sampleGroups = listOf(
                    SampleGroup(
                        0,
                        CounterSamplesTable(
                            time = slices,
                            number = List(slices.size) { 0 },
                            count = List(slices.size) { 10 }
                        )
                    )
                )
            )
        )
    }

    data class EventWithTimeRange(
        val event: RecordedEvent,
        val start: Milliseconds,
        val durationBefore: Milliseconds
    )

    private fun eventsWithTimeRanges(sortedEvents: List<RecordedEvent>): List<EventWithTimeRange> {
        var startTimeMicros = 0L
        return sortedEvents.map { event ->
            val curStart = event.startTime.toMicros()
            val diff = min(curStart - startTimeMicros, intervalMicros.toLong() * 2)
            startTimeMicros = curStart
            EventWithTimeRange(event, curStart / 1000.0, diff / 1000.0)
        }
    }

    class StringTableWrapper {
        val map = mutableMapOf<String, IndexIntoStringTable>()
        val strings = mutableListOf<String>()

        fun getString(string: String): IndexIntoStringTable {
            return map.getOrPut(string) {
                strings.add(string)
                map.size
            }
        }

        fun toStringTable(): StringTable = strings

        val size: Int
            get() = strings.size
    }

    class ResourceTableWrapper {
        val map = mutableMapOf<RecordedMethod, IndexIntoResourceTable>()
        val names = mutableListOf<IndexIntoStringTable>()
        val hosts = mutableListOf<IndexIntoStringTable?>()
        val types = mutableListOf<resourceTypeEnum>()

        fun getResource(tables: Tables, func: RecordedMethod, isJava: Boolean): IndexIntoResourceTable {
            return map.computeIfAbsent(func) {
                val wholeName = func.type.name
                names.add(tables.getString(wholeName.split("$").first()))
                if (isJava) {
                    hosts.add(tables.getString(wholeName))
                    types.add(5)
                } else {
                    hosts.add(null)
                    types.add(0)
                }
                map.size
            }
        }

        fun toResourceTable() = ResourceTable(name = names, host = hosts, type = types)

        val size: Int
            get() = names.size
    }

    data class Tables(
        val config: Config,
        val stringTable: StringTableWrapper = StringTableWrapper(),
        val resourceTable: ResourceTableWrapper = ResourceTableWrapper(),
        val frameTable: FrameTableWrapper = FrameTableWrapper(),
        val stackTraceTable: StackTableWrapper = StackTableWrapper(),
        val funcTable: FuncTableWrapper = FuncTableWrapper(),
        val classToUrl: (String, String) -> String?
    ) {
        fun getString(string: String) = stringTable.getString(string)
    }

    /** Helps to format types and other byte code related things */
    object ByteCodeHelper {
        fun formatFunctionWithClass(func: RecordedMethod) =
            "${func.type.className}.${func.name}${formatDescriptor(func.descriptor)}"

        fun formatDescriptor(descriptor: String): String {
            val args = "(${Type.getArgumentTypes(descriptor).joinToString(", ") {
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

    class FuncTableWrapper {

        val map = mutableMapOf<RecordedMethod, IndexIntoFuncTable>()
        val names = mutableListOf<IndexIntoStringTable>()
        private val isJss = mutableListOf<Boolean>()
        private val relevantForJss = mutableListOf<Boolean>()
        private val resourcess = mutableListOf<IndexIntoResourceTable>() // -1 if not present
        val fileNames = mutableListOf<IndexIntoStringTable?>()
        val sourceUrls = mutableListOf<IndexIntoStringTable?>()
        private val miscFunctions = mutableMapOf<String, IndexIntoFuncTable>()

        fun getFunction(tables: Tables, func: RecordedMethod, isJava: Boolean): IndexIntoFuncTable {
            return map.computeIfAbsent(func) {
                val type = func.type
                val url = tables.classToUrl(type.className.split("$").last(), type.pkg)
                sourceUrls.add(url?.let { tables.getString(url) })
                names.add(tables.getString(formatFunctionWithClass(func)))
                isJss.add(isJava)
                relevantForJss.add(tables.config.isRelevantForJava(func))
                resourcess.add(tables.resourceTable.getResource(tables, func, isJava))
                fileNames.add(null)
                map.size
            }
        }

        fun getMiscFunction(tables: Tables, name: String, native: Boolean): IndexIntoStringTable {
            return miscFunctions.computeIfAbsent(name) {
                val index = names.size
                names.add(tables.getString(name))
                isJss.add(native)
                relevantForJss.add(true)
                resourcess.add(-1)
                fileNames.add(null)
                index
            }
        }

        fun toFuncTable() = FuncTable(
            name = names,
            isJS = isJss,
            relevantForJS = relevantForJss,
            resource = resourcess,
            fileName = fileNames,
            sourceUrl = sourceUrls
        )

        val size: Int
            get() = fileNames.size
    }

    class FrameTableWrapper {

        val map = mutableMapOf<Pair<IndexIntoFuncTable, Int?>, IndexIntoFrameTable>()
        val categories = mutableListOf<IndexIntoCategoryList?>()
        val subcategories = mutableListOf<IndexIntoSubcategoryListForCategory?>()
        val funcs = mutableListOf<IndexIntoFuncTable>()
        val lines = mutableListOf<Int?>()
        private val miscFrames = mutableMapOf<String, IndexIntoStringTable>()

        fun getFrame(
            tables: Tables,
            frame: RecordedFrame,
            category: Pair<Int, Int>? = null
        ): IndexIntoFrameTable {
            val func = tables.funcTable.getFunction(tables, frame.method, frame.isJavaFrame)
            val line = if (frame.lineNumber == -1) null else frame.lineNumber
            return map.computeIfAbsent(func to line) {
                val (category, sub) = category
                    ?: if (tables.config.useNonProjectCategory && frame.isJavaFrame && tables.config.isNonProjectType(
                            frame.method.type
                        )
                    ) {
                        CategoryE.NON_PROJECT_JAVA.sub(frame.type)
                    } else if (frame.isJavaFrame) {
                        CategoryE.JAVA.sub(frame.type)
                    } else {
                        CategoryE.CPP.sub(frame.type)
                    }
                funcs.add(func)
                categories.add(category)
                subcategories.add(sub)
                lines.add(line)
                lines.size - 1
            }
        }

        fun getMiscFrame(
            tables: Tables,
            name: String,
            category: CategoryE,
            subcategory: String,
            isNative: Boolean
        ): IndexIntoFrameTable {
            return miscFrames.computeIfAbsent(name) {
                val (cat, sub) = category.sub(subcategory)
                categories.add(cat)
                subcategories.add(sub)
                funcs.add(tables.funcTable.getMiscFunction(tables, name, isNative))
                lines.add(null)
                lines.size - 1
            }
        }

        fun toFrameTable() = FrameTable(category = categories, subcategory = subcategories, func = funcs, line = lines)

        val size: Int
            get() = funcs.size
    }

    class HashedList<T>(val array: List<T>, val start: Int = 0, val end: Int = array.size) {
        override fun hashCode(): Int {
            var hash = 0
            for (i in start until end) {
                hash = hash * 31 + array[i].hashCode()
            }
            return hash
        }

        override fun equals(other: Any?): Boolean {
            if (other !is HashedList<*>) {
                return false
            }
            if ((other.end - other.start) != (end - start)) {
                return false
            }
            for (i in 0 until (end - start)) {
                if (array[i + start] != other.array[i + other.start]) {
                    return false
                }
            }
            return true
        }

        val size: Int
            get() = end - start

        val first: T
            get() = array[start]

        val last: T
            get() = array[end - 1]

        operator fun get(index: Int): T {
            return array[start + index]
        }
    }

    class StackTableWrapper {

        val map = mutableMapOf<HashedList<IndexIntoFrameTable>, IndexIntoStackTable>()
        val frames = mutableListOf<IndexIntoFrameTable>()
        val prefix = mutableListOf<IndexIntoFrameTable?>()
        val categories = mutableListOf<IndexIntoCategoryList>()
        val subcategories = mutableListOf<IndexIntoSubcategoryListForCategory>()
        private val miscStacks = mutableMapOf<String, IndexIntoStringTable>()

        /** common prefix stack */
        fun getCommonStack(
            tables: Tables,
            stackTrace: RecordedStackTrace,
            stackTrace2: RecordedStackTrace,
            category: Pair<Int, Int>? = null
        ): IndexIntoStackTable {
            val list = getHashedFrameList(tables, stackTrace, category)
            val list2 = getHashedFrameList(tables, stackTrace2, category)
            var commonCount = min(list.size, list2.size)
            for (i in 0 until min(list.size, list2.size)) {
                if (list[i] != list2[i]) {
                    commonCount = i
                }
            }
            return getStack(tables, HashedList(list.array, 0, commonCount))
        }

        private fun getHashedFrameList(
            tables: Tables,
            stackTrace: RecordedStackTrace,
            category: Pair<Int, Int>? = null
        ) =
            HashedList(stackTrace.frames.reversed().map { tables.frameTable.getFrame(tables, it, category) })

        fun getStack(
            tables: Tables,
            stackTrace: RecordedStackTrace,
            category: Pair<Int, Int>? = null,
            maxStackTraceFrames: Int = Int.MAX_VALUE
        ): IndexIntoStackTable {
            return getStack(tables, getHashedFrameList(tables, stackTrace, category), maxStackTraceFrames)
        }

        fun getStack(
            tables: Tables,
            stackTrace: HashedList<IndexIntoFrameTable>,
            maxStackTraceFrames: Int = Int.MAX_VALUE
        ): IndexIntoStackTable {
            if (maxStackTraceFrames == 0) {
                return -1
            }
            // top frame is on the highest index
            if (!map.containsKey(stackTrace)) {
                if (stackTrace.size == 0) {
                    return -1
                }
                val topFrame = stackTrace.last
                val cat = tables.frameTable.categories[topFrame]!!
                val sub = tables.frameTable.subcategories[topFrame]!!
                val pref = if (stackTrace.size > 1) getStack(
                    tables,
                    HashedList(stackTrace.array, stackTrace.start, stackTrace.end - 1),
                    maxStackTraceFrames - 1
                ) else null
                val index = frames.size
                prefix.add(pref)
                frames.add(topFrame)
                categories.add(cat)
                subcategories.add(sub)
                map[stackTrace] = index
            }
            return map[stackTrace]!!
        }

        fun getMiscStack(
            tables: Tables,
            name: String,
            category: CategoryE = CategoryE.MISC,
            subcategory: String = "Other",
            isNative: Boolean = false
        ): IndexIntoStackTable {
            return miscStacks.computeIfAbsent(name) {
                val (cat, sub) = category.sub(subcategory)
                categories.add(cat)
                subcategories.add(sub)
                prefix.add(null)
                frames.add(tables.frameTable.getMiscFrame(tables, name, category, subcategory, isNative))
                prefix.size - 1
            }
        }

        fun toStackTable() =
            StackTable(frame = frames, prefix = prefix, category = categories, subcategory = subcategories)

        val size: Int
            get() = frames.size
    }

    private fun generateSamplesTable(
        tables: Tables,
        executionSamples: List<EventWithTimeRange>
    ): SamplesTable {
        val stack = mutableListOf<IndexIntoStackTable?>()
        val time = mutableListOf<Milliseconds>()
        // in ms,    delta[i] = [time[i] - time[i - 1]] * [usage in this interval]
        val threadCPUDelta = mutableListOf<Milliseconds?>()
        for ((sample, start, durationBefore) in executionSamples) {
            time.add(start)
            threadCPUDelta.add(
                durationBefore * 1000 * getCpuLoad(
                    sample.sampledThread,
                    sample.startTime.toMicros() - (durationBefore * 1000).toLong() / 2
                )
            )
            stack.add(
                sample.stackTrace.let {
                    tables.stackTraceTable.getStack(
                        tables,
                        it,
                        maxStackTraceFrames = config.maxStackTraceFrames
                    )
                }
            )
        }
        return SamplesTable(
            stack = stack,
            time = time,
            threadCPUDelta = threadCPUDelta
        )
    }

    /**
     * Model the object allocation sites view the JsAllocationTable
     *
     <Event name="ObjectAllocationSample" category="Java Application" label="Object Allocation Sample"
     thread="true" stackTrace="true" startTime="false" throttle="true">
     <Field type="Class" name="objectClass" label="Object Class" description="Class of allocated object" />
     <Field type="long" contentType="bytes" name="weight" label="Sample Weight"
     description="The relative weight of the sample. Aggregating the weights for a large number of samples,
     for a particular class, thread or stack trace, gives a statistically accurate representation of the allocation pressure" />
     </Event>
     */
    private fun generateJsAllocationsTable(
        tables: Tables,
        allocationSampleEvents: List<RecordedEvent>
    ): JsAllocationsTable {
        val time = mutableListOf<Milliseconds>()
        val className = mutableListOf<String>()
        val weight = mutableListOf<Bytes>()
        val stack = mutableListOf<IndexIntoStackTable?>()
        for (sample in allocationSampleEvents) {
            time.add(sample.startTime.toMillis())
            className.add(formatRecordedClass(sample.getClass("objectClass")))
            weight.add(sample.getLong("weight"))
            // now we pick the nearest stack trace that we access to
            val lowEntry = extensionSamplesTreeMap.floorEntry(sample.startTime.toMillis())
            val highEntry = extensionSamplesTreeMap.ceilingEntry(sample.startTime.toMillis())
            val nearestSampleEvent = if (lowEntry == null && highEntry == null) {
                null
            } else if (lowEntry == null) {
                highEntry.value
            } else {
                val lowTimeDelta = sample.startTime.toMillis() - lowEntry.key
                val highTimeDelta = highEntry.key - sample.startTime.toMillis()
                if (lowTimeDelta < highTimeDelta) {
                    lowEntry.value
                } else {
                    highEntry.value
                }
            }
            val stackTraceIndex = nearestSampleEvent?.let {
                val diff = abs(sample.startTime.toMicros() - nearestSampleEvent.startTime.toMicros())
                if (diff <= intervalMicros * 0.25 || lowEntry == null || highEntry == null) {
                    // take the nearest one if it is less than 25% of the interval away
                    tables.stackTraceTable.getStack(tables, nearestSampleEvent.stackTrace, CategoryE.MISC_OTHER)
                } else if (diff <= intervalMicros) { // take the common prefix stack
                    tables.stackTraceTable.getCommonStack(
                        tables,
                        lowEntry.value.stackTrace,
                        highEntry.value.stackTrace,
                        MISC_OTHER
                    )
                } else {
                    null
                }
            } ?: tables.stackTraceTable.getMiscStack(tables, "<unknown>", CategoryE.OTHER, "Unknown", false)
            stack.add(stackTraceIndex)
        }
        return JsAllocationsTable(
            time = time,
            className = className,
            weight = weight,
            stack = stack
        )
    }

    private val fileFinder = FileFinder()

    private fun classToUrl(packageName: String, className: String) = fileFinder.findFile(packageName, className)?.let { file ->
        config.sourceUrl?.let {
            config.sourcePath!!.let { sourcePath ->
                val relativePath = file.relativeTo(sourcePath)
                config.sourceUrl + "/" + relativePath
            }
        }
    }

    init {
        config.sourcePath?.let { fileFinder.addFolder(it) }
    }

    /** like generateJsAllocationsTable, but models the allocated objects in the NativeAllocationTable */
    private fun generateNativeAllocationsTable(
        tables: Tables,
        allocationSampleEvents: List<RecordedEvent>
    ): NativeAllocationsTable {
        val time = mutableListOf<Milliseconds>()
        val weight = mutableListOf<Bytes>()
        val stack = mutableListOf<IndexIntoStackTable?>()
        for (sample in allocationSampleEvents) {
            time.add(sample.startTime.toMillis())
            weight.add(sample.getLong("weight"))
            stack.add(
                tables.stackTraceTable.getMiscStack(
                    tables,
                    formatRecordedClass(sample.getClass("objectClass")),
                    CategoryE.MISC,
                    "Other",
                    false
                )
            )
        }
        return NativeAllocationsTable(
            time = time,
            weight = weight,
            stack = stack
        )
    }

    class RawMarkerTableWrapper {
        private val datas = mutableListOf<Map<String, JsonElement>>()
        val names = mutableListOf<IndexIntoStringTable>()
        private val startTimes = mutableListOf<Milliseconds?>()
        private val endTimes = mutableListOf<Milliseconds?>()
        var phases = mutableListOf<MarkerPhase>()
        val categories = mutableListOf<IndexIntoCategoryList>()

        fun process(
            markerSchema: MarkerSchemaWrapper,
            tables: Tables,
            event: RecordedEvent,
            prof: FirefoxProfileGenerator
        ) {
            val fieldMapping = markerSchema.addForEvent(event) ?: return
            names.add(tables.getString(event.eventType.name))
            startTimes.add(event.startTime.toMillis())
            endTimes.add(event.endTime.toMillis())
            phases.add(if (event.endTime == event.startTime) 0 else 1) // instant vs interval
            categories.add(CategoryE.fromName(event.eventType.categoryNames.first()).index)
            val data =
                event.fields.filter { !markerSchema.isIgnoredField(it) && event.getValue<Any>(it.name) != null }.map {
                    fieldMapping[it.name] to MarkerType.fromName(it).convert(event, it.name, prof, tables)
                        .toJsonElement()
                }.toMap(mutableMapOf())
            data["type"] = event.eventType.name.toJsonElement()
            data["startTime"] = (event.startTime.toMillis() - prof.startTimeMillis).toJsonElement()
            when (event.eventType.name) {
                "jdk.ObjectAllocationSample" -> {
                    data["_class"] = tables.stackTraceTable.getMiscStack(tables, formatRecordedClass(event.getClass("objectClass")))
                        .toJsonElement()
                }
            }
            datas.add(data)
        }

        fun toRawMarkerTable() = RawMarkerTable(
            data = datas,
            name = names,
            startTime = startTimes,
            endTime = endTimes,
            phase = phases,
            category = categories
        )
    }

    private fun generateMarkersTable(
        markerSchema: MarkerSchemaWrapper,
        table: Tables,
        events: List<RecordedEvent>,
        eventsForProcess: List<RecordedEvent>
    ): RawMarkerTable {
        return RawMarkerTableWrapper().also { raw ->
            events.forEach { raw.process(markerSchema, table, it, this) }
            eventsForProcess.forEach { raw.process(markerSchema, table, it, this) }
        }.toRawMarkerTable()
    }

    enum class MarkerType(
        val type: MarkerFormatType,
        val converter: (
            event: RecordedObject,
            field: String,
            prof: FirefoxProfileGenerator,
            tables: Tables
        ) -> Any = { event, field, prof, tables ->
            event.getValue<Any?>(field).toString()
        },
        val aliases: List<String> = emptyList(),
        /* unspecific type */
        val generic: Boolean = false
    ) {
        // look first for contentType, then for field name and last for actual type
        BOOLEAN(BasicMarkerFormatType.STRING),
        BYTES(
            BasicMarkerFormatType.BYTES,
            { event, field, prof, _ ->
                when (val value = event.getValue<Any?>(field)) {
                    is Long -> value.toLong()
                    is Double -> value.toDouble()
                    else -> throw IllegalArgumentException("Cannot convert $value to bytes")
                }
            },
            listOf(
                "dataAmount", "allocated", "totalSize", "usedSize",
                "initialSize", "reservedSize", "nonNMethodSize", "profiledSize",
                "nonProfiledSize", "expansionSize", "minBlockLength", "minSize", "maxSize",
                "osrBytesCompiled", "minTLABSize", "tlabRefillWasteLimit"
            )
        ),
        ADDRESS(
            BasicMarkerFormatType.STRING,
            { event, field, prof, _ -> "0x" + event.getLong(field).toString(16) },
            listOf(
                "baseAddress",
                "topAddress",
                "startAddress",
                "reservedTopAddress",
                "heapAddressBits",
                "objectAlignment"
            )
        ),
        UBYTE(
            BasicMarkerFormatType.INTEGER,
            { event, field, prof, _ -> event.getLong(field) },
            generic = true
        ),
        UNSIGNED(
            BasicMarkerFormatType.INTEGER,
            { event, field, prof, _ -> event.getLong(field) },
            generic = true
        ),
        INT(BasicMarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }), UINT(
            BasicMarkerFormatType.INTEGER,
            { event, field, prof, _ -> event.getLong(field) },
            generic = true
        ),
        USHORT(
            BasicMarkerFormatType.INTEGER,
            { event, field, prof, _ -> event.getLong(field) },
            generic = true
        ),
        LONG(
            BasicMarkerFormatType.INTEGER,
            { event, field, prof, _ -> event.getLong(field) },
            generic = true
        ),
        FLOAT(
            BasicMarkerFormatType.DECIMAL,
            { event, field, prof, _ -> event.getDouble(field) },
            generic = true
        ),
        TABLE(
            TableMarkerFormat(columns = listOf(TableColumnFormat(), TableColumnFormat())),
            { event, field, prof, tables -> tableFormatter(event, field, prof, tables) },
            generic = true
        ),
        STRING(
            BasicMarkerFormatType.STRING,
            { event, field, prof, _ -> event.getValue<Any?>(field).toString() },
            generic = true
        ),
        ULONG(
            BasicMarkerFormatType.INTEGER,
            { event, field, prof, _ -> event.getLong(field) },
            generic = true
        ),
        DOUBLE(
            BasicMarkerFormatType.DECIMAL,
            { event, field, prof, _ -> event.getDouble(field) },
            generic = true
        ),
        MILLIS(
            BasicMarkerFormatType.MILLISECONDS,
            { event, field, prof, _ -> event.getLong(field) - prof.startTimeMillis }
        ),
        TIMESTAMP(
            BasicMarkerFormatType.INTEGER,
            { event, field, prof, _ ->
                event.getLong(field) - prof.startTimeMillis
            }
        ),
        TIMESPAN(
            BasicMarkerFormatType.DURATION,
            { event, field, prof, _ ->
                event.getLong(field) / 1000_000.0
            }
        ),

        NANOS(BasicMarkerFormatType.MILLISECONDS, { event, field, prof, _ -> event.getLong(field) / 1000.0 }),
        PERCENTAGE(
            BasicMarkerFormatType.PERCENTAGE,
            { event, field, prof, _ -> event.getDouble(field) }
        ),
        EVENT_THREAD(BasicMarkerFormatType.STRING, { event, field, prof, _ ->
            event.getThread(field).let {
                "${it.javaName} (${it.id})"
            }
        }),

        COMPILER_PHASE_TYPE("phase", STRING), COMPILER_TYPE("compiler", STRING), DEOPTIMIZATION_ACTION(
            "action",
            STRING
        ),
        DEOPTIMIZATION_REASON("reason", STRING), FLAG_VALUE_ORIGIN("origin", STRING), FRAME_TYPE(
            "description",
            STRING
        ),
        G1_HEAP_REGION_TYPE("type", STRING), G1_YC_TYPE("type", STRING), GC_CAUSE("cause", STRING), GC_NAME(
            "name",
            STRING
        ),
        GC_THRESHHOLD_UPDATER("updater", STRING), GC_WHEN("when", STRING), INFLATE_CAUSE("cause", STRING), MODIFIERS(
            BasicMarkerFormatType.STRING,
            { event, field, prof, _ ->
                val modInt = event.getInt(field)
                val mods = mutableListOf<String>()
                if (modInt and Modifier.PUBLIC != 0) {
                    mods.add("public")
                }
                if (modInt and Modifier.PRIVATE != 0) {
                    mods.add("private")
                }
                if (modInt and Modifier.PROTECTED != 0) {
                    mods.add("protected")
                }
                if (modInt and Modifier.STATIC != 0) {
                    mods.add("static")
                }
                if (modInt and Modifier.FINAL != 0) {
                    mods.add("final")
                }
                if (modInt and Modifier.SYNCHRONIZED != 0) {
                    mods.add("synchronized")
                }
                if (modInt and Modifier.VOLATILE != 0) {
                    mods.add("volatile")
                }
                if (modInt and Modifier.TRANSIENT != 0) {
                    mods.add("transient")
                }
                if (modInt and Modifier.NATIVE != 0) {
                    mods.add("native")
                }
                if (modInt and Modifier.INTERFACE != 0) {
                    mods.add("interface")
                }
                if (modInt and Modifier.ABSTRACT != 0) {
                    mods.add("abstract")
                }
                if (modInt and Modifier.STRICT != 0) {
                    mods.add("strict")
                }
                mods.joinToString(" ")
            }
        ),
        EPOCH_MILLIS(
            BasicMarkerFormatType.MILLISECONDS,
            { event, field, prof, _ -> event.getLong(field) }
        ),
        BYTES_PER_SECOND(BasicMarkerFormatType.BYTES, { event, field, prof, _ -> event.getDouble(field) }),
        BITS_PER_SECOND(
            BasicMarkerFormatType.BYTES,
            { event, field, prof, _ -> event.getDouble(field) / 8 }
        ),
        METADATA_TYPE("type", STRING), METASPACE_OBJECT_TYPE("type", STRING), NARROW_OOP_MODE(
            "mode",
            STRING
        ),
        NETWORK_INTERFACE_NAME("networkInterface", STRING), OLD_OBJECT_ROOT_TYPE(
            "type",
            STRING
        ),
        OLD_OBJECT_ROOT_SYSTEM("system", STRING), REFERENCE_TYPE("type", STRING), ShenandoahHeapRegionState(
            "state",
            STRING
        ),
        STACKTRACE(BasicMarkerFormatType.INTEGER, { event, field, prof, tables ->
            val st = (event as RecordedEvent).stackTrace
            if (st.frames.isEmpty()) {
                0
            } else {
                mutableMapOf<String, Any>(
                    "stack" to tables.stackTraceTable.getStack(tables, st, MISC_OTHER, prof.config.maxStackTraceFrames),
                    "time" to event.startTime.toMillis()
                )
            }
        }),
        SYMBOL("string", STRING), ThreadState("name", STRING), TICKS(
            BasicMarkerFormatType.INTEGER,
            { event, field, prof, _ -> event.getLong(field) }
        ),
        TICKSPAN(BasicMarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }), VMOperationType(
            "type",
            STRING
        ),
        ZPageTypeType("type", STRING), ZStatisticsCounterType("type", STRING), ZStatisticsSamplerType(
            "type",
            STRING
        ),
        PATH(
            BasicMarkerFormatType.FILE_PATH,
            { event, field, prof, _ -> event.getString(field) }
        ),
        CLASS(
            BasicMarkerFormatType.STRING,
            { event, field, prof, _ -> formatRecordedClass(event.getValue<RecordedClass>(field)) }
        ),
        METHOD(
            BasicMarkerFormatType.STRING,
            { event, field, prof, _ -> formatFunctionWithClass(event.getValue<RecordedMethod>(field)) }
        );

        constructor(childField: String, type: MarkerType, generic: Boolean = false) : this(
            type.type,
            { event: RecordedObject, field: String, prof: FirefoxProfileGenerator, tables: Tables ->
                type.converter(event, field, prof, tables)
            },
            generic = generic
        )

        fun convert(
            event: RecordedObject,
            field: String,
            prof: FirefoxProfileGenerator,
            tables: Tables
        ): Any {
            return try {
                converter(event, field, prof, tables)
            } catch (e: Exception) {
                LOG.throwing("MarkerType", "convert", e)
                TABLE.converter(event, field, prof, tables)
            }
        }

        companion object {
            private val map: MutableMap<String, MarkerType> = mutableMapOf()
            private val map2: MutableMap<Triple<String, String, String?>, MarkerType> = mutableMapOf()

            init {
                values().forEach {
                    for (name in listOf(it.name) + it.aliases) {
                        map[name.lowercase().replace("_", "")] = it
                    }
                }
            }

            fun fromName(field: ValueDescriptor): MarkerType {
                return map2.computeIfAbsent(Triple(field.typeName, field.name, field.contentType)) {
                    val contentTypeResult = field.contentType?.let {
                        map[field.contentType.lowercase().split(".").last()]
                    }
                    val otherResult = map[field.name.lowercase()]
                        ?: map[field.typeName.lowercase().split(".").last()] ?: TABLE
                    val result = if (otherResult != TABLE &&
                        contentTypeResult != null && contentTypeResult.generic
                    ) {
                        otherResult
                    } else {
                        contentTypeResult ?: otherResult
                    }
                    result
                }
            }

            fun tableFormatter(
                event: RecordedObject,
                field: String,
                prof: FirefoxProfileGenerator,
                tables: Tables
            ): Any {
                // idea: transform arbitrary objects into tables:
                // --------
                // key path | value
                // --------
                try {
                    val fields = mutableListOf<Pair<List<String>, String>>()

                    fun fieldName(field: ValueDescriptor) =
                        if (field.label != null && field.label.length < 20) {
                            field.label
                        } else field.name

                    fun addField(path: List<String>, field: ValueDescriptor, base: RecordedObject) {
                        val fieldValue = base.getValue<Any?>(field.name)
                        if (fieldValue is RecordedObject) {
                            fields.add(path + "type" to field.typeName)
                            fieldValue.fields.map { it to fieldValue.getValue<Any?>(it.name) }
                                .filter { it.second != null }
                                .forEach { (field, value) -> addField(path + fieldName(field), field, fieldValue) }
                        } else {
                            fields.add(
                                path to (
                                    fromName(
                                        field
                                    ).converter(base, field.name, prof, tables)
                                    ).toString()
                            )
                        }
                    }
                    addField(emptyList(), event.fields.find { it.name == field }!!, event)
                    return fields.map { (path, value) -> listOf(path.joinToString("."), value) }.toList()
                } catch (e: Exception) {
                    println("Error getting value for field=$field $event: ${e.message}")
                    throw e
                }
            }

            private val LOG = Logger.getLogger("MarkerType")
        }
    }

    data class FieldMapping(val fields: Map<String, String>) {
        operator fun get(key: String) = fields[key] ?: key
    }

    class MarkerSchemaWrapper(val config: Config) {
        val list = mutableListOf<MarkerSchema>()
        val names = mutableMapOf<String, FieldMapping>()
        private val timelineOverviewEvents = setOf<String>()
        private val timelineMemoryEvents = setOf("memory", "gc", "GarbageCollection")
        private val ignoredEvents =
            setOf("jdk.ExecutionSample", "jdk.NativeMethodSample")

        private fun isIgnoredEvent(event: RecordedEvent) = ignoredEvents.contains(event.eventType.name)

        fun isIgnoredField(field: ValueDescriptor) =
            (config.omitEventThreadProperty && field.name == "eventThread") ||
                field.name == "startTime"

        fun isMemoryEvent(event: RecordedEvent) = event.eventType.name.let { name -> name in timelineMemoryEvents }

        fun addForEvent(event: RecordedEvent): FieldMapping? {
            val name = event.eventType.name
            if (isIgnoredEvent(event)) {
                return null
            }
            return names.computeIfAbsent(name) {
                val display = mutableListOf(
                    MarkerDisplayLocation.MARKER_CHART,
                    MarkerDisplayLocation.MARKER_TABLE
                )
                if (name in timelineOverviewEvents) {
                    display.add(MarkerDisplayLocation.TIMELINE_OVERVIEW)
                } else if (isMemoryEvent(event)) {
                    display.add(MarkerDisplayLocation.TIMELINE_MEMORY)
                }
                val mapping = mutableMapOf("stackTrace" to "cause")
                val addedData = listOfNotNull(
                    event.eventType.description?.let {
                        MarkerSchemaDataStatic(
                            "description",
                            event.eventType.description
                        )
                    },
                    MarkerSchemaDataString(
                        key = "startTime",
                        label = "Start Time",
                        format = BasicMarkerFormatType.SECONDS,
                        searchable = true
                    )
                )
                val directData = event.fields.filter { it.name != "stackTrace" && !isIgnoredField(it) }.map { v ->
                    val type = MarkerType.fromName(v)
                    val name = when (v.name) {
                        "type" -> "type "
                        "cause" -> "cause "
                        else -> v.name
                    }
                    if (name != v.name) {
                        mapping[v.name] = name
                    }
                    MarkerSchemaDataString(
                        key = name,
                        label = if (v.label != null && v.label.length < 20) v.label else v.name,
                        format = type.type,
                        searchable = true
                    )
                }
                val data = addedData + directData
                // basic heuristic for finding table label:
                // pick the first three non table fields, prepend with the description
                val directNonTableData = directData.filterNot { it.format is TableMarkerFormat }
                val label = directNonTableData.take(3).joinToString(", ") { "${it.label} = {marker.data.${it.key}}" }
                val combinedLabel = if (directNonTableData.size == 2 && directNonTableData.first().key == "key") {
                    "{marker.data.key} = {marker.data.${directNonTableData.last().key}}"
                } else if (directNonTableData.size <= 1 && event.eventType.description != null) {
                    "${event.eventType.description}: $label"
                } else {
                    label
                }
                val trackConfig = when (name) {
                    "jdk.CPULoad" -> MarkerTrackConfig(
                        label = "CPU Load",
                        height = "large",
                        lines = listOf(
                            MarkerTrackLineConfig(
                                key = "jvmSystem",
                                strokeColor = "orange",
                                type = "line"
                            ),
                            MarkerTrackLineConfig(
                                key = "jvmUser",
                                strokeColor = "blue",
                                type = "line"
                            )
                        ),
                        isPreSelected = true
                    )
                    "jdk.NetworkUtilization" -> MarkerTrackConfig(
                        label = "Network Utilization",
                        height = "large",
                        lines = listOf(
                            MarkerTrackLineConfig(
                                key = "readRate",
                                strokeColor = "blue",
                                type = "line"
                            ),
                            MarkerTrackLineConfig(
                                key = "writeRate",
                                strokeColor = "orange",
                                type = "line"
                            )
                        )
                    )
                    else -> null
                }
                list.add(
                    MarkerSchema(
                        name,
                        tooltipLabel = event.eventType.label ?: name,
                        tableLabel = combinedLabel,
                        display = display,
                        data = data,
                        trackConfig = trackConfig
                    )
                )
                FieldMapping(mapping)
            }
        }

        fun contains(name: String) = names.contains(name)

        fun toMarkerSchemaList() = list
    }

    private fun generateThread(
        markerSchema: MarkerSchemaWrapper,
        thread: RecordedThread?,
        eventsForThread: List<RecordedEvent>,
        // events for the process (without a thread id)
        eventsForProcess: List<RecordedEvent>
    ): Thread {
        val sortedEventsPerType = eventsForThread.groupByType().mapValues {
            if (config.maxMiscSamplesPerThread > -1 && it.key != "jdk.ExecutionSample") it.value.take(
                config.maxMiscSamplesPerThread
            ) else it.value
        }
        val isSystemThread = thread == null
        val start = if (isSystemThread) this.startTimeMillis else {
            eventsForThread.first().startTime.toMillis() - intervalMicros / 1000.0
        }
        val end = if (isSystemThread) this.endTimeMillis else eventsForThread.last().endTime.toMillis()
        val executionSamples = eventsWithTimeRanges(sortedEventsPerType["jdk.ExecutionSample"] ?: emptyList()).let {
            if (config.maxExecutionSamplesPerThread > -1) {
                it.take(config.maxExecutionSamplesPerThread)
            } else {
                it
            }
        }
        val tables = Tables(config, classToUrl = this::classToUrl)
        val samplesTable =
            if (thread == null) SamplesTable(listOf(), time = listOf())
            else generateSamplesTable(tables, executionSamples)
        val hasObjectSamples = sortedEventsPerType["jdk.ObjectAllocationSample"]?.isNotEmpty() ?: false
        return Thread(
            processType = if (isSystemThread) "tab" else "default",
            processStartupTime = if (isSystemThread) 0.0 else start,
            processShutdownTime = end,
            registerTime = sortedEventsPerType["jdk.ThreadStart"]?.getOrNull(0)?.startTime?.toMillis() ?: start,
            unregisterTime = sortedEventsPerType["jdk.ThreadEnd"]?.getOrNull(0)?.startTime?.toMillis() ?: end,
            pausedRanges = listOf(),
            // the global process track has to have type "tab" and name "GeckoMain"
            name = if (isSystemThread) "GeckoMain" else thread!!.javaName ?: thread.osName,
            processName = "Parent Process",
            pid = pid,
            tid = if (isSystemThread) pid else thread!!.javaThreadId,
            samples = samplesTable,
            jsAllocations = if (thread != null && config.enableAllocations &&
                hasObjectSamples
            ) generateJsAllocationsTable(
                tables,
                sortedEventsPerType["jdk.ObjectAllocationSample"] ?: emptyList()
            ) else null,
            nativeAllocations = if (thread != null && config.enableAllocations &&
                config.useNativeAllocViewForAllocations && hasObjectSamples
            ) {
                generateNativeAllocationsTable(
                    tables,
                    sortedEventsPerType["jdk.ObjectAllocationSample"] ?: emptyList()
                )
            } else null,
            markers = if (config.enableMarkers) generateMarkersTable(
                markerSchema,
                tables,
                eventsForThread,
                eventsForProcess
            ) else RawMarkerTable(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            stackTable = tables.stackTraceTable.toStackTable(),
            frameTable = tables.frameTable.toFrameTable(),
            gTable = listOf(),
            funcTable = tables.funcTable.toFuncTable(),
            stringArray = tables.stringTable.toStringTable(),
            resourceTable = tables.resourceTable.toResourceTable(),
            nativeSymbols = NativeSymbolTable(listOf(), listOf(), listOf()),
            sampleLikeMarkersConfig = generateSampleLikeMarkersConfig(
                sortedEventsPerType
            )
        )
    }

    fun generateSampleLikeMarkersConfig(sortedEventsPerType: Map<String, List<RecordedEvent>>) =
        sortedEventsPerType.values.map { it.first() }
            .filter { it.stackTrace != null && !it.isExecutionSample }
            .flatMap {
                generateSampleLikeMarkersConfig(it)
            }

    fun generateSampleLikeMarkersConfig(event: RecordedEvent): List<SampleLikeMarkerConfig> {
        val name = event.eventType.name
        val label = event.eventType.label ?: name
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
        )
    }

    private fun isGCThread(thread: RecordedThread) = thread.osName.startsWith("GC Thread") && thread.javaName == null

    private fun isSystemThread(thread: RecordedThread): Boolean {
        return thread.javaName in listOf(
            "JFR Periodic Tasks",
            "JFR Shutdown Hook",
            "Permissionless thread",
            "Thread Monitor CTRL-C"
        ) || thread.threadGroup?.name == "system" || thread.osName.startsWith("GC Thread") ||
            thread.javaName.startsWith("JFR ") ||
            thread.javaName == "Monitor Ctrl-Break" || "CompilerThread" in thread.javaName ||
            thread.javaName.startsWith("GC Thread") || thread.javaName == "Notification Thread" ||
            thread.javaName == "Finalizer" || thread.javaName == "Attach Listener"
    }

    private fun generateThreads(markerSchema: MarkerSchemaWrapper): Pair<List<Thread>, Set<Thread>> {
        val inThreadEvents = mutableListOf<RecordedEvent>()
        val outThreadEvents = mutableListOf<RecordedEvent>()
        for (event in this.events) {
            (if (event.sampledThreadOrNull == null) outThreadEvents else inThreadEvents).add(event)
        }
        val systemThreads = mutableSetOf<Thread>()
        val normalThreads = inThreadEvents.groupBy { it.sampledThread.javaThreadId }
            .filter {
                it.value.any { e -> e.isExecutionSample } || it.value.first().sampledThread.javaName == "main"
            }.toList().map {
                it to if (it.first == mainThreadId) {
                    -2
                } else if (it.second.first().sampledThread.javaName == "main") {
                    -1
                } else {
                    it.second.first().startTime.toMicros()
                }
            }.sortedBy { (_, sortable) -> sortable }.map { it.first }.let {
                if (config.maxThreads > 0) {
                    it.take(config.maxThreads)
                } else {
                    it
                }
            }.map { (_, eventss) ->
                val t = generateThread(
                    markerSchema,
                    eventss.first().sampledThread,
                    eventss,
                    listOf()
                )
                if (isSystemThread(eventss.first().sampledThread)) {
                    systemThreads.add(t)
                }
                t
            }
        val processThread = generateThread(markerSchema, null, listOf(), outThreadEvents)
        return Pair(listOf(processThread) + normalThreads, systemThreads)
    }

    fun generate(): Profile {
        val markerSchema = MarkerSchemaWrapper(config)
        val (threads, systemThreads) = generateThreads(markerSchema)
        val filteredThreads = threads.filter { it.frameTable.length > 0 || it.name == "GeckoMain" }
        val filteredDefaultVisibleThreadIds = List(
            filteredThreads.filterNot { t ->
                t in systemThreads
            }.size
        ) { index -> index }.take(config.initialVisibleThreads + 1)
        val initialSelectedThreadIndex: List<ThreadIndex> =
            (if (config.selectProcessTrackInitially) listOf(0) else listOf()) +
                filteredDefaultVisibleThreadIds.drop(1).take(config.initialSelectedThreads)

        return Profile(
            libs = listOf(),
            counters = generateMemoryCounters() + generateCPUCounters(),
            threads = filteredThreads,
            meta = profileMeta(
                markerSchema,
                initialVisibleThreads = filteredDefaultVisibleThreadIds,
                initialSelectedThreads = initialSelectedThreadIndex
            )

        )
    }

    private fun profileMeta(
        markerSchema: MarkerSchemaWrapper,
        initialVisibleThreads: List<ThreadIndex>,
        initialSelectedThreads: List<ThreadIndex>
    ): ProfileMeta {
        // TODO: include markers
        return ProfileMeta(
            interval = intervalMicros / 1_000.0,
            startTime = startTimeMillis,
            endTime = endTimeMillis,
            categories = CategoryE.toCategoryList(),
            product = "JVM Application ${jvmInformation.getString("javaArguments")}",
            stackwalk = 0,
            misc = "JVM Version ${jvmInformation.getString("jvmVersion")}",
            oscpu = oscpu,
            platform = platform,
            markerSchema = markerSchema.toMarkerSchemaList(),
            arguments = "jvm=${jvmInformation.getString("jvmArguments")}  --  java=${jvmInformation.getString(
                "javaArguments"
            )}",
            physicalCPUs = cpuInformation.getInt("cores"),
            logicalCPUs = cpuInformation.getInt("hwThreads"),
            sampleUnits = SampleUnits(threadCPUDelta = ThreadCPUDeltaUnit.US),
            importedFrom = jfrFile?.toString(),
            extra = listOf(
                ExtraProfileInfoSection(
                    "Extra Environment Information",
                    listOfNotNull(
                        initialSystemPropertyEntry(),
                        environmentVariablesEntry(),
                        generateSystemProcessEntry()
                    )
                )
            ),
            initialVisibleThreads = initialVisibleThreads,
            initialSelectedThreads = initialSelectedThreads,
            disableThreadOrdering = true
        )
    }

    private fun environmentVariablesEntry() =
        generateTableEntry(
            "jdk.InitialEnvironmentVariable",
            "Environment Variables",
            listOf(CC("Name", "key"), CC("Value", "value"))
        )

    private fun initialSystemPropertyEntry() =
        generateTableEntry(
            "jdk.InitialSystemProperty",
            "System Property",
            listOf(CC("Name", "key"), CC("Value", "value"))
        )

    private fun generateSystemProcessEntry() =
        generateTableEntry(
            "jdk.SystemProcess",
            "System Process",
            listOf(CC("ProcessId", "pid"), CC("Command Line", "commandLine"))
        )

    data class CC(val name: String, val key: String, val type: BasicMarkerFormatType = BasicMarkerFormatType.STRING)

    private fun generateTableEntry(type: String, label: String, columns: List<CC>): ExtraProfileInfoEntry? =
        this.eventsPerType[type]?.let {
            val format = TableMarkerFormat(columns.map { column -> TableColumnFormat(column.type, column.name) })
            val value = JsonArray(
                it.map { e ->
                    JsonArray(columns.map { c -> JsonPrimitive(e.getString(c.key)) })
                }
            )
            return ExtraProfileInfoEntry(label, format, value)
        }

    companion object {
        private val LOG = Logger.getLogger("Converter")
    }
}

// source: https://github.com/Kotlin/kotlinx.serialization/issues/296#issuecomment-1132714147
fun Collection<*>.toJsonElement(): JsonElement = JsonArray(mapNotNull { it.toJsonElement() })

fun Map<*, *>.toJsonElement(): JsonElement = JsonObject(
    mapNotNull {
        (it.key as? String ?: return@mapNotNull null) to it.value.toJsonElement()
    }.toMap()
)

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Map<*, *> -> toJsonElement()
    is Collection<*> -> toJsonElement()
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

@OptIn(ExperimentalSerializationApi::class)
private val jsonFormat = Json {
    prettyPrint = false
    encodeDefaults = true
    explicitNulls = false
}

fun Profile.generateJSON(): String {
    return jsonFormat.encodeToString(this)
}

@OptIn(ExperimentalSerializationApi::class)
fun Profile.encodeToJSONStream(output: OutputStream) {
    jsonFormat.encodeToStream(this, output)
}

fun Profile.encodeToZippedStream(output: OutputStream) {
    GZIPOutputStream(output).use { zipped ->
        encodeToJSONStream(zipped)
    }
}

fun Profile.encodeToJSONStream(): InputStream {
    val input = PipedInputStream()
    val out = PipedOutputStream(input)
    Runnable { encodeToJSONStream(out) }.run()
    return input
}

fun Profile.encodeToZippedStream(): InputStream {
    val input = PipedInputStream()
    val out = PipedOutputStream(input)
    Runnable { encodeToZippedStream(out) }.run()
    return input
}

fun Profile.store(path: Path) {
    Files.newOutputStream(path).use { stream ->
        when (path.extension) {
            "json" -> encodeToJSONStream(stream)
            "gz" -> encodeToZippedStream(stream)
            else -> throw IllegalArgumentException("Unknown file extension: ${path.extension}")
        }
    }
}
