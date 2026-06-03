package me.bechberger.jfrtofp.processor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToStream
import me.bechberger.jfrtofp.types.FrameTable
import me.bechberger.jfrtofp.types.FuncTable
import me.bechberger.jfrtofp.types.IndexIntoCategoryList
import me.bechberger.jfrtofp.types.IndexIntoFrameTable
import me.bechberger.jfrtofp.types.IndexIntoFuncTable
import me.bechberger.jfrtofp.types.IndexIntoResourceTable
import me.bechberger.jfrtofp.types.IndexIntoSourceTable
import me.bechberger.jfrtofp.types.IndexIntoStackTable
import me.bechberger.jfrtofp.types.IndexIntoStringTable
import me.bechberger.jfrtofp.types.IndexIntoSubcategoryListForCategory
import me.bechberger.jfrtofp.types.MarkerPhase
import me.bechberger.jfrtofp.types.Milliseconds
import me.bechberger.jfrtofp.types.RawMarkerTable
import me.bechberger.jfrtofp.types.ResourceTable
import me.bechberger.jfrtofp.types.SamplesTable
import me.bechberger.jfrtofp.types.SourceTable
import me.bechberger.jfrtofp.types.StackTable
import me.bechberger.jfrtofp.types.resourceTypeEnum
import me.bechberger.jfrtofp.util.BasicJSONGenerator
import me.bechberger.jfrtofp.util.ByteCodeHelper
import me.bechberger.jfrtofp.util.HashedList
import me.bechberger.jfrtofp.util.Percentage
import me.bechberger.jfrtofp.util.StringTableWrapper
import me.bechberger.jfrtofp.util.jsonFormat
import me.bechberger.jfrtofp.util.toJsonElement
import me.bechberger.jfrtofp.util.quantize
import java.time.Instant

/** Wraps the [SamplesTable] class */
class SamplesTableWrapper(val tables: Tables, private val spiller: SampleSpiller? = null) {
    data class Item(val stack: IndexIntoStackTable, val time: Milliseconds)

    private val items: MutableList<Item> = if (spiller == null) mutableListOf() else mutableListOf()
    private var itemCount: Int = 0

    fun processEvent(event: ParsedJFREvent) {
        val cap = tables.config.maxExecutionSamplesPerThread
        if (cap >= 0 && itemCount >= cap) return
        val stack = tables.getStack(event)
        val time = event.startMs
        itemCount++
        if (spiller != null) {
            spiller.add(stack, time)
        } else {
            items.add(Item(stack, time))
        }
    }

    val count: Int get() = itemCount

    fun toSamplesTable(cpuLoad: (Milliseconds) -> Percentage): SamplesTable {
        val sortedItems = items.sortedBy { it.time }
        val time = sortedItems.map { it.time.quantize(tables.config.timestampDecimals) }
        val stack = sortedItems.map { it.stack }

        val threadCPUDelta: MutableList<Milliseconds> = mutableListOf(0.0)
        for (i in 1 until time.size) {
            if (i == time.size - 1) {
                threadCPUDelta.add(0.0)
            } else {
                threadCPUDelta.add(
                    ((time[i] - time[i - 1]) * 1000.0 * cpuLoad(time[i])).quantize(tables.config.timestampDecimals),
                )
            }
        }
        return SamplesTable(
            stack = stack,
            time = time,
            threadCPUDelta = threadCPUDelta,
        )
    }

    fun write(
        json: BasicJSONGenerator,
        cpuLoad: (Milliseconds) -> Percentage,
    ) {
        if (spiller != null) {
            writeFromSpiller(json, cpuLoad)
        } else {
            val samplesTable = toSamplesTable(cpuLoad)
            writeArrays(json, samplesTable.stack, samplesTable.time, samplesTable.threadCPUDelta!!)
        }
    }

    private fun writeFromSpiller(json: BasicJSONGenerator, cpuLoad: (Milliseconds) -> Percentage) {
        spiller!!.close()
        val cap = spiller.count.toInt().coerceAtMost(Int.MAX_VALUE)
        val stackList = ArrayList<Int?>(cap)
        val timeList = ArrayList<Double>(cap)
        spiller.replay { stack, rawTime ->
            stackList.add(stack)
            timeList.add(rawTime.quantize(tables.config.timestampDecimals))
        }

        val threadCPUDelta = ArrayList<Double?>(timeList.size)
        threadCPUDelta.add(0.0)
        for (i in 1 until timeList.size) {
            threadCPUDelta.add(
                if (i == timeList.size - 1) 0.0
                else ((timeList[i] - timeList[i - 1]) * 1000.0 * cpuLoad(timeList[i])).quantize(tables.config.timestampDecimals)
            )
        }
        writeArrays(json, stackList, timeList, threadCPUDelta)
    }

    private fun writeArrays(json: BasicJSONGenerator, stack: List<Int?>, time: List<Double>, threadCPUDelta: List<Double?>) {
        json.writeStartObject()
        json.writeNumberArrayField("stack", stack)
        json.writeQuantizedNumberArrayField("time", time, tables.config.timestampDecimals)
        json.writeQuantizedNumberArrayField("threadCPUDelta", threadCPUDelta, tables.config.timestampDecimals)
        if (tables.config.emitEventDelay) {
            json.writeSingleValueArrayField("eventDelay", "0.0", stack.size)
        }
        json.writeSimpleField("weightType", "samples")
        json.writeSimpleField("length", stack.size, last = true)
        json.writeEndObject()
    }
}

/** Combines the different table wrappers */
data class Tables(
    val config: Config,
    val basicInformation: BasicInformation,
    val markerSchema: MarkerSchemaProcessor,
    val classToUrl: (String, String) -> String? = { _, _ -> null },
    val defaultUrl: String? = null,
) {
    val stringTable: StringTableWrapper = StringTableWrapper()
    val resourceTable: ResourceTableWrapper = ResourceTableWrapper(this)
    val frameTable: FrameTableWrapper = FrameTableWrapper(this)
    val stackTraceTable: StackTableWrapper = StackTableWrapper(this)
    val sourceTable: SourceTableWrapper = SourceTableWrapper(this)
    val funcTable: FuncTableWrapper = FuncTableWrapper(this)

    fun getString(string: String) = synchronized(this) { stringTable[string] }

    fun getResource(key: MethodKey, isJava: Boolean) = synchronized(this) { resourceTable.getResource(key, isJava) }

    fun getFunction(key: MethodKey, isJava: Boolean, lineNumber: Int) = synchronized(this) { funcTable.getFunction(key, isJava, lineNumber) }

    fun getMiscFunction(name: String, isNative: Boolean) = synchronized(this) { funcTable.getMiscFunction(name, isNative) }

    fun getFrame(key: MethodKey, lineNumber: Int, isJavaFrame: Boolean, frameType: String) =
        synchronized(this) { frameTable.getFrame(key, lineNumber, isJavaFrame, frameType) }

    fun getMiscFrame(
        name: String,
        category: CategoryE,
        subcategory: String,
        isNative: Boolean,
    ) = synchronized(this) { frameTable.getMiscFrame(name, category, subcategory, isNative) }

    /** Build stack index from the parallel frame arrays in a [ParsedJFREvent]. */
    fun getStack(event: ParsedJFREvent): IndexIntoStackTable = getStack(event, Int.MAX_VALUE)

    fun getStack(event: ParsedJFREvent, maxStackTraceFrames: Int): IndexIntoStackTable =
        synchronized(this) { stackTraceTable.getStack(event, maxStackTraceFrames) }

    fun getStack(
        stackTrace: HashedFrameList,
        maxStackTraceFrames: Int = Int.MAX_VALUE,
    ) = synchronized(this) { stackTraceTable.getStack(stackTrace, maxStackTraceFrames) }

    fun getMiscStack(
        name: String,
        category: CategoryE = CategoryE.MISC,
        subcategory: String = "Other",
        isNative: Boolean = false,
    ) = synchronized(this) { stackTraceTable.getMiscStack(name, category, subcategory, isNative) }
}

class RawMarkerTableWrapper(
    val tables: Tables,
    val basicInformation: BasicInformation,
    val markerSchema: MarkerSchemaProcessor,
    private val spiller: MarkerSpiller? = null,
) {
    data class Item(
        val name: IndexIntoStringTable,
        val startTime: Milliseconds?,
        val endTime: Milliseconds?,
        val phase: MarkerPhase,
        val category: IndexIntoCategoryList,
        val data: Map<String, JsonElement>,
    )

    private val items: MutableList<Item> = mutableListOf()
    private var itemCount: Int = 0
    // Reused per-thread: avoids allocating a new ByteArrayOutputStream per marker when spilling
    private val reuseableBaos = java.io.ByteArrayOutputStream(512)
    private val reuseableJsonGen = BasicJSONGenerator(reuseableBaos)

    @OptIn(ExperimentalSerializationApi::class)
    fun processEvent(event: ParsedJFREvent) {
        val cap = tables.config.maxMiscSamplesPerThread
        if (cap >= 0 && itemCount >= cap) return
        val fieldMapping: MarkerSchemaFieldMapping = markerSchema[event.typeName] ?: return
        val name = tables.getString(event.typeName)
        val startTime = event.startMs.quantize(tables.config.timestampDecimals)
        val endTime = event.endMs.quantize(tables.config.timestampDecimals)
        val phase = if (event.endMs == event.startMs) 0 else 1
        val category = CategoryE.fromName(fieldMapping.categoryName ?: "Other").index
        val startTimeInstant = event.startTime
        itemCount++

        if (spiller != null) {
            // Fast path: write JSON bytes directly without building Map<String, JsonElement>
            reuseableBaos.reset()
            val gen = reuseableJsonGen
            gen.writeStartObject()
            var firstField = true
            for (field in fieldMapping.fields) {
                val rawValue: Any? = if (field.sourceName == "stackTrace" && field.type == MarkerType.STACKTRACE) {
                    tables.getStack(event, Int.MAX_VALUE)
                } else {
                    field.getValue(event)
                }
                if (rawValue == null) continue
                if (tables.config.dropSentinelValues && rawValue is Long && (rawValue == Long.MIN_VALUE || rawValue == Long.MAX_VALUE)) continue
                val converted = field.type.convert(tables, startTimeInstant, rawValue)
                if (!firstField) gen.writeFieldSep()
                firstField = false
                gen.writeFieldName(field.targetName)
                gen.writeAnyValue(converted)
            }
            // type is always emitted — Firefox Profiler uses it to look up markerSchema for Details rendering
            if (!firstField) gen.writeFieldSep()
            firstField = false
            gen.writeFieldName("type")
            gen.writeString(event.typeName)
            if (!tables.config.minimalMarkerPayload) {
                gen.writeFieldSep()
                gen.writeFieldName("startTime")
                gen.write((event.startMs - basicInformation.startTimeMillis).toString())
            }
            if (event.typeName == "jdk.ObjectAllocationSample") {
                val className = event.getString("objectClass.name") ?: event.getString("objectClass") ?: ""
                val formatted = if (className.startsWith("[")) className else "L$className;"
                val stackIdx = tables.stackTraceTable.getMiscStack(
                    me.bechberger.jfrtofp.util.ByteCodeHelper.formatByteCodeType(formatted, omitPackages = false),
                )
                if (!firstField) gen.writeFieldSep()
                gen.writeFieldName("_class")
                gen.writeStartObject()
                gen.writeFieldName("stack")
                gen.write(stackIdx.toString())
                gen.writeEndObject()
            }
            gen.writeEndObject()
            spiller.add(name, startTime, endTime, phase, category, reuseableBaos.toByteArray())
        } else {
            // Slow path (non-streaming): build JsonElement map for later serialization
            val data =
                fieldMapping.fields.map { field ->
                    val rawValue: Any? = if (field.sourceName == "stackTrace" && field.type == MarkerType.STACKTRACE) {
                        tables.getStack(event, Int.MAX_VALUE)
                    } else {
                        field.getValue(event)
                    }
                    rawValue?.let { value ->
                        if (tables.config.dropSentinelValues && value is Long && (value == Long.MIN_VALUE || value == Long.MAX_VALUE)) return@let null
                        field.targetName to field.type.convert(tables, startTimeInstant, value).toJsonElement()
                    }
                }.filterNotNull().toMap(mutableMapOf())
            // type is always emitted — Firefox Profiler uses it to look up markerSchema for Details rendering
            data["type"] = event.typeName.toJsonElement()
            if (!tables.config.minimalMarkerPayload) {
                data["startTime"] = (event.startMs - basicInformation.startTimeMillis).toJsonElement()
            }
            when (event.typeName) {
                "jdk.ObjectAllocationSample" -> {
                    val className = event.getString("objectClass.name") ?: event.getString("objectClass") ?: ""
                    val formatted = if (className.startsWith("[")) className else "L$className;"
                    data["_class"] =
                        mapOf(
                            "stack" to tables.stackTraceTable.getMiscStack(
                                me.bechberger.jfrtofp.util.ByteCodeHelper.formatByteCodeType(formatted, omitPackages = false),
                            ),
                        ).toJsonElement()
                }
            }
            items.add(Item(name, startTime, endTime, phase, category, data))
        }
    }

    val count: Int get() = itemCount

    fun toRawMarkerTable(): RawMarkerTable {
        val sortedItems = items.sortedBy { it.startTime }
        return RawMarkerTable(
            data = sortedItems.map { it.data },
            name = sortedItems.map { it.name },
            startTime = sortedItems.map { it.startTime },
            endTime = sortedItems.map { it.endTime },
            phase = sortedItems.map { it.phase },
            category = sortedItems.map { it.category },
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun write(json: BasicJSONGenerator) {
        if (spiller != null) {
            writeFromSpiller(json)
        } else {
            writeFromItems(json)
        }
    }

    private fun writeFromSpiller(json: BasicJSONGenerator) {
        spiller!!.close()
        val names = ArrayList<Int>(itemCount)
        val startTimes = ArrayList<Double?>(itemCount)
        val endTimes = ArrayList<Double?>(itemCount)
        val phases = ArrayList<Int>(itemCount)
        val categories = ArrayList<Int>(itemCount)
        val dataBlobs = ArrayList<ByteArray>(itemCount)
        spiller.replay { name, startTime, endTime, phase, category, dataBytes ->
            names.add(name); startTimes.add(startTime); endTimes.add(endTime)
            phases.add(phase); categories.add(category); dataBlobs.add(dataBytes)
        }
        writeArraysAndData(json, names, startTimes, endTimes, phases, categories) { i ->
            json.output.write(dataBlobs[i])
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeFromItems(json: BasicJSONGenerator) {
        val sortedItems = items.sortedBy { it.startTime }
        writeArraysAndData(
            json,
            sortedItems.map { it.name },
            sortedItems.map { it.startTime },
            sortedItems.map { it.endTime },
            sortedItems.map { it.phase },
            sortedItems.map { it.category },
        ) { i -> jsonFormat.encodeToStream(sortedItems[i].data, json.output) }
    }

    private fun writeArraysAndData(
        json: BasicJSONGenerator,
        names: List<Int>,
        startTimes: List<Double?>,
        endTimes: List<Double?>,
        phases: List<Int>,
        categories: List<Int>,
        writeData: (Int) -> Unit,
    ) {
        json.writeStartObject()
        json.writeNumberArrayField("name", names)
        json.writeQuantizedNumberArrayField("startTime", startTimes, tables.config.timestampDecimals)
        json.writeQuantizedNumberArrayField("endTime", endTimes, tables.config.timestampDecimals)
        json.writeNumberArrayField("phase", phases)
        json.writeNumberArrayField("category", categories)
        json.writeSimpleField("length", names.size)
        json.writeFieldName("data")
        json.writeStartArray()
        names.indices.forEach { i ->
            writeData(i)
            if (i < names.size - 1) json.writeFieldSep()
        }
        json.writeEndArray()
        json.writeEndObject()
    }
}

class ResourceTableWrapper(val tables: Tables) {
    private val map = mutableMapOf<MethodKey, IndexIntoResourceTable>()
    private val names = mutableListOf<IndexIntoStringTable>()
    private val hosts = mutableListOf<IndexIntoStringTable?>()
    private val types = mutableListOf<resourceTypeEnum>()

    internal fun getResource(
        key: MethodKey,
        isJava: Boolean,
    ): IndexIntoResourceTable {
        return map.computeIfAbsent(key) {
            val wholeName = key.className
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

    fun write(json: BasicJSONGenerator) {
        json.writeStartObject()
        json.writeNumberArrayField("name", names)
        json.writeNumberArrayField("host", hosts)
        json.writeNumberArrayField("type", types)
        json.writeSimpleField("length", size)
        json.writeNullArrayField("lib", size, last = true)
        json.writeEndObject()
    }

    val size: Int
        get() = names.size
}

class SourceTableWrapper(val tables: Tables) {
    private val map = mutableMapOf<Pair<IndexIntoStringTable, IndexIntoStringTable?>, IndexIntoSourceTable>()
    private val ids = mutableListOf<String?>()
    private val filenames = mutableListOf<IndexIntoStringTable>()
    private val sourceUrls = mutableListOf<IndexIntoStringTable?>()

    fun getOrCreate(
        filename: String?,
        sourceUrl: String?,
    ): IndexIntoSourceTable? {
        if (filename == null) return null
        val filenameIdx = tables.getString(filename)
        val sourceUrlIdx = sourceUrl?.let { tables.getString(it) }
        return map.computeIfAbsent(filenameIdx to sourceUrlIdx) {
            val index = ids.size
            ids.add(null)
            filenames.add(filenameIdx)
            sourceUrls.add(sourceUrlIdx)
            index
        }
    }

    fun toSourceTable(): SourceTable {
        val length = filenames.size
        return SourceTable(
            length = length,
            id = ids,
            filename = filenames,
            startLine = List(length) { -1 },
            startColumn = List(length) { -1 },
            sourceMapURL = List(length) { null },
            sourceUrl = if (sourceUrls.any { it != null }) sourceUrls else null,
        )
    }

    val size: Int
        get() = filenames.size
}

class FuncTableWrapper(val tables: Tables) {
    private val map = mutableMapOf<MethodKey, IndexIntoFuncTable>()
    private val names = mutableListOf<IndexIntoStringTable>()
    private val lineNumbers = mutableListOf<Int>()
    private val isJss = mutableListOf<Boolean>()
    private val relevantForJss = mutableListOf<Boolean>()
    private val resourcess = mutableListOf<IndexIntoResourceTable>()
    private val sources = mutableListOf<IndexIntoSourceTable?>()
    private val miscFunctions = mutableMapOf<String, IndexIntoFuncTable>()

    internal fun getFunction(
        key: MethodKey,
        isJava: Boolean,
        lineNumber: Int,
    ): IndexIntoFuncTable {
        return map.computeIfAbsent(key) {
            val index = names.size
            val url = tables.classToUrl(key.pkg, key.simpleClassName)
            sources.add(tables.sourceTable.getOrCreate(filename = key.className, sourceUrl = url))
            names.add(tables.getString(ByteCodeHelper.formatFunctionWithClass(key)))
            isJss.add(isJava)
            relevantForJss.add(true)
            resourcess.add(tables.getResource(key, isJava))
            lineNumbers.add(lineNumber)
            index
        }
    }

    internal fun getMiscFunction(
        name: String,
        isNative: Boolean,
    ): IndexIntoStringTable {
        return miscFunctions.computeIfAbsent(name) {
            val index = names.size
            names.add(tables.getString(name))
            isJss.add(isNative)
            relevantForJss.add(true)
            resourcess.add(-1)
            sources.add(tables.sourceTable.getOrCreate(filename = null, sourceUrl = tables.defaultUrl))
            lineNumbers.add(-1)
            index
        }
    }

    fun toFuncTable() =
        FuncTable(
            name = names,
            isJS = isJss,
            relevantForJS = relevantForJss,
            resource = resourcess,
            source = sources,
            lineNumber = lineNumbers,
        )

    fun write(json: BasicJSONGenerator) {
        json.writeStartObject()
        json.writeNumberArrayField("name", names)
        json.writeBooleanArrayField("isJS", isJss)
        json.writeBooleanArrayField("relevantForJS", relevantForJss)
        json.writeNumberArrayField("resource", resourcess)
        json.writeNumberArrayField("source", sources)
        json.writeSimpleField("length", size)
        json.writeNumberArrayField("lineNumber", lineNumbers)
        json.writeNullArrayField("columnNumber", size, last = true)
        json.writeEndObject()
    }

    val size: Int
        get() = sources.size
}

class FrameTableWrapper(val tables: Tables) {
    private val map = mutableMapOf<Triple<MethodKey, Int?, String>, IndexIntoFrameTable>()
    private val categories = mutableListOf<IndexIntoCategoryList?>()
    private val subcategories = mutableListOf<IndexIntoSubcategoryListForCategory?>()
    private val funcs = mutableListOf<IndexIntoFuncTable>()
    private val lines = mutableListOf<Int?>()
    private val miscFrames = mutableMapOf<String, IndexIntoStringTable>()

    internal fun getFrame(key: MethodKey, lineNumber: Int, isJavaFrame: Boolean, frameType: String): IndexIntoFrameTable {
        val line = if (lineNumber == -1) null else lineNumber
        return map.computeIfAbsent(Triple(key, line, frameType)) {
            val func = tables.getFunction(key, isJavaFrame, -1)
            val (mainCat, sub) =
                if (tables.config.useNonProjectCategory && isJavaFrame &&
                    key.isNonProjectType(tables.config.nonProjectPackagePrefixes)
                ) {
                    CategoryE.NON_PROJECT_JAVA.sub(frameType)
                } else if (isJavaFrame) {
                    CategoryE.JAVA.sub(frameType)
                } else {
                    CategoryE.CPP.sub(frameType)
                }
            funcs.add(func)
            categories.add(mainCat)
            subcategories.add(sub)
            lines.add(line)
            lines.size - 1
        }
    }

    internal fun getMiscFrame(
        name: String,
        category: CategoryE,
        subcategory: String,
        isNative: Boolean,
    ): IndexIntoFrameTable {
        return miscFrames.computeIfAbsent(name) {
            val (cat, sub) = category.sub(subcategory)
            categories.add(cat)
            subcategories.add(sub)
            funcs.add(tables.getMiscFunction(name, isNative))
            lines.add(null)
            lines.size - 1
        }
    }

    fun write(json: BasicJSONGenerator) {
        json.writeStartObject()
        json.writeNumberArrayField("category", categories)
        json.writeNumberArrayField("subcategory", subcategories)
        json.writeNumberArrayField("func", funcs)
        json.writeNumberArrayField("line", lines)
        json.writeSingleValueArrayField("address", "-1", size)
        json.writeSingleValueArrayField("inlineDepth", "0", size)
        for (name in listOf("nativeSymbol", "innerWindowID", "column")) {
            json.writeNullArrayField(name, size)
        }
        json.writeSimpleField("length", size, last = true)
        json.writeEndObject()
    }

    fun getCategoryOfFrame(frame: IndexIntoFrameTable): Pair<IndexIntoCategoryList, IndexIntoSubcategoryListForCategory> {
        return categories[frame]!! to subcategories[frame]!!
    }

    fun toFrameTable() = FrameTable(category = categories, subcategory = subcategories, func = funcs, line = lines)

    val size: Int
        get() = funcs.size
}

typealias HashedFrameList = HashedList<IndexIntoFrameTable>

class StackTableWrapper(val tables: Tables) {
    class StackTraceMap {
        private val mapPerLength: MutableList<MutableMap<HashedFrameList, IndexIntoStackTable>> = mutableListOf()

        private fun getMapForLength(length: Int): MutableMap<HashedFrameList, IndexIntoStackTable> {
            while (mapPerLength.size <= length) {
                mapPerLength.add(mutableMapOf())
            }
            return mapPerLength[length]
        }

        fun contains(stack: HashedFrameList) = getMapForLength(stack.size).containsKey(stack)

        operator fun get(stack: HashedFrameList) = getMapForLength(stack.size)[stack]

        operator fun set(
            stack: HashedFrameList,
            value: IndexIntoStackTable,
        ) {
            getMapForLength(stack.size)[stack] = value
        }
    }

    private val map = StackTraceMap()

    private val frames = mutableListOf<IndexIntoFrameTable>()
    private val prefix = mutableListOf<IndexIntoFrameTable?>()
    private val miscStacks = mutableMapOf<String, IndexIntoStringTable>()

    /** Build a [HashedFrameList] from the parallel frame arrays in [ParsedJFREvent]. */
    private fun getHashedFrameList(event: ParsedJFREvent): HashedFrameList {
        val n = event.stackDepth
        // Jafar provides frames top-of-stack first (index 0 = top). Firefox Profiler wants
        // leaf-at-end (index 0 = bottom-most), matching the JDK legacy: frames are reversed.
        val frameIndices = (n - 1 downTo 0).mapNotNull { i ->
            val className = event.frameClassNames[i]
            val methodName = event.frameMethodNames[i]
            val descriptor = event.frameDescriptors[i]
            if (className.isEmpty() && methodName.isEmpty()) return@mapNotNull null
            val key = MethodKey(className, methodName, descriptor)
            val lineNumber = event.frameLineNumbers[i]
            val isJava = event.frameIsJava[i]
            val frameType = if (isJava) "Interpreted" else "Native"
            tables.getFrame(key, lineNumber, isJava, frameType)
        }
        return HashedFrameList(frameIndices)
    }

    internal fun getStack(event: ParsedJFREvent, maxStackTraceFrames: Int): IndexIntoStackTable {
        if (event.stackDepth == 0) return -1
        return getStack(getHashedFrameList(event), maxStackTraceFrames)
    }

    internal fun getStack(
        stackTrace: HashedFrameList,
        maxStackTraceFrames: Int = Int.MAX_VALUE,
    ): IndexIntoStackTable {
        if (maxStackTraceFrames == 0) return -1
        if (stackTrace.size == 0) return -1

        if (!map.contains(stackTrace)) {
            val topFrame = stackTrace.last
            val pref =
                if (stackTrace.size > 1) {
                    getStack(stackTrace.prefix(), maxStackTraceFrames - 1)
                } else {
                    null
                }
            val index = frames.size
            prefix.add(pref)
            frames.add(topFrame)
            map[stackTrace] = index
        }
        return map[stackTrace]!!
    }

    internal fun getMiscStack(
        name: String,
        category: CategoryE = CategoryE.MISC,
        subcategory: String = "Other",
        isNative: Boolean = false,
    ): IndexIntoStackTable {
        return miscStacks.computeIfAbsent(name) {
            prefix.add(null)
            frames.add(tables.getMiscFrame(name, category, subcategory, isNative))
            prefix.size - 1
        }
    }

    fun toStackTable() = StackTable(frame = frames, prefix = prefix)

    fun write(json: BasicJSONGenerator) {
        json.writeStartObject()
        json.writeNumberArrayField("frame", frames)
        json.writeNumberArrayField("prefix", prefix)
        json.writeSimpleField("length", size, last = true)
        json.writeEndObject()
    }

    val size: Int
        get() = frames.size
}
