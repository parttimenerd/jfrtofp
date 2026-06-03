package me.bechberger.jfrtofp.processor

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Per-thread sample spiller: buffers (stackIndex, time) rows in memory; when the buffer reaches
 * [chunkSize], sorts by time and flushes a gzipped chunk file. [replay] does a k-way merge over
 * all chunks to emit rows in globally sorted order.
 */
class SampleSpiller(val tempDir: Path, val chunkSize: Int = 65_536) {
    data class Row(val stack: Int, val time: Double)

    private val buffer = ArrayList<Row>(minOf(chunkSize, 16))
    private val chunkPaths = mutableListOf<Path>()
    var count: Long = 0L
        private set

    fun add(stack: Int, time: Double) {
        buffer.add(Row(stack, time))
        count++
        if (buffer.size >= chunkSize) flushChunk()
    }

    fun close() {
        if (buffer.isNotEmpty()) flushChunk()
    }

    private fun flushChunk() {
        buffer.sortBy { it.time }
        val path = tempDir.resolve("samples-chunk-${chunkPaths.size}.bin.gz")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(path)))).use { out ->
            out.writeInt(buffer.size)
            for (row in buffer) {
                out.writeInt(row.stack)
                out.writeDouble(row.time)
            }
        }
        chunkPaths.add(path)
        buffer.clear()
    }

    /** Emit all rows in globally sorted order via k-way merge. */
    fun replay(callback: (stack: Int, time: Double) -> Unit) {
        if (chunkPaths.isEmpty()) return

        data class Cursor(val stream: DataInputStream, var stack: Int, var time: Double, var remaining: Int) :
            Comparable<Cursor> {
            override fun compareTo(other: Cursor) = compareValuesBy(this, other, { it.time }, { it.stack })
        }

        val cursors = ArrayList<Cursor>(chunkPaths.size)
        try {
            for (path in chunkPaths) {
                val stream = DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(path))))
                val size = stream.readInt()
                if (size > 0) {
                    cursors.add(Cursor(stream, stream.readInt(), stream.readDouble(), size - 1))
                } else {
                    stream.close()
                }
            }

            val pq = PriorityQueue(cursors)
            while (pq.isNotEmpty()) {
                val min = pq.poll()
                callback(min.stack, min.time)
                if (min.remaining > 0) {
                    min.stack = min.stream.readInt()
                    min.time = min.stream.readDouble()
                    min.remaining--
                    pq.add(min)
                } else {
                    min.stream.close()
                }
            }
        } catch (e: Exception) {
            cursors.forEach { runCatching { it.stream.close() } }
            throw e
        }
    }

    fun deleteChunks() = chunkPaths.forEach { runCatching { Files.deleteIfExists(it) } }
}

/**
 * Per-thread marker spiller: buffers (name, startTime, endTime, phase, category, dataBytes) rows.
 * Sort key is startTime. The pre-serialized data blob travels through chunks verbatim.
 */
class MarkerSpiller(val tempDir: Path, val chunkSize: Int = 16_384) {
    data class Row(
        val name: Int,
        val startTime: Double?,
        val endTime: Double?,
        val phase: Int,
        val category: Int,
        val dataBytes: ByteArray,
    ) : Comparable<Row> {
        override fun compareTo(other: Row) =
            compareValuesBy(this, other, { it.startTime ?: Double.MAX_VALUE }, { it.name })
    }

    private val buffer = ArrayList<Row>(minOf(chunkSize, 16))
    private val chunkPaths = mutableListOf<Path>()
    var count: Long = 0L
        private set

    fun add(name: Int, startTime: Double?, endTime: Double?, phase: Int, category: Int, dataBytes: ByteArray) {
        buffer.add(Row(name, startTime, endTime, phase, category, dataBytes))
        count++
        if (buffer.size >= chunkSize) flushChunk()
    }

    fun close() {
        if (buffer.isNotEmpty()) flushChunk()
    }

    private fun flushChunk() {
        buffer.sort()
        val path = tempDir.resolve("markers-chunk-${chunkPaths.size}.bin.gz")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(path)))).use { out ->
            out.writeInt(buffer.size)
            for (row in buffer) {
                out.writeInt(row.name)
                writeNullableDouble(out, row.startTime)
                writeNullableDouble(out, row.endTime)
                out.writeInt(row.phase)
                out.writeInt(row.category)
                out.writeInt(row.dataBytes.size)
                out.write(row.dataBytes)
            }
        }
        chunkPaths.add(path)
        buffer.clear()
    }

    /** Emit all rows in startTime-sorted order via k-way merge. */
    fun replay(callback: (name: Int, startTime: Double?, endTime: Double?, phase: Int, category: Int, dataBytes: ByteArray) -> Unit) {
        if (chunkPaths.isEmpty()) return

        data class Cursor(val stream: DataInputStream, var row: Row, var remaining: Int) :
            Comparable<Cursor> {
            override fun compareTo(other: Cursor) = row.compareTo(other.row)
        }

        fun readRow(stream: DataInputStream): Row {
            val name = stream.readInt()
            val startTime = readNullableDouble(stream)
            val endTime = readNullableDouble(stream)
            val phase = stream.readInt()
            val category = stream.readInt()
            val dataSize = stream.readInt()
            val dataBytes = stream.readNBytes(dataSize)
            return Row(name, startTime, endTime, phase, category, dataBytes)
        }

        val cursors = ArrayList<Cursor>(chunkPaths.size)
        try {
            for (path in chunkPaths) {
                val stream = DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(path))))
                val size = stream.readInt()
                if (size > 0) {
                    cursors.add(Cursor(stream, readRow(stream), size - 1))
                } else {
                    stream.close()
                }
            }

            val pq = PriorityQueue(cursors)
            while (pq.isNotEmpty()) {
                val min = pq.poll()
                val r = min.row
                callback(r.name, r.startTime, r.endTime, r.phase, r.category, r.dataBytes)
                if (min.remaining > 0) {
                    min.row = readRow(min.stream)
                    min.remaining--
                    pq.add(min)
                } else {
                    min.stream.close()
                }
            }
        } catch (e: Exception) {
            cursors.forEach { runCatching { it.stream.close() } }
            throw e
        }
    }

    fun deleteChunks() = chunkPaths.forEach { runCatching { Files.deleteIfExists(it) } }
}

private fun writeNullableDouble(out: DataOutputStream, value: Double?) {
    if (value == null) {
        out.writeBoolean(false)
    } else {
        out.writeBoolean(true)
        out.writeDouble(value)
    }
}

private fun readNullableDouble(stream: DataInputStream): Double? =
    if (stream.readBoolean()) stream.readDouble() else null
