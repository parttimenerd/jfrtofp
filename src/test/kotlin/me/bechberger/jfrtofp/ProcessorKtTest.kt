package me.bechberger.jfrtofp

import me.bechberger.jfrtofp.processor.Config
import org.junit.jupiter.api.Test
import java.nio.file.Path

internal class ProcessorKtTest {
    @Test
    fun testCanGenerateSimpleProfileWithoutExceptions() {
        println(Path.of("samples/small_profile.jfr").toAbsolutePath())
        val processor = FirefoxProfileGenerator(Path.of("samples/small_profile.jfr"), config = Config())
        processor.generate()
    }
}
