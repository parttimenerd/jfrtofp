package me.bechberger.jfrtofp

import me.bechberger.jfrtofp.processor.Config
import me.bechberger.jfrtofp.util.encodeToZippedStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

internal class FileCacheTest {

    @Test
    fun testCache() {
        val cache = FileCache(maxSize = 10_000_000)
        val sampleJFRFile = Path.of("samples/small_profile.jfr")
        val expectedBaas = ByteArrayOutputStream()
        FirefoxProfileGenerator(sampleJFRFile).generate().encodeToZippedStream(expectedBaas)
        val actualBytes = Files.readAllBytes(cache.get(sampleJFRFile, Config()))
        assertEquals(expectedBaas.toByteArray().size, actualBytes.size)
        assertTrue(cache.has(sampleJFRFile, Config()))
        cache.close()
    }
}
