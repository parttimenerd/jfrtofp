package me.bechberger.jfrtofp

import me.bechberger.jfrtofp.processor.MarkerSpiller
import me.bechberger.jfrtofp.processor.SampleSpiller
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpillerTest {

    @Test
    fun `SampleSpiller round-trips 1k rows in sorted order`(@TempDir tempDir: Path) {
        val spiller = SampleSpiller(tempDir)
        val rows = (999 downTo 0).map { i -> i to i.toDouble() }
        rows.forEach { (stack, time) -> spiller.add(stack, time) }
        spiller.close()

        val result = mutableListOf<Pair<Int, Double>>()
        spiller.replay { stack, time -> result.add(stack to time) }

        assertEquals(1000, result.size)
        result.zipWithNext().forEach { (a, b) -> assert(a.second <= b.second) { "Not sorted: $a before $b" } }
    }

    @Test
    fun `SampleSpiller handles multi-chunk merge correctly`(@TempDir tempDir: Path) {
        // Force chunk size to 10 to exercise multi-chunk path
        val spiller = SampleSpiller(tempDir, chunkSize = 10)
        // Add 35 rows in descending order (worst case for sort)
        for (i in 34 downTo 0) spiller.add(i, i.toDouble())
        spiller.close()

        val result = mutableListOf<Double>()
        spiller.replay { _, time -> result.add(time) }

        assertEquals(35, result.size)
        assertEquals(result.sorted(), result) { "Output not globally sorted" }
    }

    @Test
    fun `SampleSpiller empty spiller produces no output`(@TempDir tempDir: Path) {
        val spiller = SampleSpiller(tempDir)
        spiller.close()
        var count = 0
        spiller.replay { _, _ -> count++ }
        assertEquals(0, count)
    }

    @Test
    fun `MarkerSpiller round-trips rows with null timestamps`(@TempDir tempDir: Path) {
        val spiller = MarkerSpiller(tempDir, chunkSize = 5)
        val data = "hello".toByteArray()
        // Mix of null and non-null startTimes, descending order
        spiller.add(3, 30.0, 31.0, 1, 0, data)
        spiller.add(1, null, null, 0, 0, data)
        spiller.add(2, 10.0, 11.0, 1, 0, data)
        spiller.add(4, 20.0, 21.0, 1, 0, data)
        spiller.close()

        val names = mutableListOf<Int>()
        val times = mutableListOf<Double?>()
        spiller.replay { name, startTime, _, _, _, _ -> names.add(name); times.add(startTime) }

        assertEquals(4, names.size)
        // Non-null startTimes should be sorted; null sorts last (Double.MAX_VALUE)
        val nonNull = times.filterNotNull()
        assertEquals(nonNull.sorted(), nonNull)
    }

    @Test
    fun `MarkerSpiller data bytes survive round-trip`(@TempDir tempDir: Path) {
        val spiller = MarkerSpiller(tempDir)
        val payload = ByteArray(256) { it.toByte() }
        spiller.add(1, 1.0, 2.0, 1, 0, payload)
        spiller.close()

        var received: ByteArray? = null
        spiller.replay { _, _, _, _, _, dataBytes -> received = dataBytes }
        assertArrayEquals(payload, received)
    }

    @Test
    fun `SampleSpiller deleteChunks removes all temp files`(@TempDir tempDir: Path) {
        val spiller = SampleSpiller(tempDir, chunkSize = 5)
        for (i in 0 until 20) spiller.add(i, i.toDouble())
        spiller.close()

        assert(tempDir.toFile().listFiles()!!.isNotEmpty()) { "Expected chunk files before delete" }
        spiller.deleteChunks()
        assertEquals(0, tempDir.toFile().listFiles()!!.size, "Chunk files should be deleted")
    }

    @Test
    fun `SampleSpiller count tracks total rows`(@TempDir tempDir: Path) {
        val spiller = SampleSpiller(tempDir, chunkSize = 10)
        for (i in 0 until 35) spiller.add(i, i.toDouble())
        assertEquals(35L, spiller.count)
        spiller.close()
        assertEquals(35L, spiller.count)
    }
}
