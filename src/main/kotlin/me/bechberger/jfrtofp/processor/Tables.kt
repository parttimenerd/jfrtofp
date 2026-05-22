package me.bechberger.jfrtofp.processor

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedStackTrace
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
import me.bechberger.jfrtofp.util.HashableRecordedMethod
import me.bechberger.jfrtofp.util.HashedList
import me.bechberger.jfrtofp.util.Percentage
import me.bechberger.jfrtofp.util.StringTableWrapper
import me.bechberger.jfrtofp.util.className
import me.bechberger.jfrtofp.util.jsonFormat
import me.bechberger.jfrtofp.util.pkg
import me.bechberger.jfrtofp.util.toJsonElement
import me.bechberger.jfrtofp.util.toMillis
import java.util.IdentityHashMap

/** Wraps the [SamplesTable] class */
class SamplesTableWrapper(val tables: Tables) {
    data class Item(val stack: IndexIntoStackTable, val time: Milliseconds)

    private val items: MutableList<Item> = mutableListOf()

    fun processEvent(event: RecordedEvent) {
        items.add(
            Item(
                event.stackTrace.let {
                    tables.getStack(
                        it,
                    )
                },
                event.startTime.toMillis(),
            ),
        )
    }

    fun toSamplesTable(cpuLoad: (Milliseconds) -> Percentage): SamplesTable {
        val sortedItems = items.sortedBy { it.time }
        val time = sortedItems.map { it.time }
        val stack = sortedItems.map { it.stack }

        // Declared unit is µs (see SampleUnits.threadCPUDelta in MetaProcessor).
        // delta_µs = (time_ms[i] - time_ms[i-1]) * 1000 * cpuLoad
        val threadCPUDelta: MutableList<Milliseconds> = mutableListOf(0.0)
        for (i in 1 until time.size) {
            if (i == time.size - 1) {
                threadCPUDelta.add(0.0)
            } else {
                threadCPUDelta.add(
                    (time[i] - time[i - 1]) * 1000.0 * cpuLoad(time[i]),
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
        val samplesTable = toSamplesTable(cpuLoad)
        json.writeStartObject()
        json.writeNumberArrayField("stack", samplesTable.stack)
        json.writeNumberArrayField("time", samplesTable.time)
        json.writeNumberArrayField("threadCPUDelta", samplesTable.threadCPUDelta!!)
        json.writeSingleValueArrayField("eventDelay", "0.0", samplesTable.stack.size)
        json.writeSimpleField("weightType", "samples")
        json.writeSimpleField("length", samplesTable.stack.size, last = true)
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
    val methodToHashable: IdentityHashMap<RecordedMethod, HashableRecordedMethod> = IdentityHashMap()

    fun getString(string: String) = stringTable[string]

    fun getResource(
        func: RecordedMethod,
        isJava: Boolean,
    ) = resourceTable.getResource(func, isJava)

    fun getFunction(
        func: RecordedMethod,
        isJava: Boolean,
        lineNumber: Int,
    ) = funcTable.getFunction(func, isJava, lineNumber)

    fun getMiscFunction(
        name: String,
        isNative: Boolean,
    ) = funcTable.getMiscFunction(name, isNative)

    fun getFrame(frame: RecordedFrame) = frameTable.getFrame(frame)

    fun getMiscFrame(
        name: String,
        category: CategoryE,
        subcategory: String,
        isNative: Boolean,
    ) = frameTable.getMiscFrame(name, category, subcategory, isNative)

    fun getStack(stackTrace: RecordedStackTrace) = getStack(stackTrace, Int.MAX_VALUE)

    fun getStack(
        stackTrace: RecordedStackTrace,
        maxStackTraceFrames: Int,
    ) = stackTraceTable.getStack(stackTrace, maxStackTraceFrames)

    fun getStack(
        stackTrace: HashedFrameList,
        maxStackTraceFrames: Int = Int.MAX_VALUE,
    ) = stackTraceTable.getStack(stackTrace, maxStackTraceFrames)

    fun getMiscStack(
        name: String,
        category: CategoryE = CategoryE.MISC,
        subcategory: String = "Other",
        isNative: Boolean = false,
    ) = stackTraceTable.getMiscStack(name, category, subcategory, isNative)

    fun getHashable(func: RecordedMethod): HashableRecordedMethod {
        return methodToHashable.computeIfAbsent(func) { HashableRecordedMethod(it) }
    }
}

class RawMarkerTableWrapper(
    val tables: Tables,
    val basicInformation: BasicInformation,
    val markerSchema: MarkerSchemaProcessor,
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

    fun processEvent(event: RecordedEvent) {
        val fieldMapping: MarkerSchemaFieldMapping = markerSchema[event.eventType] ?: return
        val name = tables.getString(event.eventType.name)
        val startTime = event.startTime.toMillis()
        val endTime = event.endTime.toMillis()
        val phase = if (event.endTime == event.startTime) 0 else 1 // instant vs interval
        val category = CategoryE.fromName(event.eventType.categoryNames.firstOrNull() ?: "Other").index
        val startTimeInstant = event.startTime
        val data =
            fieldMapping.fields.map { field ->
                field.getValue(event)?.let { value ->
                    field.targetName to field.type.convert(tables, startTimeInstant, value).toJsonElement()
                }
            }.filterNotNull().toMap(mutableMapOf())
        data["type"] = event.eventType.name.toJsonElement()
        data["startTime"] = (event.startTime.toMillis() - basicInformation.startTimeMillis).toJsonElement()
        when (event.eventType.name) {
            "jdk.ObjectAllocationSample" -> {
                data["_class"] =
                    mapOf(
                        "stack" to
                            tables.stackTraceTable.getMiscStack(
                                ByteCodeHelper.formatRecordedClass(event.getClass("objectClass")),
                            ),
                    )
                        .toJsonElement()
            }
        }
        items.add(Item(name, startTime, endTime, phase, category, data))
    }

    fun toRawMarkerTable(): RawMarkerTable {
        val sortedItems = items.sortedBy { it.startTime } // TODO: really needed?
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
        val sortedItems = items.sortedBy { it.startTime } // TODO: really needed?
        json.writeStartObject()

        json.writeNumberArrayField("name", sortedItems.map { it.name })
        json.writeNumberArrayField("startTime", sortedItems.map { it.startTime })
        json.writeNumberArrayField("endTime", sortedItems.map { it.endTime })
        json.writeNumberArrayField("phase", sortedItems.map { it.phase })
        json.writeNumberArrayField("category", sortedItems.map { it.category })
        json.writeSimpleField("length", sortedItems.size)

        json.writeFieldName("data")
        json.writeStartArray()
        sortedItems.forEach {
            jsonFormat.encodeToStream(it.data, json.output)
            if (it != sortedItems.last()) {
                json.writeFieldSep()
            }
        }
        json.writeEndArray()

        json.writeEndObject()
    }
}

class ResourceTableWrapper(val tables: Tables) {
    private val map = mutableMapOf<HashableRecordedMethod, IndexIntoResourceTable>()
    private val names = mutableListOf<IndexIntoStringTable>()
    private val hosts = mutableListOf<IndexIntoStringTable?>()
    private val types = mutableListOf<resourceTypeEnum>()

    internal fun getResource(
        func: RecordedMethod,
        isJava: Boolean,
    ): IndexIntoResourceTable {
        return map.computeIfAbsent(tables.getHashable(func)) {
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
    // Key: (filename string index, sourceUrl string index or null) — collapse identical sources.
    private val map = mutableMapOf<Pair<IndexIntoStringTable, IndexIntoStringTable?>, IndexIntoSourceTable>()
    private val ids = mutableListOf<String?>()
    private val filenames = mutableListOf<IndexIntoStringTable>()
    private val sourceUrls = mutableListOf<IndexIntoStringTable?>()

    /** Returns null if no filename is provided. */
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
    private val map = mutableMapOf<HashableRecordedMethod, IndexIntoFuncTable>()
    private val names = mutableListOf<IndexIntoStringTable>()
    private val lineNumbers = mutableListOf<Int>()
    private val isJss = mutableListOf<Boolean>()
    private val relevantForJss = mutableListOf<Boolean>()
    private val resourcess = mutableListOf<IndexIntoResourceTable>() // -1 if not present
    private val sources = mutableListOf<IndexIntoSourceTable?>()
    private val miscFunctions = mutableMapOf<String, IndexIntoFuncTable>()

    internal fun getFunction(
        func: RecordedMethod,
        isJava: Boolean,
        lineNumber: Int,
    ): IndexIntoFuncTable {
        return map.computeIfAbsent(tables.getHashable(func)) {
            val index = names.size
            val type = func.type
            val url = tables.classToUrl(type.pkg, type.className)
            // No filename information from JFR, only the class name; use it so the
            // shared SourceTable can carry a sourceUrl for this function.
            sources.add(tables.sourceTable.getOrCreate(filename = type.name, sourceUrl = url))
            names.add(tables.getString(ByteCodeHelper.formatFunctionWithClass(func)))
            isJss.add(isJava)
            relevantForJss.add(true)
            resourcess.add(tables.getResource(func, isJava))
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
    private val map = mutableMapOf<Pair<IndexIntoFuncTable, Int?>, IndexIntoFrameTable>()
    private val categories = mutableListOf<IndexIntoCategoryList?>()
    private val subcategories = mutableListOf<IndexIntoSubcategoryListForCategory?>()
    private val funcs = mutableListOf<IndexIntoFuncTable>()
    private val lines = mutableListOf<Int?>()
    private val miscFrames = mutableMapOf<String, IndexIntoStringTable>()

    internal fun getFrame(frame: RecordedFrame): IndexIntoFrameTable {
        // we don't know the start line of the function from JFR
        // so we use -1, to signal the profile viewer that it is invalid
        // Related to https://github.com/parttimenerd/jfrtofp/issues/6
        val func = tables.getFunction(frame.method, frame.isJavaFrame, -1)
        val line = if (frame.lineNumber == -1) null else frame.lineNumber

        return map.computeIfAbsent(func to line) {
            val (mainCat, sub) =
                if (tables.config.useNonProjectCategory && frame.isJavaFrame &&
                    tables.config.isNonProjectType(
                        frame.method.type,
                    )
                ) {
                    CategoryE.NON_PROJECT_JAVA.sub(frame.type)
                } else if (frame.isJavaFrame) {
                    CategoryE.JAVA.sub(frame.type)
                } else {
                    CategoryE.CPP.sub(frame.type)
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

    private fun getHashedFrameList(
        tables: Tables,
        stackTrace: RecordedStackTrace,
    ) = HashedFrameList(
        stackTrace.frames
            .filter { f -> f.method != null }
            .asReversed().map { tables.getFrame(it) },
    )

    internal fun getStack(
        stackTrace: RecordedStackTrace,
        maxStackTraceFrames: Int,
    ): IndexIntoStackTable {
        return getStack(getHashedFrameList(tables, stackTrace), maxStackTraceFrames)
    }

    internal fun getStack(
        stackTrace: HashedFrameList,
        maxStackTraceFrames: Int = Int.MAX_VALUE,
    ): IndexIntoStackTable {
        // we obtain the stack recursively

        if (maxStackTraceFrames == 0) {
            return -1 // too many stack frames
        }
        if (stackTrace.size == 0) {
            return -1
        }
        // top frame is on the highest index

        // this map contains all stack traces and their prefixes
        if (!map.contains(stackTrace)) {
            val topFrame = stackTrace.last
            val pref =
                if (stackTrace.size > 1) {
                    getStack(
                        stackTrace.prefix(),
                        maxStackTraceFrames - 1,
                    )
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
