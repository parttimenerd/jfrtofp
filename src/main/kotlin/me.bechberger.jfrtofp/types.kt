/**
 * code for creating firefox profiler files,
 * should be enough to support all features of the profiler view
 *
 * the code (and its documentation) is an adaption of the type definition in the profiler view
 *
 * https://github.com/firefox-devtools/profiler/blob/main/src/types/profile.js
 */
package me.bechberger.jfrtofp

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

typealias Milliseconds = Double
typealias Microseconds = Double
typealias Seconds = Double

typealias IndexIntoStackTable = Int
typealias IndexIntoSamplesTable = Int
typealias IndexIntoRawMarkerTable = Int
typealias IndexIntoFrameTable = Int
typealias IndexIntoStringTable = Int
typealias IndexIntoFuncTable = Int
typealias IndexIntoResourceTable = Int
typealias IndexIntoLibs = Int
typealias IndexIntoNativeSymbolTable = Int
typealias IndexIntoCategoryList = Int
typealias IndexIntoSubcategoryListForCategory = Int
typealias resourceTypeEnum = Int
typealias ThreadIndex = Int

// The Tid is most often a Int. However in some cases such as merged profiles
// we could generate a g.
typealias Tid = Long
typealias IndexIntoJsTracerEvents = Int
typealias CounterIndex = Int
typealias TabID = Int
typealias InnerWindowID = Int

typealias Weight = Long

/**
 * If a pid is a number, then it is the int value that came from the profiler.
 * However, if it is a g, then it is an unique value generated during
 * the profile processing. This happens for older profiles before the pid was
 * collected, or for merged profiles.
 */
typealias Pid = Long

typealias Bytes = Long

// An address, in bytes, relative to a library. The library that the address
// is relative to is usually given by the context in some way.
// Also called a library-relative offset.
// The vast majority of addresses that we deal with in profiler code are in this
// form, rather than in the absolute MemoryOffset form.
typealias Address = Long

/**
 * This type is equivalent to {[String]: T} for an object created without a prototype,
 * e.g. Object.create(null).
 *
 * See: https://github.com/facebook/flow/issues/4967#issuecomment-402355640
 */
typealias ObjectMap<T> = Map<String, T>

/**
 * The stack table stores the tree of stack nodes of a thread.
 * The shape of the tree is encoded in the prefix column: Root stack nodes have
 * null as their prefix, and every non-root stack has the stack index of its
 * "caller" / "parent" as its prefix.
 * Every stack node also has a frame and a category.
 * A "call stack" is a list of frames. Every stack index in the stack table
 * represents such a call stack; the "list of frames" is obtained by walking
 * the path in the tree from the root to the given stack node.
 *
 * Stacks are used in the thread's samples; each sample refers to a stack index.
 * Stacks can be shared between samples.
 *
 * With this representation, every sample only needs to store a single integer
 * to identify the sample's stack.
 * We take advantage of the fact that many call stacks in the profile have a
 * shared prefix; storing these stacks as a tree saves a lot of space compared
 * to storing them as actual lists of frames.
 *
 * The category of a stack node is always non-null and is derived from a stack's
 * frame and its prefix. Frames can have null categories, stacks cannot. If a
 * stack's frame has a null category, the stack inherits the category of its
 * prefix stack. Root stacks whose frame has a null stack have their category
 * set to the "default category". (The default category is currently defined as
 * the category in the profile's category list whose color is "grey", and such
 * a category is required to be present.)
 *
 * You could argue that the stack table's category column is derived data and as
 * such doesn't need to be stored in the profile itself. This is true, but
 * storing this information in the stack table makes it a lot easier to carry
 * it through various transforms that we apply to threads.
 * For example, here's a case where a stack's category is not recoverable from
 * any other information in the transformed thread:
 * In the call path
 *   someJSFunction [JS] -> Node.insertBefore [DOM] -> nsAttrAndChildArray::InsertChildAt,
 * the stack node for nsAttrAndChildArray::InsertChildAt should inherit the
 * category DOM from its "Node.insertBefore" prefix stack. And it should keep
 * the DOM category even if you apply the "Merge node into calling function"
 * transform to Node.insertBefore. This transform removes the stack node
 * "Node.insertBefore" from the stackTable, so the information about the DOM
 * category would be lost if it wasn't inherited into the
 * nsAttrAndChildArray::InsertChildAt stack before transforms are applied.
 */
@Serializable
data class StackTable(
    val frame: List<IndexIntoFrameTable>,
    // Imported profiles may not have categories. In this case fill the array with 0s.
    val category: List<IndexIntoCategoryList>,
    val subcategory: List<IndexIntoSubcategoryListForCategory>,
    val prefix: List<IndexIntoStackTable?>,
    @Required
    val length: Int = category.size
)

/**
 * Profile samples can come in a variety of forms and represent different information.
 * The Gecko Profiler by default uses sample counts, as it samples on a fixed interval.
 * These samples are all weighted equally by default, with a weight of one. However in
 * comparison profiles, some weights are negative, creating a "diff" profile.
 *
 * In addition, tracing formats can fit into the sample-based format by reporting
 * the "self time" of the profile. Each of these "self time" samples would then
 * provide the weight, in duration. Currently, the tracing format assumes that
 * the timing comes in milliseconds (see 'tracing-ms') but if needed, microseconds
 * or nanoseconds support could be added.
 *
 * e.g. The following tracing data could be represented as samples:
 *
 *     0 1 2 3 4 5 6 7 8 9 10
 *     | | | | | | | | | | |
 *     - - - - - - - - - - -
 *     A A A A A A A A A A A
 *         B B D D D D
 *         C C E E E E
 *                                     .
 * This chart represents the self time.
 *
 *     0 1 2 3 4 5 6 7 8 9 10
 *     | | | | | | | | | | |
 *     A A C C E E E E A A A
 *
 * And finally this is what the samples table would look like.
 *
 *     SamplesTable = {
 *       time:   [0,   2,   4, 8],
 *       stack:  [A, ABC, ADE, A],
 *       weight: [2,   2,   4, 3],
 *     }
 */
@Serializable
enum class WeightType {
    @SerialName("samples")
    SAMPLES,

    @SerialName("tracing-ms")
    TRACING,

    @SerialName("bytes")
    BYTES
}

@Serializable(with = SamplesLikeTableSerializer::class)
interface SamplesLikeTable {
    val stack: List<IndexIntoStackTable?>
    val time: List<Milliseconds>

    // An optional weight array. If not present, then the weight is assumed to be 1.
    // See the WeightType type for more information.
    val weight: List<Weight>?
    val weightType: WeightType
    val length: Int
}

fun SamplesLikeTable?.isNullOrEmpty(): Boolean {
    return this == null || this.length == 0
}

/**
 * The Gecko Profiler records samples of what function was currently being executed, and
 * the callstack that is associated with it. This is done at a fixed but configurable
 * rate, e.g. every 1 millisecond. This table represents the minimal amount of
 * information that is needed to represent that sampled function. Most of the entries
 * are indices into other tables.
 */
@Serializable
data class SamplesTable(
    override val stack: List<IndexIntoStackTable?>,
    // Responsiveness is the older version of eventDelay. It injects events every 16ms.
    // This is optional because newer profiles don't have that field anymore.
    // val responsiveness: List<?Milliseconds>,
    // Event delay is the newer version of responsiveness. It allow us to get a finer-grained
    // view of jank by inferring what would be the delay of a hypothetical input event at
    // any point in time. It requires a pre-processing to be able to visualize properly.
    // This is optional because older profiles didn't have that field.
    // TODO: what does this mean?
    val eventDelay: List<Milliseconds> = List(stack.size) { 0.0 },
    override val time: List<Milliseconds>,
    // An optional weight array. If not present, then the weight is assumed to be 1.
    // See the WeightType type for more information.
    override val weight: List<Weight>? = null,
    override val weightType: WeightType = WeightType.SAMPLES,
    // CPU usage value of the current thread. Its values are null only if the back-end
    // fails to get the CPU usage from operating system.
    // It's landed in Firefox 86, and it is optional because older profile
    // versions may not have it or that feature could be disabled. No upgrader was
    // written for this change because it's a completely new data source.
    //
    // in ms,    delta[i] = [time[i] - time[i - 1]] * [usage in this interval]
    val threadCPUDelta: List<Milliseconds?>? = null,
    // This property isn't present in normal threads. However it's present for
    // merged threads, so that we know the origin thread for these samples.
    val threadId: List<Tid>? = null,
    override val length: Int = time.size
) : SamplesLikeTable

/**
 * JS allocations are recorded as a marker payload, but in profile processing they
 * are moved to the Thread. This allows them to be part of the stack processing pipeline.
 *
 * NOTE: use it for Java allocations?
 */
@Serializable
data class JsAllocationsTable(
    override val time: List<Milliseconds>,
    val className: List<String>,
    @Required
    val typeName: List<String> = List(className.size) { "JSObject" }, // Currently only 'JSObject'
    @Required
    val coarseType: List<String> = List(className.size) { "Object" }, // Currently only 'Object',
    // "weight" is used here rather than "bytes", so that this type will match the
    // SamplesLikeTableShape.
    override val weight: List<Bytes>,
    @Required
    override val weightType: WeightType = WeightType.BYTES,
    @Required
    val inNursery: List<Bytes> = List(className.size) { 0 },
    override val stack: List<IndexIntoStackTable?>,
    @Required
    override val length: Int = className.size
) : SamplesLikeTable

/**
 * Native allocations are recorded as a marker payload, but in profile processing they
 * are moved to the Thread. This allows them to be part of the stack processing pipeline.
 * Currently they include native allocations and deallocations. However, both
 * of them are sampled independently, so they will be unbalanced if summed togther.
 */
@Serializable
data class NativeAllocationsTable(
    override val time: List<Milliseconds>,
    // "weight" is used here rather than "bytes", so that this type will match the
    // SamplesLikeTableShape.
    override val weight: List<Bytes>,
    @Required
    override val weightType: WeightType = WeightType.BYTES,
    override val stack: List<IndexIntoStackTable?>,
    @Required
    override val length: Int = time.size,
    val threadId: List<Tid>? = null
) : SamplesLikeTable

object SamplesLikeTableSerializer : JsonContentPolymorphicSerializer<SamplesLikeTable>(SamplesLikeTable::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "inNursery" in element.jsonObject -> JsAllocationsTable.serializer()
        "bytes" in element.jsonObject -> NativeAllocationsTable.serializer()
        else -> SamplesTable.serializer()
    }
}

/**
 * This is the base abstract class that marker payloads inherit from. This probably isn't
 * used directly in profiler.firefox.com, but is provided here for mainly documentation
 * purposes.
 */
@Serializable
data class ProfilerMarkerPayload(
    val type: String,
    val startTime: Milliseconds? = null,
    val endTime: Milliseconds? = null,
    val stack: Thread? = null
)

/**
 * Markers represent arbitrary events that happen within the browser. They have a
 * name, time, and potentially a JSON data payload. These can come from all over the
 * system. For instance Paint markers instrument the rendering and layout process.
 * Engineers can easily add arbitrary markers to their code without coordinating with
 * profiler.firefox.com to instrument their code.
 *
 * In the profile, these markers are raw and unprocessed. In the marker selectors, we
 * can run them through a processing pipeline to match up start and end markers to
 * create markers with durations, or even take a g-only marker and parse
 * it into a structured marker.
 */
@Serializable
data class RawMarkerTable(
    val data: List<Map<String, JsonElement>>,
    val name: List<IndexIntoStringTable>,
    val startTime: List<Milliseconds?>,
    val endTime: List<Milliseconds?>,
    val phase: List<MarkerPhase>,
    val category: List<IndexIntoCategoryList>,
    // This property isn't present in normal threads. However it's present for
    // merged threads, so that we know the origin thread for these markers.
    val threadId: List<Tid>? = null,
    @Required
    val length: Int = data.size
)

/**
 * Frames contain the context information about the function execution at the moment in
 * time. The caller/callee relationship between frames is defined by the StackTable.
 */
@Serializable
data class FrameTable(
    val category: List<IndexIntoCategoryList?>,
    val subcategory: List<IndexIntoSubcategoryListForCategory?>,
    val func: List<IndexIntoFuncTable>,
    val line: List<Int?>,
    @Required
    var length: Int = func.size,

    // all of the other properties are not important (for now)

    // If this is a frame for native code, the address is the address of the frame's
    // assembly instruction,  relative to the native library that contains it.
    //
    // For frames obtained from stack walking, the address points into the call instruction.
    // It is not a return address, it is a "nudged" return address (i.e. return address
    // minus one byte). This is different from the Gecko profile format. The conversion
    // is performed at the end of profile processing. See the big comment above
    // nudgeReturnAddresses for more details.
    //
    // The library which this address is relative to is given by the frame's nativeSymbol:
    // frame -> nativeSymbol -> lib.
    // default is -1
    @Required
    val address: List<Address> = List(length) { -1L },

    // The inline depth for this frame. If there is an inline stack at an address,
    // we create multiple frames with the same address, one for each depth.
    // The outermost frame always has depth 0.
    //
    // Example:
    // If the raw stack is 0x10 -> 0x20 -> 0x30, and symbolication adds two inline frames
    // for 0x10, no inline frame for 0x20, and one inline frame for 0x30, then the
    // symbolicated stack will be the following:
    //
    // func:        outer1 -> inline1a -> inline1b -> outer2 -> outer3 -> inline3a
    // address:     0x10   -> 0x10     -> 0x10     -> 0x20   -> 0x30   -> 0x30
    // inlineDepth:    0   ->    1     ->    2     ->    0   ->    0   ->    1
    //
    // Background:
    // When a compiler performs an inlining optimization, it removes a call to a function
    // and instead generates the code for the called function directly into the outer
    // function. But it remembers which instructions were the result of this inlining,
    // so that information about the inlined function can be recovered from the debug
    // information during symbolication, based on the instruction address.
    // The compiler can choose to do inlining multiple levels deep: An instruction can
    // be the result of a whole "inline stack" of functions.
    // Before symbolication, all frames have depth 0. During symbolication, we resolve
    // addresses to inline stacks, and create extra frames with non-zero depths as needed.
    //
    // The frames of an inline stack at an address all have the same address and the same
    // nativeSymbol, but each has a different func and line.
    @Required
    val inlineDepth: List<Int> = List(length) { 0 },

    // The symbol index (referring into this thread's nativeSymbols table) corresponding
    // to symbol that covers the frame address of this frame. Only non-null for native
    // frames (e.g. C / C++ / Rust code). Null before symbolication.
    @Required
    val nativeSymbol: List<IndexIntoNativeSymbolTable?> = List(length) { null },

    // Inner window ID of JS frames. JS frames can be correlated to a Page through this value.
    // It's used to determine which JS frame belongs to which web page so we can display
    // that information and filter for single tab profiling.
    // `0` for non-JS frames and the JS frames that failed to get the ID. `0` means "null value"
    // because that's what Firefox platform DOM side assigns when it fails to get the ID or
    // something bad happens during that process. It's not `null` or `-1` because that information
    // is being stored as `uint64_t` there.
    @Required
    val innerWindowID: List<InnerWindowID?> = List(length) { null },

    @Required
    val implementation: List<IndexIntoStringTable?> = List(length) { null },
    @Required
    val column: List<Int?> = List(length) { null },
    @Required
    val optimizations: List<Int?> = List(line.size) { null }
)

/**
 * The funcTable stores the functions that were called in the profile.
 * These can be native functions (e.g. C / C++ / rust), JavaScript functions, or
 * "label" functions. Multiple frames can have the same function: The frame
 * represents which part of a function was being executed at a given moment, and
 * the function groups all frames that occurred inside that function.
 * Concretely, for native code, each encountered instruction address is a separate
 * frame, and the function groups all instruction addresses which were symbolicated
 * with the same function name.
 * For JS code, each encountered line/column in a JS file is a separate frame, and
 * the function represents an entire JS function which can span multiple lines.
 *
 * Funcs that are orphaned, i.e. funcs that no frame refers to, do not have
 * meaningful values in their fields. Symbolication will cause many funcs that
 * were created upfront to become orphaned, as the frames that originally referred
 * to them get reassigned to the canonical func for their actual function.
 */
@Serializable
data class FuncTable(
    // The function name.
    val name: List<IndexIntoStringTable>,

    // isJS and relevantForJS describe the function type. Non-JavaScript functions
    // can be marked as "relevant for JS" so that for example DOM API label functions
    // will show up in any JavaScript stack views.
    // It may be worth combining these two fields into one:
    // https://github.com/firefox-devtools/profiler/issues/2543
    // NOTE: use for "own code" vs "JVM/library/native code" (depending on the configuration)
    val isJS: List<Boolean>,
    val relevantForJS: List<Boolean>,

    // The resource describes "Which bag of code did this function come from?".
    // For JS functions, the resource is of type addon, webhost, otherhost, or url.
    // For native functions, the resource is of type library.
    // For labels and for other unidentified functions, we set the resource to -1.
    val resource: List<IndexIntoResourceTable>,

    // These are non-null for JS functions only. The line and column describe the
    // location of the *start* of the JS function. As for the information about which
    // which lines / columns inside the function were actually hit during execution,
    // that information is stored in the frameTable, not in the funcTable.
    val fileName: List<IndexIntoStringTable?>,
    @Required
    var length: Int = name.size,
    @Required
    val lineNumber: List<Int?> = List(length) { null },
    @Required
    val columnNumber: List<Int?> = List(length) { null }
)

/**
 * The nativeSymbols table stores the addresses and symbol names for all symbols
 * that were encountered by frame addresses in this thread. This table can
 * contain symbols from multiple libraries, and the symbols are in arbitrary
 * order.
 * Note: Despite the similarity in name, this table is not what's usually
 * considered a "symbol table" - normally, a "symbol table" is something that
 * contains *all* symbols of a given library. But this table only contains a
 * subset of those symbols, and mixes symbols from multiple libraries.
 */
@Serializable
data class NativeSymbolTable(
    // The library that this native symbol is in.
    val libIndex: List<IndexIntoLibs>,
    // The library-relative offset of this symbol.
    val address: List<Address>,
    // The symbol name, demangled.
    val name: List<IndexIntoStringTable>,

    // This would be a good spot for a "size" field. But the symbolication API does
    // not give us information about the size of a function.
    // https://github.com/mstange/profiler-get-symbols/issues/17

    @Required
    var length: Int = name.size
)

/**
 * The ResourceTable holds additional information about functions. It tends to contain
 * sparse arrays. Multiple functions can point to the same resource.
 */
@Serializable
data class ResourceTable(
    val name: List<IndexIntoStringTable>,
    @Required
    val length: Int = name.size,
    @Required
    val lib: List<IndexIntoLibs?> = List(length) { null },
    val host: List<IndexIntoStringTable?>,
    /** 0: unknown, library: 1, addon: 2, webhost: 3, otherhost: 4, url: 5 */
    val type: List<resourceTypeEnum>
)

/**
 * Information about the shared libraries that were loaded into the processes in
 * the profile. This information is needed during symbolication. Most importantly,
 * the symbolication API requires a debugName + breakpadId for each set of
 * unsymbolicated addresses, to know where to obtain symbols for those addresses.
 */
@Serializable
data class Lib(
    val arch: String, // e.g. "x86_64"
    val name: String, // e.g. "firefox"
    val path: String, // e.g. "/Applications/FirefoxNightly.app/Contents/MacOS/firefox"
    val debugName: String, // e.g. "firefox", or "firefox.pdb" on Windows
    val debugPath: String, // e.g. "/Applications/FirefoxNightly.app/Contents/MacOS/firefox"
    val breakpadId: String, // e.g. "E54D3AF274383256B9F6144F83F3F7510"

    // The codeId is currently always null.
    // In the future, it will have the following values:
    //  - On macOS, it will still be null.
    //  - On Linux / Android, it will have the full GNU build id. (The breakpadId
    //    is also based on the build id, but truncates some information.)
    //    This lets us obtain unstripped system libraries on Linux distributions
    //    which have a "debuginfod" server, and we can use those unstripped binaries
    //    for symbolication.
    //  - On Windows, it will be the codeId for the binary (.exe / .dll), as used
    //    by Windows symbol servers. This will allow us to get assembly code for
    //    Windows system libraries for profiles which were captured on another machine.
    @Required
    val codeId: String? = null // e.g. "6132B96B70fd000"
)

@Serializable
data class Category(
    val name: String,
    val color: String,
    val subcategories: List<String>
)

typealias CategoryList = List<Category>

/**
 * A Page describes the page the browser profiled. In Firefox, TabIDs represent the
 * ID that is shared between multiple frames in a single tab. The Inner Window IDs
 * represent JS `window` objects in each Document. And they are unique for each frame.
 * That's why it's enough to keep only inner Window IDs inside marker payloads.
 * 0 means null(no embedder) for Embedder Window ID.
 *
 * The unique field for a page is innerWindowID.
 */
@Serializable
data class Page(
    // Tab ID of the page. This ID is the same for all the pages inside a tab's
    // session history.
    val tabID: TabID,
    // ID of the JS `window` object in a `Document`. It's unique for every page.
    val innerWindowID: InnerWindowID,
    // Url of this page.
    val url: String,
    // Each page describes a frame in websites. A frame can either be the top-most
    // one or inside of another one. For the children frames, `embedderInnerWindowID`
    // points to the innerWindowID of the parent (embedder). It's `0` if there is
    // no embedder, which means that it's the top-most frame. That way all pages
    // can create a tree of pages that can be navigated.
    val embedderInnerWindowID: Int,
    // If true, this page has been opened in a private browsing window.
    // It's optional because it appeared in Firefox 98, and is absent before when
    // capturing was disabled when a private browsing window was open.
    // The property is always present in Firefox 98+.
    @Required
    val isPrivateBrowsing: Boolean = false
)

typealias PageList = List<Page>

@Serializable
enum class PauseReason {
    @SerialName("profiler-paused")
    PROFILER_PAUSED,

    @SerialName("collecting")
    COLLECTING
}

/**
 * Information about a period of time during which no samples were collected.
 */
@Serializable
data class PausedRange(
    // null if the profiler was already paused at the beginning of the period of
    // time that was present in the profile buffer
    val startTime: Milliseconds?,
    // null if the profiler was still paused when the profile was captured
    val endTime: Milliseconds?,
    val reason: PauseReason
)

@Serializable
data class JsTracerTable(
    val events: List<IndexIntoStringTable>,
    val timestamps: List<Microseconds>,
    val durations: List<Microseconds?>,
    val line: List<Int?>, // Line number.
    val column: List<Int?>, // Column number.
    @Required
    val length: Int = column.size
)

@Serializable
data class CounterSamplesTable(
    val time: List<Milliseconds>,
    // The number of times the Counter's "number" was changed since the previous sample.
    val number: List<Int>,
    /* The count of the data, for instance for memory this would be bytes.
       real count[i] = sum(count[0], ..., count[i])*/
    val count: List<Long>,
    @Required
    val length: Int = count.size
)

@Serializable
data class SampleGroup(
    val id: Int,
    val samples: CounterSamplesTable
)

@Serializable
data class Counter(
    val name: String,
    /** currently supported: Memory */
    val category: String,
    val description: String,
    val pid: Pid,
    val mainThreadIndex: ThreadIndex,
    val sampleGroups: List<SampleGroup>
)

/**
 * The statistics about profiler overhead. It includes max/min/mean values of
 * individual and overall overhead timings.
 */
@Serializable
data class ProfilerOverheadStats(
    val maxCleaning: Microseconds,
    val maxCounter: Microseconds,
    val maxInterval: Microseconds,
    val maxLockings: Microseconds,
    val maxOverhead: Microseconds,
    val maxThread: Microseconds,
    val meanCleaning: Microseconds,
    val meanCounter: Microseconds,
    val meanInterval: Microseconds,
    val meanLockings: Microseconds,
    val meanOverhead: Microseconds,
    val meanThread: Microseconds,
    val minCleaning: Microseconds,
    val minCounter: Microseconds,
    val minInterval: Microseconds,
    val minLockings: Microseconds,
    val minOverhead: Microseconds,
    val minThread: Microseconds,
    val overheadDurations: Microseconds,
    val overheadPercentage: Microseconds,
    val profiledDuration: Microseconds,
    val samplingCount: Microseconds
)

/**
 * This object represents the configuration of the profiler when the profile was recorded.
 */
@Serializable
data class ProfilerConfiguration(
    val threads: List<String>,
    val features: List<String>,
    val capacity: Bytes,
    val duration: Milliseconds? = null,
    // Optional because that field is introduced in Firefox 72.
    // Active Tab ID indicates a Firefox tab. That field allows us to
    // create an "active tab view".
    // `0` means null value. Firefox only outputs `0` and not null, that's why we
    // should take care of this case while we are consuming it. If it's `0`, we
    // should revert back to the full view since there isn't enough data to show
    // the active tab view.
    val activeTabID: TabID? = null
)

/**
 * Gecko Profiler records profiler overhead samples of specific tasks that take time.
 * counters: Time spent during collecting counter samples.
 * expiredMarkerCleaning: Time spent during expired marker cleanup
 * lockings: Time spent during acquiring locks.
 * threads: Time spent during threads sampling and marker collection.
 */
@Serializable
data class ProfilerOverheadSamplesTable(
    val counters: List<Microseconds>,
    val expiredMarkerCleaning: List<Microseconds>,
    val locking: List<Microseconds>,
    val threads: List<Microseconds>,
    val time: List<Milliseconds>,
    @Required
    val length: Int = time.size
)

/**
 * Information about profiler overhead. It includes overhead timings for
 * counters, expired marker cleanings, mutex locking and threads. Also it
 * includes statistics about those individual and overall overhead.
 */
@Serializable
data class ProfilerOverhead(
    val samples: ProfilerOverheadSamplesTable,
    // There is no statistics object if there is no sample.
    val statistics: ProfilerOverheadStats? = null,
    val pid: Pid,
    val mainThreadIndex: ThreadIndex
)

typealias StringTable = List<String>

/**
 * Gecko has one or more processes. There can be multiple threads per processes. Each
 * thread has a unique set of tables for its data.
 *
 * might be the SerializableThread class
 */
@Serializable
data class Thread(
    /*
    This list of process types is defined here:
    https://searchfox.org/mozilla-central/rev/819cd31a93fd50b7167979607371878c4d6f18e8/xpcom/build/nsXULAppAPI.h#383
    | 'default'
    | 'plugin'
    | 'tab'
    | 'ipdlunittest'
    | 'geckomediaplugin'
    | 'gpu'
    | 'pdfium'
    | 'vr'
    // Unknown process type:
    // https://searchfox.org/mozilla-central/rev/819cd31a93fd50b7167979607371878c4d6f18e8/toolkit/xre/nsEmbedFunctions.cpp#232
    | 'invalid'
    */
    @Required
    val processType: String = "default",
    val processStartupTime: Milliseconds,
    val processShutdownTime: Milliseconds?,
    val registerTime: Milliseconds,
    val unregisterTime: Milliseconds?,
    val pausedRanges: List<PausedRange>,
    val name: String,
    /*
    The eTLD+1 of the isolated content process if provided by the back-end.
    It will be undefined if:
    - Fission is not enabled.
    - It's not an isolated content process.
    - It's a sanitized profile.
    - It's a profile from an older Firefox which doesn't include this field (introduced in Firefox 80).
    */
    val `eTLD+1`: String? = null,
    val processName: String? = null,
    val isJsTracer: Boolean? = null,
    val pid: Pid,
    val tid: Tid,
    val samples: SamplesTable,
    /* this table cannot be empty */
    val jsAllocations: JsAllocationsTable? = null,
    val nativeAllocations: NativeAllocationsTable? = null,
    val markers: RawMarkerTable,
    val stackTable: StackTable,
    val frameTable: FrameTable,
    /*
    Strings for profiles are collected into a single table, and are referred to by
    their index by other tables.

    but the gTable is apparently not required
    */
    val gTable: List<String>,
    val funcTable: FuncTable,
    // val stringTable: StringTable,
    val stringArray: List<String>,
    val resourceTable: ResourceTable,
    val nativeSymbols: NativeSymbolTable,
    val jsTracer: JsTracerTable? = null,
    /*
    If present and true, this thread was launched for a private browsing session only.
    When false, it can still contain private browsing data if the profile was
    captured in a non-fission browser.
    It's absent in Firefox 97 and before, or in Firefox 98+ when this thread
    had no extra attribute at all.
    */
    val isPrivateBrowsing: Boolean? = null,
    /*
    If present and non-0, the number represents the container this thread was loaded in.
    It's absent in Firefox 97 and before, or in Firefox 98+ when this thread
    had no extra attribute at all.
    */
    val userContextId: Int? = null
) {
    init {
        assert(jsAllocations.isNullOrEmpty())
        assert(nativeAllocations.isNullOrEmpty())
        assert(samples.isNullOrEmpty())
    }
}

@Serializable
data class ExtensionTable(
    val baseURL: List<String>,
    val id: List<String>,
    val name: List<String>,
    @Required
    val length: Int = name.size
)

/**
 * Visual progress describes the visual progression during page load. A sample is generated
 * everytime the visual completeness of the webpage changes.
 */
@Serializable
data class ProgressGraphData(
    // A percentage that describes the visual completeness of the webpage, ranging from 0% - 100%
    val percent: Double,
    // The time in milliseconds which the sample was taken.
    val timestamp: Milliseconds
)

/**
 * Visual metrics are performance metrics that measure above-the-fold webpage visual performance,
 * and more accurately describe how human end-users perceive the speed of webpage loading. These
 * metrics serves as additional metrics to the typical page-level metrics such as onLoad. Visual
 * metrics contains key metrics such as Speed Index, Perceptual Speed Index, and ContentfulSpeedIndex,
 * along with their visual progression. These properties find their way into the gecko profile through running
 * browsertime, which is a tool that lets you collect performance metrics of your website.
 * Browsertime provides the option of generating a gecko profile, which then embeds these visual metrics
 * into the geckoprofile. More information about browsertime can be found through https://github.com/sitespeedio/browsertime.
 * These values are generated only when browsertime is run with --visualMetrics.
 *
 * Most values from this object are generated by the python script https://github.com/sitespeedio/browsertime/blob/6e88284930c1d3ded8d9d95252d2e13c252d361c/browsertime/visualmetrics.py
 * Especially look at the function calculate_visual_metrics.
 * Then the script is called and the result processed from the JS files in https://github.com/sitespeedio/browsertime/tree/6e88284930c1d3ded8d9d95252d2e13c252d361c/lib/video/postprocessing/visualmetrics
 * and https://github.com/sitespeedio/browsertime/blob/6e88284930c1d3ded8d9d95252d2e13c252d361c/lib/core/engine/iteration.js#L261-L264.
 * Finally they're inserted into the JSON profile in https://github.com/sitespeedio/browsertime/blob/6e88284930c1d3ded8d9d95252d2e13c252d361c/lib/firefox/webdriver/firefox.js#L215-L230
 */
@Serializable
data class VisualMetrics(
    val FirstVisualChange: Int,
    val LastVisualChange: Int,
    val SpeedIndex: Int,
    val VisualProgress: List<ProgressGraphData>,
    // Contentful and Perceptual values may be missing. They're generated only if
    // the user specifies the options --visualMetricsContentful and
    // --visualMetricsPerceptual in addition to --visualMetrics.
    val ContentfulSpeedIndex: Int? = null,
    val ContentfulSpeedIndexProgress: List<ProgressGraphData>? = null,
    val PerceptualSpeedIndex: Int? = null,
    val PerceptualSpeedIndexProgress: List<ProgressGraphData>? = null,
    // VisualReadiness and VisualCompleteXX values are generated in
    // https://github.com/sitespeedio/browsertime/blob/main/lib/video/postprocessing/visualmetrics/extraMetrics.js
    val VisualReadiness: Int,
    val VisualComplete85: Int,
    val VisualComplete95: Int,
    val VisualComplete99: Int
)

// Units of ThreadCPUDelta values for different platforms.
@Serializable
enum class ThreadCPUDeltaUnit {
    @SerialName("ns")
    NS,

    @SerialName("µs")
    US,

    @SerialName("variable CPU cycles")
    VARIABLE_CPU_CYCLES
}

// Object that holds the units of samples table values. Some of the values can be
// different depending on the platform, e.g. threadCPUDelta.
// See https://searchfox.org/mozilla-central/rev/851bbbd9d9a38c2785a24c13b6412751be8d3253/tools/profiler/core/platform.cpp#2601-2606
@Serializable
data class SampleUnits(
    @Required
    val time: String = "ms",
    @Required
    val eventDelay: String = "ms",
    val threadCPUDelta: ThreadCPUDeltaUnit
)

/**
 * Meta information associated for the entire profile.
 */
@Serializable
data class ProfileMeta(
    val arguments: String,
    /** The interval at which the threads are sampled. */
    val interval: Milliseconds,
    /* The number of milliseconds since midnight January 1, 1970 GMT. */
    val startTime: Milliseconds,
    val endTime: Milliseconds,
    /*
    The process type where the Gecko profiler was started. This is the raw enum
    numeric value as defined here:
    https://searchfox.org/mozilla-central/rev/819cd31a93fd50b7167979607371878c4d6f18e8/xpcom/build/nsXULAppAPI.h#365
    */
    @Required
    val processType: Int = 0,
    /*
    The extensions property landed in Firefox 60, and is only optional because older
    processed profile versions may not have it. No upgrader was written for this change.
    */
    val extensions: ExtensionTable? = null,
    /*
    The list of categories as provided by the platform. The categories are present for
    all Firefox profiles, but imported profiles may not include any category support.
    The front-end will provide a default list of categories, but the saved profile
    will not include them.
    */
    val categories: CategoryList? = null,
    /* The name of the product, most likely "Firefox". */
    val product: String,
    /*
    This value represents a boolean, but for some reason is written out as an int value.
    It's 0 for the stack walking feature being turned off, and 1 for stackwalking being
    turned on.
    */
    val stackwalk: Int,
    // A boolean flag indicating whether the profiled application is using a debug build.
    // It's false for opt builds, and true for debug builds.
    // This property is optional because older processed profiles don't have this but
    // this property was added to Firefox a long time ago. It should work on older Firefox
    // versions without any problem.
    val debug: Boolean? = null,
    // This is the Gecko profile format version (the unprocessed version received directly
    // from the browser.)
    @Required
    val version: Int = 25,
    // This is the processed profile format version.
    @Required
    val preprocessedProfileVersion: Int = 41,

    // The following fields are most likely included in Gecko profiles, but are marked
    // optional for imported or converted profiles.

    // The XPCOM ABI (Application Binary Interface) name, taking the form:
    // {CPU_ARCH}-{TARGET_COMPILER_ABI} e.g. "x86_64-gcc3"
    // See https://developer.mozilla.org/en-US/docs/Mozilla/Tech/XPCOM/XPCOM_ABI
    val abi: String? = null,
    // The "misc" value of the browser's user agent, typically the revision of the browser.
    // e.g. "rv:63.0", which would be Firefox 63.0
    // See https://searchfox.org/mozilla-central/rev/819cd31a93fd50b7167979607371878c4d6f18e8/netwerk/protocol/http/nsHttpHandler.h#543
    val misc: String? = null,
    // The OS and CPU. e.g. "Intel Mac OS X"
    val oscpu: String? = null,
    // The current platform, as taken from the user agent String.
    // See https://searchfox.org/mozilla-central/rev/819cd31a93fd50b7167979607371878c4d6f18e8/netwerk/protocol/http/nsHttpHandler.cpp#992
    //  | 'Android' // It usually has the version embedded in the String
    //    | 'Windows'
    //    | 'Macintosh'
    //    // X11 is used for historic reasons, but this value means that it is a Unix platform.
    //    | 'X11'
    val platform: String? = null,
    // The widget toolkit used for GUI rendering.
    // Older versions of Firefox for Linux had the 2 flavors gtk2/gtk3, and so
    // we could find the value "gtk3".
    // 'gtk' | 'gtk3' | 'windows' | 'cocoa' | 'android' | String
    val toolkit: String? = null,

    // The appBuildID, sourceURL, physicalCPUs and logicalCPUs properties landed
    // in Firefox 62, and are optional because older processed profile
    // versions may not have them. No upgrader was written for this change.

    // The build ID/date of the application.
    val appBuildID: String? = null,
    // The URL to the source revision for this build of the application.
    val sourceURL: String? = null,
    // The physical number of CPU cores for the machine.
    val physicalCPUs: Int? = null,
    // The amount of logically available CPU cores for the program.
    val logicalCPUs: Int? = null,
    // A boolean flag indicating whether we symbolicated this profile. If this is
    // false we'll start a symbolication process when the profile is loaded.
    // A missing property means that it's an older profile, it stands for an
    // "unknown" state.  For now we don't do much with it but we may want to
    // propose a manual symbolication in the future.
    @Required
    val symbolicated: Boolean? = true,
    @Required
    val symbolicationNotSupported: Boolean? = false,
    // The Update channel for this build of the application.
    // This property is landed in Firefox 67, and is optional because older
    // processed profile versions may not have them. No upgrader was necessary.
    //     | 'default' // Local builds
    //    | 'nightly'
    //    | 'nightly-try' // Nightly try builds for QA
    //    | 'aurora' // Developer Edition channel
    //    | 'beta'
    //    | 'release'
    //    | 'esr' // Extended Support Release channel
    //    | String,
    val updateChannel: String? = null,

    // Visual metrics contains additional performance metrics such as Speed Index,
    // Perceptual Speed Index, and ContentfulSpeedIndex. This is optional because only
    // profiles generated by browsertime will have this property. Source code for
    // browsertime can be found at https://github.com/sitespeedio/browsertime.
    val visualMetrics: VisualMetrics? = null,
    // The configuration of the profiler at the time of recording. Optional since older
    // versions of Firefox did not include it.
    val configuration: ProfilerConfiguration? = null,
    // Markers are displayed in the UI according to a schema definition. See the
    // MarkerSchema type for more information.
    val markerSchema: List<MarkerSchema>,
    // Units of samples table values.
    // The sampleUnits property landed in Firefox 86, and is only optional because
    // older profile versions may not have it. No upgrader was written for this change.
    val sampleUnits: SampleUnits,
    // Information of the device that profile is captured from.
    // Currently it's only present for Android devices and it includes brand and
    // model names of that device.
    // It's optional because profiles from non-Android devices and from older
    // Firefox versions may not have it.
    // This property landed in Firefox 88.
    val device: String? = null,
    // Profile importers can optionally add information about where they are imported from.
    // They also use the "product" field in the meta information, but this is somewhat
    // ambiguous. This field, if present, is unambiguous that it was imported.
    val importedFrom: String? = null,

    @Required
    val usesOnlyOneStackType: Boolean? = true,
    @Required
    val doesNotUseFrameImplementation: Boolean? = true,
    @Required
    val sourceCodeIsNotOnSearchfox: Boolean? = true
)

/**
 * All data for a processed profile.
 *
 * might be the SerializableProfile class
 */
@Serializable
data class Profile(
    val meta: ProfileMeta,
    val libs: List<Lib>,
    val pages: PageList? = null,
    // The counters list is optional only because old profilers may not have them.
    // An upgrader could be written to make this non-optional.
    val counters: List<Counter>? = null,
    // The profilerOverhead list is optional only because old profilers may not
    // have them. An upgrader could be written to make this non-optional.
    // This is list because there is a profiler overhead per process.
    val profilerOverhead: ProfilerOverhead? = null,
    val threads: List<Thread>,
    val profilingLog: ProfilingLog? = null,
    val profileGatheringLog: ProfilingLog? = null
)

/*
Source: https://bugzilla.mozilla.org/show_bug.cgi?id=1676271

It would be useful to be able to store some extra profiling-related information in the output JSON profile,
 so that it can at least be present in the processed profile.meta in the front-end.
For example, one thing I'd like to record is when some sub-process doesn't respond to IPCs when gathering the profile.

This will be a free-form JSON object, intended to be read by people working on the profiler, and maybe a few advanced users.
In particular it should not be considered a "stable" source of data for the front-end; no processing should happen apart
from preserving it as-is in the final profile. Though we should add a way to mark identifying data so that it can be
stripped on demand.

If we later consider some of its information to be useful to our users, it should be moved to another location
in the profile, and the front-end could then display it in the proper manner.
*/
typealias ProcessProfilingLog = Map<String, String>
typealias ProfilingLog = Map<Pid, ProcessProfilingLog>

// ------- marker.js ---------

// Provide different formatting options for Strings.
@Serializable
enum class MarkerFormatType {
    // ----------------------------------------------------
    // String types.

    // Show the URL, and handle PII sanitization
    // TODO Handle PII sanitization. Issue #2757
    @SerialName("url")
    URL,

    // TODO Handle PII sanitization. Issue #2757
    // Show the file path, and handle PII sanitization.
    @SerialName("file-path")
    FILE_PATH,

    // Important, do not put URL or file path information here, as it will not be
    // sanitized. Please be careful with including other types of PII here as well.
    // e.g. "Label: Some String"
    @SerialName("string")
    STRING,

    // ----------------------------------------------------
    // Numeric types

    // Note: All time and durations are stored as milliseconds.

    // For time data that represents a duration of time.
    // e.g. "Label: 5s, 5ms, 5μs"
    @SerialName("duration")
    DURATION,

    // Data that happened at a specific time, relative to the start of
    // the profile. e.g. "Label: 15.5s, 20.5ms, 30.5μs"
    @SerialName("time")
    TIME,

    // The following are alternatives to display a time only in a specific
    // unit of time.
    @SerialName("seconds") // "Label: 5s"
    SECONDS,

    @SerialName("milliseconds") // "Label: 5ms"
    MILLISECONDS,

    @SerialName("microseconds") // "Label: 5μs"
    MICROSECONDS,

    @SerialName("nanoseconds") // "Label: 5ns"
    NANOSECONDS,

    // e.g. "Label: 5.55mb, 5 bytes, 312.5kb"
    @SerialName("bytes")
    BYTES,

    // This should be a value between 0 and 1.
    // "Label: 50%"
    @SerialName("percentage")
    PERCENTAGE,

    // The integer should be used for generic representations of Ints. Do not
    // use it for time information.
    // "Label: 52, 5,323, 1,234,567"
    @SerialName("integer")
    INTEGER,

    // The decimal should be used for generic representations of Ints. Do not
    // use it for time information.
    // "Label: 52.23, 0.0054, 123,456.78"
    @SerialName("decimal")
    DECIMAL
}

// A list of all the valid locations to surface this marker.
// We can be free to add more UI areas.
@Serializable
enum class MarkerDisplayLocation {
    @SerialName("marker-chart")
    MARKER_CHART,

    @SerialName("marker-table")
    MARKER_TABLE,

    // This adds markers to the main marker timeline in the header.
    @SerialName("timeline-overview")
    TIMELINE_OVERVIEW,

    // In the timeline, this is a section that breaks out markers that are related
// to memory. When memory counters are enabled, this is its own track, otherwise
// it is displayed with the main thread.
    @SerialName("timeline-memory")
    TIMELINE_MEMORY,

    // This adds markers to the IPC timeline area in the header.
    @SerialName("timeline-ipc")
    TIMELINE_IPC,

    // This adds markers to the FileIO timeline area in the header.
    @SerialName("timeline-fileio")
    TIMELINE_FILEIO,

    // TODO - This is not supported yet.
    @SerialName("stack-chart")
    STACK_CHART
}

@Serializable
data class MarkerTrackLineConfig(
    val key: String,
    val fillColor: String? = null,
    val strokeColor: String? = null,
    val width: Int? = null,
    // "line" or "bar"
    val type: String
)

@Serializable
data class MarkerTrackConfig(
    val label: String,
    /* small, medium, large */
    val height: String? = null,
    val lines: List<MarkerTrackLineConfig>,
    val isPreSelected: Boolean = false
)

@Serializable
data class MarkerSchema(
    // The unique identifier for this marker.
    val name: String, // e.g. "CC"

    // The label of how this marker should be displayed in the UI.
    // If none is provided, then the name is used.
    val tooltipLabel: String? = null, // e.g. "Cycle Collect"

    // This is how the marker shows up in the Marker Table description.
    // If none is provided, then the name is used.
    val tableLabel: String? = null, // e.g. "{marker.data.eventType} – DOMEvent"

    // This is how the marker shows up in the Marker Chart, where it is drawn
    // on the screen as a bar.
    // If none is provided, then the name is used.
    val chartLabel: String? = null,

    // The locations to display
    val display: List<MarkerDisplayLocation>,

    val data: List<MarkerSchemaData>,

    val trackConfig: MarkerTrackConfig? = null
)

@Serializable(with = MarkerSchemaDataSerializer::class)
sealed class MarkerSchemaData

@Serializable
data class MarkerSchemaDataString(
    val key: String,
    val label: String? = null,
    val format: MarkerFormatType,
    val searchable: Boolean? = null
) : MarkerSchemaData()

// This type is a static bit of text that will be displayed
@Serializable
data class MarkerSchemaDataStatic(
    val label: String,
    val value: String
) : MarkerSchemaData()

object MarkerSchemaDataSerializer : JsonContentPolymorphicSerializer<MarkerSchemaData>(MarkerSchemaData::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "format" in element.jsonObject -> MarkerSchemaDataString.serializer()
        else -> MarkerSchemaDataStatic.serializer()
    }
}

@Serializable
@SerialName("Text")
data class TextMarkerPayload(
    val name: String,
    val cause: CauseBacktrace? = null
) : MarkerPayload

typealias MarkerSchemaByName = ObjectMap<MarkerSchema>

/**
 * Markers can include a stack. These are converted to a cause backtrace, which includes
 * the time the stack was taken. Sometimes this cause can be async, and triggered before
 * the marker, or it can be synchronous, and the time is contained within the marker's
 * start and end time.
 */
@Serializable
data class CauseBacktrace(
    // `tid` is optional because older processed profiles may not have it.
    // No upgrader was written for this change.
    val tid: Tid? = null,
    val time: Milliseconds,
    val stack: IndexIntoStackTable
)

/**
 * This type holds data that should be synchronized across the various phases
 * associated with an IPC message.
 */
@Serializable
data class IPCSharedData(
    // Each of these fields comes from a specific marker corresponding to each
    // phase of an IPC message; since we can't guarantee that any particular
    // marker was recorded, all of the fields are optional.
    val startTime: Milliseconds? = null,
    val sendStartTime: Milliseconds? = null,
    val sendEndTime: Milliseconds? = null,
    val recvEndTime: Milliseconds? = null,
    val endTime: Milliseconds? = null,
    val sendTid: Tid? = null,
    val recvTid: Tid? = null,
    val sendThreadName: String? = null,
    val recvThreadName: String? = null
)

/**
 * Measurement for how long draw calls take for the compositor.
 */
@Serializable
data class GPUMarkerPayload(
    @Required
    val type: String = "gpu_time_query",
    val cpustart: Milliseconds,
    val cpuend: Milliseconds,
    val gpustart: Milliseconds, // always 0.
    val gpuend: Milliseconds // The time the GPU took to execute the command.
)

/**
 * These markers don't have a start and end time. They work in pairs, one
 * specifying the start, the other specifying the end of a specific tracing
 * marker.
 */

@Serializable
data class PaintProfilerMarkerTracing(
    @Required
    val type: String = "tracing",
    @Required
    val category: String = "Paint",
    val cause: CauseBacktrace? = null
)

@Serializable
data class ArbitraryEventTracing(
    @Required
    val type: String = "tracing",
    @Required
    val category: String
)

@Serializable
data class CcMarkerTracing(
    @Required
    val type: String = "tracing",
    @Required
    val category: String = "CC",
    val cause: CauseBacktrace? = null
)

@Serializable
data class GCSliceData(
    // Slice number within the GCMajor collection.
    val slice: Int,

    val pause: Milliseconds,

    // The reason for this slice.
    val reason: String,

    // The GC state at the start and end of this slice.
    val initial_state: String,
    val final_state: String,

    // The incremental GC budget for this slice (see pause above).
    val budget: String,

    // The number of the GCMajor that this slice belongs to.
    val major_gc_number: Int,

    // These are present if the collection was triggered by exceeding some
    // threshold.  The reason field says how they should be interpreted.
    val trigger_amount: Int? = null,
    val trigger_threshold: Int? = null,

    // The number of page faults that occured during the slice.  If missing
    // there were 0 page faults.
    val page_faults: Int? = null,

    val start_timestamp: Seconds,
    /* either times or phase times has to be present */
    val times: Map<String, Milliseconds>? = null,
    // only present in non-gecko
    val phase_times: Map<String, Microseconds>? = null
)

@Serializable(with = GCMajorStatusSerializer::class)
interface GCMajorStatus {
    val status: String
}

@Serializable
data class GCMajorAborted(
    override val status: String = "aborted"
) : GCMajorStatus

@Serializable
data class GCMajorCompleted(
    override val status: String = "completed",
    val max_pause: Milliseconds,

    // The sum of all the slice durations
    val total_time: Milliseconds,

    // The reason from the first slice. see JS::gcreason::Reason
    val reason: String,

    // Counts
    val zones_collected: Int,
    val total_zones: Int,
    val total_compartments: Int,
    val minor_gcs: Int,
    // Present when non-zero.
    val store_buffer_overflows: Int? = null,
    val slices: Int,

    // Timing for the SCC sweep phase.
    val scc_sweep_total: Milliseconds,
    val scc_sweep_max_pause: Milliseconds,

    // The reason why this GC ran non-incrementally. Older profiles could have the string
    // 'None' as a reason.
    val nonincremental_reason: String? = null,

    // The allocated space for the whole heap before the GC started.
    val allocated_bytes: Int,
    val post_heap_size: Int? = null,

    // Only present if non-zero.
    val added_chunks: Int? = null,
    val removed_chunks: Int? = null,

    // The number for the start of this GC event.
    val major_gc_number: Int,
    val minor_gc_number: Int,

    // Slice number isn't in older profiles.
    val slice_number: Int? = null,

    // This usually isn't present with the gecko profiler, but it's the same
    // as all of the slice markers themselves.
    val slices_list: List<GCSliceData>? = null,

    // MMU (Minimum mutator utilisation) A measure of GC's affect on
    // responsiveness  See Statistics::computeMMU(), these percentages in the
    // rage of 0-100.
    // Percentage of time the mutator ran in a 20ms window.
    val mmu_20ms: Double,
    // Percentage of time the mutator ran in a 50ms window.
    val mmu_50ms: Double,

    // The duration of each phase
    // only present in non-gecko
    val phase_times: Map<String, Microseconds>? = null,
    // only present in gecko
    val totals: Map<String, Milliseconds>
) : GCMajorStatus

interface MarkerPayload

@Serializable
@SerialName("GCMajor")
data class GCMajorMarkerPayload(
    val timings: GCMajorStatus
) : MarkerPayload

// skipping GCMajorMarkerPayload_Gecko
// and lots of other stuff, that we probably won't need.

object GCMajorStatusSerializer : JsonContentPolymorphicSerializer<GCMajorStatus>(GCMajorStatus::class) {
    override fun selectDeserializer(element: JsonElement) =
        when (element.jsonObject["status"]!!.jsonPrimitive.content) {
            "aborted" -> GCMajorAborted.serializer()
            "completed" -> GCMajorAborted.serializer()
            else -> throw IllegalArgumentException()
        }
}

@Serializable
@SerialName("JS allocation")
data class JsAllocationPayload(
    val className: String,
    val typeName: String, // Currently only 'JSObject'
    val coarseType: String, // Currently only 'Object',
    val size: Bytes,
    val inNursery: Boolean,
    val stack: GeckoMarkerStack
) : MarkerPayload

@Serializable
@SerialName("Native allocation")
data class NativeAllocationPayload(
    val size: Bytes,
    val stack: GeckoMarkerStack,
    val memoryAddress: Long? = null,
    val threadId: Long? = null
) : MarkerPayload

// gecko-profile.js

// These integral values are exported in the JSON of the profile, and are in the
// RawMarkerTable. They represent a C++ class in Gecko that defines the type of
// marker it is. These markers are then combined together to form the Marker[] type.
// See deriveMarkersFromRawMarkerTable for more information. Also see the constants.js
// file for JS values that can be used to refer to the different phases.
//
// From the C++:
//
// enum class MarkerPhase : int {
//   Instant = 0,
//   Interval = 1,
//   IntervalStart = 2,
//   IntervalEnd = 3,
// };
typealias MarkerPhase = Int

@Serializable
data class GeckoMarkerTuple(
    val name: IndexIntoStringTable,
    val startTime: Milliseconds?,
    val endTime: Milliseconds?,
    val phase: MarkerPhase,
    val category: Int,
    val data: MarkerPayload
)

@Serializable
data class GeckoMarkers(
    @Required
    val schema: Map<String, Int> = mapOf(
        "name" to 0,
        "startTime" to 1,
        "endTime" to 2,
        "phase" to 3,
        "category" to 4,
        "data" to 5
    ),
    val data: List<GeckoMarkerTuple>
)

/**
 * These structs aren't very DRY, but it is a simple and complete approach.
 * These structs are the initial transformation of the Gecko data to the
 * processed format. See `docs-developer/gecko-profile-format.md` for more
 * information.
 */
@Serializable
data class GeckoMarkerStruct(
    val name: IndexIntoStringTable,
    val startTime: Milliseconds,
    val endTime: Milliseconds,
    val phase: MarkerPhase,
    val data: MarkerPayload,
    val category: IndexIntoCategoryList,
    val length: Int
)

@Serializable
data class GeckoMarkerStack(
    @Required
    val name: String = "SyncProfile",
    val registerTime: Seconds?,
    val unregisterTime: Seconds?,
    val processType: String,
    val tid: Int,
    val pid: Int,
    val markers: GeckoMarkers,
    val samples: GeckoSamples
)

@Serializable(with = GeckoSamplesSerializer::class)
interface GeckoSamples

@Serializable
data class GeckoSamplesBasic(
    @Required
    val schema: Map<String, Int> = mapOf("stack" to 0, "time" to 1, "responsiveness" to 2),
    val data: List<GeckoSamplesBasicData>
) : GeckoSamples

@Serializable
data class GeckoSamplesBasicData(
    val stack: IndexIntoStackTable?,
    val time: Milliseconds,
    val responsiveness: Milliseconds
)

@Serializable
data class GeckoSamplesDetailed(
    @Required
    val schema: Map<String, Int> = mapOf("stack" to 0, "time" to 1, "eventDelay" to 2, "responsiveness" to 3),
    val data: List<GeckoSamplesDetailedData>
) : GeckoSamples

@Serializable
data class GeckoSamplesDetailedData(
    val stack: IndexIntoStackTable?,
    val time: Milliseconds,
    val eventDelay: Milliseconds,
    val responsiveness: Milliseconds? = null
)

class GeckoSamplesSerializer : JsonContentPolymorphicSerializer<GeckoSamples>(GeckoSamples::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "responsiveness" in element.jsonObject["schema"]!!.jsonObject -> GeckoSamplesBasic.serializer()
        else -> GeckoSamplesDetailed.serializer()
    }
}
