package me.bechberger.jfrtofp

import org.junit.jupiter.api.Test
import java.nio.file.Path
import me.bechberger.jfrtofp.processor.Config

internal class ProcessorKtTest {
    @Test
    fun testCanGenerateSimpleProfileWithoutExceptions() {
        println(Path.of("samples/small_profile.jfr").toAbsolutePath())
        val processor = FirefoxProfileGenerator(Path.of("samples/small_profile.jfr"), config = Config())
        processor.generate()
    }
}
