package me.bechberger.jfrtofp.processor

import MarkerSchemaProcessor
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedStackTrace
import kotlinx.serialization.json.JsonElement
import me.bechberger.jfrtofp.types.FrameTable
import me.bechberger.jfrtofp.types.FuncTable
import me.bechberger.jfrtofp.types.IndexIntoCategoryList
import me.bechberger.jfrtofp.types.IndexIntoFrameTable
import me.bechberger.jfrtofp.types.IndexIntoFuncTable
import me.bechberger.jfrtofp.types.IndexIntoResourceTable
import me.bechberger.jfrtofp.types.IndexIntoStackTable
import me.bechberger.jfrtofp.types.IndexIntoStringTable
import me.bechberger.jfrtofp.types.IndexIntoSubcategoryListForCategory
import me.bechberger.jfrtofp.types.MarkerPhase
import me.bechberger.jfrtofp.types.Milliseconds
import me.bechberger.jfrtofp.types.RawMarkerTable
import me.bechberger.jfrtofp.types.ResourceTable
import me.bechberger.jfrtofp.types.SamplesTable
import me.bechberger.jfrtofp.types.StackTable
import me.bechberger.jfrtofp.types.resourceTypeEnum
import me.bechberger.jfrtofp.util.ByteCodeHelper
import me.bechberger.jfrtofp.util.HashedList
import me.bechberger.jfrtofp.util.Percentage
import me.bechberger.jfrtofp.util.StringTableWrapper
import me.bechberger.jfrtofp.util.className
import me.bechberger.jfrtofp.util.pkg
import me.bechberger.jfrtofp.util.toJsonElement
import me.bechberger.jfrtofp.util.toMillis

/** Wraps the [SamplesTable] class */
class SamplesTableWrapper(val tables: Tables) {

    data class Item(val stack: IndexIntoStackTable, val time: Milliseconds)

    private val items: MutableList<Item> = mutableListOf()

    fun processEvent(event: RecordedEvent) {
        items.add(
            Item(
                event.stackTrace.let {
                    tables.getStack(
                        it
                    )
                },
                event.startTime.toMillis()
            )
        )
    }

    fun toSamplesTable(cpuLoad: (Milliseconds) -> Percentage): SamplesTable {
        val sortedItems = items.sortedBy { it.time }
        val time = sortedItems.map { it.time }
        val stack = sortedItems.map { it.stack }

        /** in ms,    delta[i] = [time[i] - time[i - 1]] * [usage in this interval] */
        val threadCPUDelta: MutableList<Milliseconds> = mutableListOf(0.0)
        for (i in 1 until time.size) {
            if (i == time.size - 1) {
                threadCPUDelta.add(0.0)
            } else {
                threadCPUDelta.add(
                    (time[i] - time[i - 1]) * cpuLoad(time[i])
                )
            }
        }
        return SamplesTable(
            stack = stack,
            time = time,
            threadCPUDelta = threadCPUDelta
        )
    }
}

/** Combines the different table wrappers */
data class Tables(
    val config: Config,
    val basicInformation: BasicInformation,
    val markerSchema: MarkerSchemaProcessor,
    val classToUrl: (String, String) -> String? = { _, _ -> null },
) {
    val stringTable: StringTableWrapper = StringTableWrapper()
    val resourceTable: ResourceTableWrapper = ResourceTableWrapper(this)
    val frameTable: FrameTableWrapper = FrameTableWrapper(this)
    val stackTraceTable: StackTableWrapper = StackTableWrapper(this)
    val funcTable: FuncTableWrapper = FuncTableWrapper(this)
    val rawMarkerTable: RawMarkerTableWrapper = RawMarkerTableWrapper(this, basicInformation, markerSchema)

    fun getString(string: String) = stringTable[string]

    fun getResource(func: RecordedMethod, isJava: Boolean) = resourceTable.getResource(func, isJava)

    fun getFunction(func: RecordedMethod, isJava: Boolean) = funcTable.getFunction(func, isJava)

    fun getMiscFunction(name: String, isNative: Boolean) = funcTable.getMiscFunction(name, isNative)

    fun getFrame(
        frame: RecordedFrame,
        category: Pair<Int, Int>? = null
    ) = frameTable.getFrame(frame, category)

    fun getMiscFrame(
        name: String,
        category: CategoryE,
        subcategory: String,
        isNative: Boolean
    ) = frameTable.getMiscFrame(name, category, subcategory, isNative)

    fun getStack(
        stackTrace: RecordedStackTrace,
        category: Pair<Int, Int>? = null
    ) = getStack(stackTrace, category, config.maxStackTraceFrames)

    fun getStack(
        stackTrace: RecordedStackTrace,
        category: Pair<Int, Int>? = null,
        maxStackTraceFrames: Int
    ) = stackTraceTable.getStack(stackTrace, category, maxStackTraceFrames)

    fun getStack(
        stackTrace: HashedList<IndexIntoFrameTable>,
        maxStackTraceFrames: Int = Int.MAX_VALUE
    ) = stackTraceTable.getStack(stackTrace, maxStackTraceFrames)

    fun getMiscStack(
        name: String,
        category: CategoryE = CategoryE.MISC,
        subcategory: String = "Other",
        isNative: Boolean = false
    ) = stackTraceTable.getMiscStack(name, category, subcategory, isNative)
}

class RawMarkerTableWrapper(val tables: Tables, val basicInformation: BasicInformation, val markerSchema: MarkerSchemaProcessor) {

    data class Item(
        val name: IndexIntoStringTable,
        val startTime: Milliseconds?,
        val endTime: Milliseconds?,
        val phase: MarkerPhase,
        val category: IndexIntoCategoryList,
        val data: Map<String, JsonElement>
    )

    private val items: MutableList<Item> = mutableListOf()

    fun processEvent(
        event: RecordedEvent
    ) {
        val fieldMapping = markerSchema[event.eventType] ?: return
        val name = tables.getString(event.eventType.name)
        val startTime = event.startTime.toMillis()
        val endTime = event.endTime.toMillis()
        val phase = if (event.endTime == event.startTime) 0 else 1 // instant vs interval
        val category = CategoryE.fromName(event.eventType.categoryNames.first()).index
        val data =
            event.fields.filter { it.name in fieldMapping && event.getValue<Any>(it.name) != null }.map {
                fieldMapping[it.name] to MarkerType.fromName(it).convert(tables, event, it.name)
                    .toJsonElement()
            }.toMap(mutableMapOf())
        data["type"] = event.eventType.name.toJsonElement()
        data["startTime"] = (event.startTime.toMillis() - basicInformation.startTimeMillis).toJsonElement()
        when (event.eventType.name) {
            "jdk.ObjectAllocationSample" -> {
                data["_class"] = tables.stackTraceTable.getMiscStack(ByteCodeHelper.formatRecordedClass(event.getClass("objectClass")))
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
            category = sortedItems.map { it.category }
        )
    }
}

class ResourceTableWrapper(val tables: Tables) {
    private val map = mutableMapOf<RecordedMethod, IndexIntoResourceTable>()
    private val names = mutableListOf<IndexIntoStringTable>()
    private val hosts = mutableListOf<IndexIntoStringTable?>()
    private val types = mutableListOf<resourceTypeEnum>()

    internal fun getResource(func: RecordedMethod, isJava: Boolean): IndexIntoResourceTable {
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

class FuncTableWrapper(val tables: Tables) {

    private val map = mutableMapOf<RecordedMethod, IndexIntoFuncTable>()
    private val names = mutableListOf<IndexIntoStringTable>()
    private val isJss = mutableListOf<Boolean>()
    private val relevantForJss = mutableListOf<Boolean>()
    private val resourcess = mutableListOf<IndexIntoResourceTable>() // -1 if not present
    private val fileNames = mutableListOf<IndexIntoStringTable?>()

    // This is the optional information on the url of the source file
    // that this function can be seen in specifically.
    // Prefixing the URL with `post|` signifies that the URL should
    // be called with a POST request and the response discarded (the request
    // includes `name`, `file`, `line` and `column` information if present).
    // `post|` URLs can have another format: `post|url|alternative` where
    // the alternative URL is used if the origin of the url does not have
    // the same origin as the profile viewer. This allows to supply a public
    // fallback URL for local profile URLs.
    // These POST requests are used by imported profiles to trigger events
    // outside of the profiler.
    // Urls may currently only start with `https://raw.githubusercontent.com/` or
    // `http://localhost`.
    private val sourceUrls = mutableListOf<IndexIntoStringTable?>()
    private val miscFunctions = mutableMapOf<String, IndexIntoFuncTable>()

    internal fun getFunction(func: RecordedMethod, isJava: Boolean): IndexIntoFuncTable {
        return map.computeIfAbsent(func) {
            val type = func.type
            val url =
                tables.classToUrl(type.className.split("$").last(), type.pkg) ?: "http://localhost/files?className=${type.className}&pkg=${type.pkg}"
            sourceUrls.add(url.let { tables.getString(url) })
            names.add(tables.getString(ByteCodeHelper.formatFunctionWithClass(func)))
            isJss.add(isJava)
            relevantForJss.add(tables.config.isRelevantForJava(func))
            resourcess.add(tables.getResource(func, isJava))
            fileNames.add(null)
            map.size
        }
    }

    internal fun getMiscFunction(name: String, isNative: Boolean): IndexIntoStringTable {
        return miscFunctions.computeIfAbsent(name) {
            val index = names.size
            names.add(tables.getString(name))
            isJss.add(isNative)
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

class FrameTableWrapper(val tables: Tables) {

    private val map = mutableMapOf<Pair<IndexIntoFuncTable, Int?>, IndexIntoFrameTable>()
    private val categories = mutableListOf<IndexIntoCategoryList?>()
    private val subcategories = mutableListOf<IndexIntoSubcategoryListForCategory?>()
    private val funcs = mutableListOf<IndexIntoFuncTable>()
    private val lines = mutableListOf<Int?>()
    private val miscFrames = mutableMapOf<String, IndexIntoStringTable>()

    internal fun getFrame(
        frame: RecordedFrame,
        category: Pair<Int, Int>? = null
    ): IndexIntoFrameTable {
        val func = tables.getFunction(frame.method, frame.isJavaFrame)
        val line = if (frame.lineNumber == -1) null else frame.lineNumber

        return map.computeIfAbsent(func to line) {
            val (mainCat, sub) = if (tables.config.useNonProjectCategory && frame.isJavaFrame && tables.config.isNonProjectType(
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
        isNative: Boolean
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

    fun getCategoryOfFrame(frame: IndexIntoFrameTable): Pair<IndexIntoCategoryList, IndexIntoSubcategoryListForCategory> {
        return categories[frame]!! to subcategories[frame]!!
    }

    fun toFrameTable() = FrameTable(category = categories, subcategory = subcategories, func = funcs, line = lines)

    val size: Int
        get() = funcs.size
}

class StackTableWrapper(val tables: Tables) {

    private val map = mutableMapOf<HashedList<IndexIntoFrameTable>, IndexIntoStackTable>()
    private val frames = mutableListOf<IndexIntoFrameTable>()
    private val prefix = mutableListOf<IndexIntoFrameTable?>()
    private val categories = mutableListOf<IndexIntoCategoryList>()
    private val subcategories = mutableListOf<IndexIntoSubcategoryListForCategory>()
    private val miscStacks = mutableMapOf<String, IndexIntoStringTable>()

    private fun getHashedFrameList(
        tables: Tables,
        stackTrace: RecordedStackTrace,
        category: Pair<Int, Int>? = null
    ) =
        HashedList(stackTrace.frames.reversed().map { tables.getFrame(it, category) })

    internal fun getStack(
        stackTrace: RecordedStackTrace,
        category: Pair<Int, Int>? = null,
        maxStackTraceFrames: Int
    ): IndexIntoStackTable {
        return getStack(getHashedFrameList(tables, stackTrace, category), maxStackTraceFrames)
    }

    internal fun getStack(
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
            val (cat, sub) = tables.frameTable.getCategoryOfFrame(topFrame)
            val pref = if (stackTrace.size > 1) {
                getStack(
                    HashedList(stackTrace.array, stackTrace.start, stackTrace.end - 1),
                    maxStackTraceFrames - 1
                )
            } else {
                null
            }
            val index = frames.size
            prefix.add(pref)
            frames.add(topFrame)
            categories.add(cat)
            subcategories.add(sub)
            map[stackTrace] = index
        }
        return map[stackTrace]!!
    }

    internal fun getMiscStack(
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
            frames.add(tables.getMiscFrame(name, category, subcategory, isNative))
            prefix.size - 1
        }
    }

    fun toStackTable() =
        StackTable(frame = frames, prefix = prefix, category = categories, subcategory = subcategories)

    val size: Int
        get() = frames.size
}
