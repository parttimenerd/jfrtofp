package me.bechberger.jfrtofp

import jdk.jfr.ValueDescriptor
import jdk.jfr.consumer.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import java.util.stream.LongStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.streams.toList

fun Instant.toMicros(): Long = epochSecond * 1000000 + nano / 1000

fun Instant.toMillis(): Milliseconds = toMicros() / 1000.0

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

data class Config(
    val addedMemoryProperties: List<MemoryProperty> = listOf(MemoryProperty.USED_HEAP),
    val systemThreadType: String = "vr",
    /** time range of a given sample is at max 2.0 * interval */
    val maxIntervalFactor: Double = 2.0,
    val useNonProjectCategory: Boolean = true,
    val isNonProjectType: (RecordedClass) -> Boolean = { k ->
        listOf("java.", "java.", "kotlin.", "jdk.", "com.google.", "org.apache.").any { k.name.startsWith(it) }
    },
    val enableMarkers: Boolean = true,
    /** an objectsample weigth will be associated with the nearest stack trace
     * or the common prefix stack trace of the two nearest if the minimal time distance is > 0.25 * interval */
    val enableAllocations: Boolean = true,
    /** use native allocations view to show per allocated class allocations */
    val useNativeAllocViewForAllocations: Boolean = true,
    /** maximum number of stack frames to show in tooltip TODO: remove */
    val maxStackTraceFrames: Int = 20, // TODO: implement
    val maxThreads: Int = 100,
    val omitEventThreadProperty: Boolean = true
) {

    fun isRelevantForJava(func: RecordedMethod): Boolean {
        return false
    }

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
        mutableListOf("Interpreted", "Compiled", "Native", "Inlined")
    ),
    NON_PROJECT_JAVA(
        "Java (non-project)",
        "darkgray",
        mutableListOf("Interpreted", "Compiled", "Native", "Inlined")
    ),
    GC("GC", "orange", mutableListOf()),
    CPP("Native", "red", mutableListOf()),

    // JFR related categories
    JFR("Flight Recorder", "lightgrey"),
    JAVA_APPLICATION("Java Application", "red"),
    JAVA_APPLICATION_STATS("Java Application, Statistics", "grey"),
    JVM_CLASSLOADING("Java Virtual Machine, Class Loading", "brown"),
    JVM_CODE_CACHE("Java Virtual Machine, Code Cache", "lightbrown"),
    JVM_COMPILATION_OPT("Java Virtual Machine, Compiler, Optimization", "lightblue"),
    JVM_COMPILATION("Java Virtual Machine, Compiler", "lightblue"),
    JVM_DIAGNOSTICS("Java Virtual Machine, Diagnostics", "lightgrey"),
    JVM_FLAG("Java Virtual Machine, Flag", "lightgrey"),
    JVM_GC_COLLECTOR("Java Virtual Machine, GC, Collector", "orange"),
    JVM_GC_CONF("Java Virtual Machine, GC, Configuration", "lightgrey"),
    JVM_GC_DETAILED("Java Virtual Machine, GC, Detailed", "lightorange"),
    JVM_GC_HEAP("Java Virtual Machine, GC, Heap", "lightorange"),
    JVM_GC_METASPACE("Java Virtual Machine, GC, Metaspace", "lightorange"), // add another category for errors (OOM)
    JVM_GC_PHASES("Java Virtual Machine, GC, Phases", "lightorange"),
    JVM_GC_REFERENCE("Java Virtual Machine, GC, Reference", "lightorange"),
    JVM_INTERNAL("Java Virtual Machine, Internal", "lightgrey"),
    JVM_PROFILING("Java Virtual Machine, Profiling", "lightgrey"),
    JVM_RUNTIME_MODULES("Java Virtual Machine, Runtime, Modules", "lightgrey"),
    JVM_RUNTIME_SAFEPOINT("Java Virtual Machine, Runtime, Safepoint", "yellow"),
    JVM_RUNTIME_TABLES("Java Virtual Machine, Runtime, Tables", "lightgrey"),
    JVM_RUNTIME("Java Virtual Machine, Runtime", "green"),
    JVM("Java Virtual Machine", "lightgrey"),
    OS_MEMORY("Operating System, Memory", "lightgrey"),
    OS_NETWORK("Operating System, Network", "lightgrey"),
    OS_PROCESS("Operating System, Processor", "lightgrey"),
    OS("Operating System", "lightgrey");

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
    }
}

/** generates JSON for the profile.firefox.com viewer */
class FirefoxProfileGenerator(
    val events: List<RecordedEvent>, val config: Config, intervalSeconds: Double? = null, val jfrFile: Path? = null
) {

    private val eventsPerType: Map<String, List<RecordedEvent>>

    private fun List<RecordedEvent>.groupByType() =
        groupBy { if (it.eventType.name == "jdk.NativeMethodSample") "jdk.ExecutionSample" else it.eventType.name }

    init {
        eventsPerType = events.groupByType()
    }

    private val intervalMicros =
        round((intervalSeconds ?: (eventsPerType["jdk.ExecutionSample"]!!.take(1000).groupBy { it.sampledThread }
            .estimateIntervalInMicros() / 1_000_000.0)) * 1_000_000.0)

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
        get() = eventsPerType["jdk.ShutDown"]?.get(0)?.startTime?.toMillis() ?: events.last().startTime.toMillis()

    val pid
        get() = jvmInformation.getLong("pid")

    private val executionSamples
        get() = eventsPerType["jdk.ExecutionSample"]!!
    private val extensionSamplesTreeMap by lazy {
        executionSamples.map { it.startTime.toMillis() to it }.toMap(TreeMap())
    }

    val platform
        get() = eventsPerType["jdk.OSInformation"]?.get(0)?.getString("osVersion")?.let {
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
        get() = eventsPerType["jdk.OSInformation"]?.get(0)?.getString("osVersion")?.let { it ->
            it.split("uname:")[1].split("\n").get(0)?.let { distId ->
                eventsPerType["jdk.CPUInformation"]?.get(0)?.getString("cpu")?.split(" ")?.get(0)?.let {
                    "$it $distId"
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
            loads.lowerEntry(timeInMicros)?.value?.let { it: Float ->
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
            sampleGroups = listOf(SampleGroup(0, prop.getValues(this.events).let { timed ->
                var ovCount = 0
                CounterSamplesTable(time = timed.map { (t, _) -> t }, number = timed.map { -1 },
                    count = timed.mapIndexed { i, (_, value) ->
                        if (i == 0) {
                            value
                        } else {
                            value - timed[i - 1].second
                        }
                    })
            }))
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
                sampleGroups = listOf(SampleGroup(0, CounterSamplesTable(
                    time = cpuLoads.map { it.startTime.toMillis() },
                    number = List(cpuLoads.size) { 0 },
                    count = cpuLoads.map { ((it.getFloat("jvmUser") + it.getFloat("jvmSystem")) * 1000000.0).roundToLong() }
                )))
            )
        )
    } ?: generateGenericCPUCounters() // TODO also use if not enough info

    private fun generateGenericCPUCounters(): List<Counter> {
        val slices = LongStream.range((startTimeMillis / 100).roundToLong(), (endTimeMillis / 100).roundToLong()).mapToDouble {
            it * 100.0
        }.toList()
        return listOf(
            Counter(
                name = "processCPU",
                category = "CPU",
                description = "Process CPU utilization",
                pid = pid,
                mainThreadIndex = 0,
                sampleGroups = listOf(SampleGroup(0, CounterSamplesTable(
                    time = slices,
                    number = List(slices.size) { 0 },
                    count = List(slices.size) { 10 }
                )))
            )
        )
    }

    data class EventWithTimeRange(
        val event: RecordedEvent, val start: Milliseconds, val durationBefore: Milliseconds
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
                val wholeName = func.type.name + "#" + func.name + func.descriptor
                names.add(tables.getString(wholeName))
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
    ) {
        fun getString(string: String) = stringTable.getString(string)
    }

    class FuncTableWrapper {

        val map = mutableMapOf<RecordedMethod, IndexIntoFuncTable>()
        val names = mutableListOf<IndexIntoStringTable>()
        private val isJss = mutableListOf<Boolean>()
        private val relevantForJss = mutableListOf<Boolean>()
        private val resourcess = mutableListOf<IndexIntoResourceTable>() // -1 if not present
        val fileNames = mutableListOf<IndexIntoStringTable?>()
        private val miscFunctions = mutableMapOf<String, IndexIntoFuncTable>()

        fun getFunction(tables: Tables, func: RecordedMethod, isJava: Boolean): IndexIntoFuncTable {
            return map.computeIfAbsent(func) {
                names.add(tables.getString(func.type.className + "#" + func.name))
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
            name = names, isJS = isJss, relevantForJS = relevantForJss, resource = resourcess, fileName = fileNames
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

        fun getFrame(tables: Tables, frame: RecordedFrame, isInGCThread: Boolean): IndexIntoFrameTable {
            val func = tables.funcTable.getFunction(tables, frame.method, frame.isJavaFrame)
            val line = if (frame.lineNumber == -1) null else frame.lineNumber
            return map.computeIfAbsent(func to line) {
                val (category, sub) = if (isInGCThread) {
                    CategoryE.GC.sub(frame.type)
                } else if (tables.config.useNonProjectCategory && frame.isJavaFrame && tables.config.isNonProjectType(
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
            isInGCThread: Boolean
        ): IndexIntoStackTable {
            val list = getHashedFrameList(tables, stackTrace, isInGCThread)
            val list2 = getHashedFrameList(tables, stackTrace2, isInGCThread)
            var commonCount = min(list.size, list2.size)
            for (i in 0 until min(list.size, list2.size)) {
                if (list[i] != list2[i]) {
                    commonCount = i
                }
            }
            return getStack(tables, HashedList(list.array, 0, commonCount))
        }

        private fun getHashedFrameList(tables: Tables, stackTrace: RecordedStackTrace, isInGCThread: Boolean) =
            HashedList(stackTrace.frames.reversed().map { tables.frameTable.getFrame(tables, it, isInGCThread) })

        fun getStack(tables: Tables, stackTrace: RecordedStackTrace, isInGCThread: Boolean): IndexIntoStackTable {
            return getStack(tables, getHashedFrameList(tables, stackTrace, isInGCThread))
        }

        fun getStack(tables: Tables, stackTrace: HashedList<IndexIntoFrameTable>): IndexIntoStackTable {
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
                    HashedList(stackTrace.array, stackTrace.start, stackTrace.end - 1)
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
            category: CategoryE,
            subcategory: String,
            isNative: Boolean
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
        tables: Tables, executionSamples: List<EventWithTimeRange>
    ): SamplesTable {
        val stack = mutableListOf<IndexIntoStackTable?>()
        val time = mutableListOf<Milliseconds>()
        // in ms,    delta[i] = [time[i] - time[i - 1]] * [usage in this interval]
        val threadCPUDelta = mutableListOf<Milliseconds?>()
        for ((sample, start, durationBefore) in executionSamples) {
            time.add(start)
            threadCPUDelta.add(
                durationBefore * 1000 * getCpuLoad(
                    sample.sampledThread, sample.startTime.toMicros() - (durationBefore * 1000).toLong() / 2
                )
            )
            stack.add(sample.stackTrace.let {
                tables.stackTraceTable.getStack(
                    tables,
                    it,
                    isGCThread(sample.sampledThread)
                )
            })
        }
        return SamplesTable(
            stack = stack, time = time, threadCPUDelta = threadCPUDelta
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
        tables: Tables, allocationSampleEvents: List<RecordedEvent>,
        inGCThread: Boolean
    ): JsAllocationsTable {
        val time = mutableListOf<Milliseconds>()
        val className = mutableListOf<String>()
        val weight = mutableListOf<Bytes>()
        val stack = mutableListOf<IndexIntoStackTable?>()
        for (sample in allocationSampleEvents) {
            time.add(sample.startTime.toMillis())
            className.add(sample.getClass("objectClass").name)
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
                    tables.stackTraceTable.getStack(tables, nearestSampleEvent.stackTrace, inGCThread)
                } else if (diff <= intervalMicros) { // take the common prefix stack
                    tables.stackTraceTable.getCommonStack(
                        tables,
                        lowEntry.value.stackTrace,
                        highEntry.value.stackTrace,
                        inGCThread
                    )
                } else {
                    null
                }
            } ?: tables.stackTraceTable.getMiscStack(tables, "<unknown>", CategoryE.OTHER, "Unknown", inGCThread)
            stack.add(stackTraceIndex)
        }
        return JsAllocationsTable(
            time = time, className = className, weight = weight, stack = stack
        )
    }

    /** like generateJsAllocationsTable, but models the allocated objects in the NativeAllocationTable */
    private fun generateNativeAllocationsTable(
        tables: Tables, allocationSampleEvents: List<RecordedEvent>,
        inGCThread: Boolean
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
                    sample.getClass("objectClass").name,
                    CategoryE.OTHER,
                    "Allocation",
                    false
                )
            )
        }
        return NativeAllocationsTable(
            time = time, weight = weight, stack = stack
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
        markerSchema: MarkerSchemaWrapper, table: Tables, events: List<RecordedEvent>,
        eventsForProcess: List<RecordedEvent>
    ): RawMarkerTable {
        return RawMarkerTableWrapper().also { raw ->
            events.forEach { raw.process(markerSchema, table, it, this) }
            eventsForProcess.forEach { raw.process(markerSchema, table, it, this) }
        }.toRawMarkerTable()
    }

    enum class MarkerType(
        val type: MarkerFormatType,
        val converter: (event: RecordedObject, field: String, prof: FirefoxProfileGenerator, tables: Tables) -> Any = { event, field, prof, tables ->
            event.getValue<Any?>(field).toString()
        }
    ) {
        // look first for contentType, then for field name and last for actual type
        BOOLEAN(MarkerFormatType.STRING),
        BYTES(MarkerFormatType.BYTES, { event, field, prof, _ -> event.getLong(field) }),
        UBYTE(MarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }),
        UNSIGNED(MarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }),
        INT(MarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }),
        UINT(MarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }),
        USHORT(MarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }),
        LONG(MarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }),
        FLOAT(MarkerFormatType.DECIMAL, { event, field, prof, _ -> event.getDouble(field) }),
        TABLE(MarkerFormatType.STRING, { event, field, prof, tables ->
            try {
                val obj = event.getValue<RecordedObject>(field)
                obj.fields.filter { obj.getValue<Any>(it.name) != null }.map {
                    it.name to fromName(
                        it
                    ).converter(obj, it.name, prof, tables)
                }.map { "${it.first}=${it.second}" }.joinToString("\n")
            } catch (e: Exception) {
                println("Error getting value for field=$field $event: ${e.message}")
                throw e
            }
        }),
        STRING(MarkerFormatType.STRING, { event, field, prof, _ -> event.getValue<Any?>(field).toString() }),
        ULONG(MarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }),
        DOUBLE(MarkerFormatType.DECIMAL, { event, field, prof, _ -> event.getDouble(field) }),
        MILLIS(MarkerFormatType.MILLISECONDS, { event, field, prof, _ -> event.getLong(field) }),
        NANOS(MarkerFormatType.NANOSECONDS, { event, field, prof, _ -> event.getLong(field) }),
        PERCENTAGE(MarkerFormatType.PERCENTAGE, { event, field, prof, _ -> event.getDouble(field) }),
        EVENT_THREAD(MarkerFormatType.STRING, { event, field, prof, _ ->
            event.getThread(field).let {
                "${it.javaName} (${it.id})"
            }
        }),

        COMPILER_PHASE_TYPE("phase", STRING),
        COMPILER_TYPE("compiler", STRING),
        DEOPTIMIZATION_ACTION("action", STRING),
        DEOPTIMIZATION_REASON("reason", STRING),
        FLAG_VALUE_ORIGIN("origin", STRING),
        FRAME_TYPE("description", STRING),
        G1_HEAP_REGION_TYPE("type", STRING),
        G1_YC_TYPE("type", STRING),
        GC_CAUSE("cause", STRING),
        GC_NAME("name", STRING),
        GC_THRESHHOLD_UPDATER("updater", STRING),
        GC_WHEN("when", STRING),
        INFLATE_CAUSE("cause", STRING),
        MODIFIERS(MarkerFormatType.STRING, { event, field, prof, _ ->
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
        }),
        EPOCH_MILLIS(MarkerFormatType.MILLISECONDS, { event, field, prof, _ -> event.getLong(field) }),
        BYTES_PER_SECOND(MarkerFormatType.BYTES, { event, field, prof, _ -> event.getDouble(field) }),
        BITS_PER_SECOND(MarkerFormatType.BYTES, { event, field, prof, _ -> event.getDouble(field) / 8 }),
        METADATA_TYPE("type", STRING),
        METASPACE_OBJECT_TYPE("type", STRING),
        NARROW_OOP_MODE("mode", STRING),
        NETWORK_INTERFACE_NAME("networkInterface", STRING),
        OLD_OBJECT_ROOT_TYPE("type", STRING),
        OLD_OBJECT_ROOT_SYSTEM("system", STRING),
        REFERENCE_TYPE("type", STRING),
        ShenandoahHeapRegionState("state", STRING),
        STACKTRACE(MarkerFormatType.INTEGER, { event, field, prof, tables ->
            val st = (event as RecordedEvent).stackTrace
            if (st.frames.isEmpty()) {
                0
            } else {
                mutableMapOf<String, Any>("stack" to 10, "time" to event.startTime.toMillis())
            }
        }),
        SYMBOL("string", STRING),
        ThreadState("name", STRING),
        TICKS(MarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }),
        TICKSPAN(MarkerFormatType.INTEGER, { event, field, prof, _ -> event.getLong(field) }),
        VMOperationType("type", STRING),
        ZPageTypeType("type", STRING),
        ZStatisticsCounterType("type", STRING),
        ZStatisticsSamplerType("type", STRING),
        PATH(MarkerFormatType.FILE_PATH, { event, field, prof, _ -> event.getString(field) }),
        CLASS(
            MarkerFormatType.STRING,
            { event, field, prof, _ -> classToString(event.getValue<RecordedObject>(field)) }),
        METHOD(
            MarkerFormatType.STRING,
            { event, field, prof, _ -> methodToString(event.getValue<RecordedObject>(field)) });

        constructor(childField: String, type: MarkerType) :
                this(
                    type.type,
                    { event: RecordedObject, field: String, prof: FirefoxProfileGenerator, tables: Tables ->
                        type.converter(event, field, prof, tables)
                    })

        fun convert(event: RecordedObject, field: String, prof: FirefoxProfileGenerator, tables: Tables): Any {
            return try {
                converter(event, field, prof, tables)
            } catch (e: Exception) {
                e.printStackTrace()
                TABLE.converter(event, field, prof, tables)
            }
        }

        companion object {
            private val map: MutableMap<String, MarkerType> = mutableMapOf()
            private val map2: MutableMap<Triple<String, String, String?>, MarkerType> = mutableMapOf()

            init {
                values().forEach { map[it.name.lowercase().replace("_", "")] = it }
            }

            fun fromName(field: ValueDescriptor): MarkerType {
                return map2.computeIfAbsent(Triple(field.typeName, field.name, field.contentType)) {
                    field.contentType?.let { map[field.contentType.lowercase().split(".").last()] }
                        ?: map[field.name.lowercase()] ?: map[field.typeName.lowercase().split(".").last()] ?: TABLE
                }
            }

            internal fun classToString(klass: RecordedObject): String {
                return if (klass.getValue<RecordedObject>("package") == null) {
                    klass.getValue("name")
                } else {
                    klass.getValue<RecordedObject>("package")
                        .getValue<String>("name") + "." + klass.getValue<String>("name")
                }
            }

            internal fun methodToString(method: RecordedObject) =
                "${classToString(method.getValue("type"))}.${method.getString("name")}.${
                    method.getString(
                        "descriptor"
                    )
                }"
        }
    }

    data class FieldMapping(val fields: Map<String, String>) {
        operator fun get(key: String) = fields[key] ?: key
    }

    class MarkerSchemaWrapper(val config: Config) {
        val list = mutableListOf<MarkerSchema>()
        val names = mutableMapOf<String, FieldMapping>()
        private val timelineOverviewEvents = setOf<String>()
        private val timelineMemoryEvents = setOf<String>()
        private val ignoredEvents =
            setOf("jdk.ObjectAllocationSample", "jdk.OldObjectSample", "jdk.ExecutionSample", "jdk.NativeMethodSample")

        private fun isIgnoredEvent(event: RecordedEvent) = ignoredEvents.contains(event.eventType.name)

        fun isIgnoredField(field: ValueDescriptor) = config.omitEventThreadProperty && field.name == "eventThread"

        fun addForEvent(event: RecordedEvent): FieldMapping? {
            val name = event.eventType.name
            if (isIgnoredEvent(event)) {
                return null
            }
            return names.computeIfAbsent(name) {
                val display = mutableListOf(
                    MarkerDisplayLocation.MARKER_CHART, MarkerDisplayLocation.MARKER_TABLE
                )
                if (name in timelineOverviewEvents) {
                    display.add(MarkerDisplayLocation.TIMELINE_OVERVIEW)
                } else if (name in timelineMemoryEvents || "memory" in name.lowercase() || "gc" in name.lowercase() || "GarbageCollection" in name) {
                    display.add(MarkerDisplayLocation.TIMELINE_MEMORY)
                }
                val mapping = mutableMapOf("stackTrace" to "cause")
                val data = event.fields.filter { it.name != "stackTrace" && !isIgnoredField(it) }.map { v ->
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
                        label = if (v.description != null && v.description.length < 20) v.description else v.name,
                        format = type.type,
                        searchable = true
                    )
                }
                list.add(MarkerSchema(name, display = display, data = data))
                FieldMapping(mapping)
            }
        }

        fun contains(name: String) = names.contains(name)

        fun toMarkerSchemaList() = list
    }

    private fun generateThread(
        markerSchema: MarkerSchemaWrapper, thread: RecordedThread, eventsForThread: List<RecordedEvent>,
        // events for the process (without a thread id)
        eventsForProcess: List<RecordedEvent>
    ): Thread {
        val sortedEventsPerType = eventsForThread.groupByType()
        val start = eventsForThread.first().startTime.toMillis() - intervalMicros / 1000.0
        val end = eventsForThread.last().endTime.toMillis()
        val executionSamples = eventsWithTimeRanges(sortedEventsPerType["jdk.ExecutionSample"] ?: emptyList())
        val tables = Tables(config)
        val samplesTable = generateSamplesTable(tables, executionSamples)
        val isMainThread = thread.javaThreadId == this.mainThreadId
        val hasObjectSamples = sortedEventsPerType["jdk.ObjectAllocationSample"]?.isNotEmpty() ?: false
        return Thread(
            processType = if (isSystemThread(thread)) config.systemThreadType else "default",
            processStartupTime = if (isMainThread) 0.0 else start,
            processShutdownTime = end,
            registerTime = sortedEventsPerType["jdk.ThreadStart"]?.get(0)?.startTime?.toMillis() ?: start,
            unregisterTime = sortedEventsPerType["jdk.ThreadEnd"]?.get(0)?.startTime?.toMillis() ?: end,
            pausedRanges = listOf(),
            name = thread.javaName ?: thread.osName,
            pid = pid,
            tid = thread.javaThreadId,
            samples = samplesTable,
            jsAllocations = if (config.enableAllocations && hasObjectSamples) generateJsAllocationsTable(
                tables,
                sortedEventsPerType["jdk.ObjectAllocationSample"] ?: emptyList(),
                isGCThread(thread)
            ) else null,
            nativeAllocations = if (config.enableAllocations &&
                config.useNativeAllocViewForAllocations && hasObjectSamples) generateNativeAllocationsTable(
                tables,
                sortedEventsPerType["jdk.ObjectAllocationSample"] ?: emptyList(),
                isGCThread(thread)
            ) else null,
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
            nativeSymbols = NativeSymbolTable(listOf(), listOf(), listOf())
        )
    }

    private fun isGCThread(thread: RecordedThread) = thread.osName.startsWith("GC Thread") && thread.javaName == null

    private fun isSystemThread(thread: RecordedThread): Boolean {
        return thread.javaName in listOf(
            "JFR Periodic Tasks", "JFR Shutdown Hook", "Permissionless thread", "Thread Monitor CTRL-C"
        ) || thread.threadGroup?.name == "system" || thread.osName.startsWith("GC Thread")
    }

    private fun generateThreads(markerSchema: MarkerSchemaWrapper): List<Thread> {
        val inThreadEvents = mutableListOf<RecordedEvent>()
        val outThreadEvents = mutableListOf<RecordedEvent>()
        for (event in this.events) {
            (if (event.sampledThreadOrNull == null) outThreadEvents else inThreadEvents).add(event)
        }
        return inThreadEvents.groupBy { it.sampledThread.javaThreadId }
            .filter { it.value.any { e -> e.isExecutionSample } }.toList()
            .sortedBy {
                if (it.first == mainThreadId) {
                    0
                } else {
                    it.first
                }
            }.let {
                if (config.maxThreads > 0) {
                    it.take(config.maxThreads)
                } else {
                    it
                }
            }
            .map { (id, eventss) ->
                generateThread(
                    markerSchema,
                    eventss.first().sampledThread,
                    eventss,
                    (if (id == mainThreadId) outThreadEvents else listOf())
                )
            }
    }

    fun generate(): Profile {
        val markerSchema = MarkerSchemaWrapper(config)
        return Profile(
            libs = listOf(),
            counters = generateMemoryCounters() + generateCPUCounters(),
            threads = generateThreads(markerSchema).filter { it.frameTable.length > 0 },
            meta = profileMeta(markerSchema)
        )
    }

    private val jsonFormat = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun generateJSON(): String {
        return jsonFormat.encodeToString(generate())
    }

    private fun profileMeta(markerSchema: MarkerSchemaWrapper): ProfileMeta {
        // TODO: include markers
        return ProfileMeta(
            interval = intervalMicros / 1000.0,
            startTime = startTimeMillis,
            categories = CategoryE.toCategoryList(),
            product = "JVM Application on ${jvmInformation.getString("jvmName")} ${jvmInformation.getString("jvmName")}",
            stackwalk = 1,
            misc = "JVM version ${jvmInformation.getString("jvmVersion")}",
            oscpu = oscpu,
            platform = platform,
            markerSchema = markerSchema.toMarkerSchemaList(),
            sourceURL = "jvm=${jvmInformation.getString("jvmArguments")}  --  java=${jvmInformation.getString("javaArguments")}",
            physicalCPUs = cpuInformation.getInt("cores"),
            logicalCPUs = cpuInformation.getInt("hwThreads"),
            sampleUnits = SampleUnits(threadCPUDelta = ThreadCPUDeltaUnit.US),
            importedFrom = jfrFile?.toString()
        )
    }
}

private val RecordedEvent.isSampledThreadCorrectProperty
    get() = eventType.name == "jdk.NativeMethodSample" || eventType.name == "jdk.ExecutionSample"
val RecordedEvent.sampledThread: RecordedThread
    get() = sampledThreadOrNull!!
val RecordedEvent.sampledThreadOrNull: RecordedThread?
    get() = if (isSampledThreadCorrectProperty) getThread("sampledThread") else thread

// source: https://github.com/Kotlin/kotlinx.serialization/issues/296#issuecomment-1132714147
fun Collection<*>.toJsonElement(): JsonElement = JsonArray(mapNotNull { it.toJsonElement() })

fun Map<*, *>.toJsonElement(): JsonElement = JsonObject(
    mapNotNull {
        (it.key as? String ?: return@mapNotNull null) to it.value.toJsonElement()
    }.toMap(),
)

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Map<*, *> -> toJsonElement()
    is Collection<*> -> toJsonElement()
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

