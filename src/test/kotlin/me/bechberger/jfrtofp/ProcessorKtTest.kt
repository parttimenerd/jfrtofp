package me.bechberger.jfrtofp

import me.bechberger.jfrtofp.processor.Config
import me.bechberger.jfrtofp.processor.SimpleProcessor
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Path

internal class ProcessorKtTest {
    @Test
    fun testCanGenerateSimpleProfileWithoutExceptions() {
        println(Path.of("samples/small_profile.jfr").toAbsolutePath())
        val processor = SimpleProcessor(Config(), Path.of("samples/small_profile.jfr"))
        ByteArrayOutputStream().use { baas ->
            processor.process(baas)
        }
    }
}
