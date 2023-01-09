package me.bechberger.jfrtofp

import java.io.ByteArrayOutputStream
import me.bechberger.jfrtofp.processor.Config
import org.junit.jupiter.api.Test
import java.nio.file.Path
import me.bechberger.jfrtofp.processor.SimpleProcessor

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
