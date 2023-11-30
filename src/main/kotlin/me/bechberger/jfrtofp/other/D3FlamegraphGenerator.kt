package me.bechberger.jfrtofp.other

import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedThread
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.bechberger.jfrtofp.util.sampledThread
import java.nio.file.Path
import java.util.IdentityHashMap

class D3FlamegraphGenerator(jfrFile: Path) : BaseGenerator(jfrFile) {
    /**
     * Returns a JSON string for https://github.com/spiermar/d3-flame-graph, entries have the format
     * <code>
     *     {
     "name": "<name>",
     "value": <value>,
     "children": [
     <Object>
     ]
     }
     * </code>
     */
    override fun generate(): String {
        @Serializable
        data class Info(
            val type: String,
            val methodName: String,
            val descriptor: String,
            val samplePerType: MutableMap<String, Int> = mutableMapOf()
        )

        @Serializable
        data class Node(
            val name: String,
            var value: Int,
            var children: MutableList<Node> = mutableListOf(),
            val methodInfo: Info? = null
        )

        val nodeToChildNode = IdentityHashMap<Node, HashMap<RecordedMethod, Node>>()

        var ovSampleCount = 0
        val samples = executionSamples()
        val threads = mutableMapOf<RecordedThread, Node>()
        for (sample in samples) {
            assert(
                sample.eventType.name.equals("jdk.ExecutionSample") ||
                    sample.eventType.name.equals("jdk.NativeMethodSample")
            )
            if (sample.stackTrace.frames.isEmpty()) {
                continue
            }
            val thread = sample.sampledThread
            val threadNode = threads.computeIfAbsent(thread) { Node(thread.javaName, 0) }
            var currentNode = threadNode
            for (frame in sample.stackTrace.frames.asReversed()) {
                val methodToNode = nodeToChildNode.computeIfAbsent(currentNode) { HashMap() }
                currentNode = methodToNode.computeIfAbsent(frame.method) { method ->
                    val node = Node(
                        shortMethodString(method),
                        0,
                        methodInfo = Info(method.type.name, method.name, method.descriptor)
                    )
                    currentNode.children.add(node)
                    return@computeIfAbsent node
                }
                currentNode.value += 1
                currentNode.methodInfo?.let { info ->
                    info.samplePerType.put(frame.type, info.samplePerType[frame.type] ?: 0)
                }
            }
            threadNode.value += 1
            ovSampleCount += 1
        }
        val topNode = Node(
            "Program",
            ovSampleCount,
            threads.entries.sortedBy { it.key.javaThreadId }.map { it.value }.toMutableList()
        )
        return Json.encodeToString(topNode)
    }
}
