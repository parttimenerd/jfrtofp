package me.bechberger.jfrtofp

import me.bechberger.jfrtofp.converter.JFRConverter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class ConverterTest {
    @Test
    fun testCjfrConversion() {
        val jfrFile = Path.of("condensed-data/profile_lossless.cjfr")
        assumeTrue(jfrFile.toFile().exists(), "profile_lossless.cjfr not present")
        val out = ByteArrayOutputStream()
        JFRConverter.convertPath(jfrFile, out)
        val json = out.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"threads\""), "Output must contain threads")
        assertTrue(json.length > 1000, "Output must be non-trivial (got ${json.length} bytes)")
    }
}
