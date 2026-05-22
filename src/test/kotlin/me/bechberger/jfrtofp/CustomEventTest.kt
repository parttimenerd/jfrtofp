package me.bechberger.jfrtofp

import jdk.jfr.Configuration
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.Recording
import me.bechberger.jfrtofp.processor.Config
import me.bechberger.jfrtofp.processor.SimpleProcessor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.test.assertTrue

// Custom event with no @Category annotation — the case that previously crashed with
// NoSuchElementException on categoryNames.first().
@Name("test.WeirdCustomEvent")
@Label("Weird Custom Event")
class WeirdCustomEvent : Event() {
    @Label("Message")
    var message: String = ""

    @Label("Value")
    var value: Int = 0
}

@Name("test.AnotherCustom")
@Label("Another Custom")
class AnotherCustomEvent : Event() {
    @Label("Count")
    var count: Long = 0L
}

internal class CustomEventTest {

    @Test
    fun `custom events without category are not dropped`(@TempDir tmpDir: Path) {
        val jfrFile = tmpDir.resolve("custom.jfr")
        Recording(Configuration.getConfiguration("profile")).use { recording ->
            recording.start()

            repeat(3) { i ->
                WeirdCustomEvent().apply {
                    message = "msg-$i"
                    value = i * 10
                }.commit()
                AnotherCustomEvent().apply {
                    count = i.toLong()
                }.commit()
            }

            Thread.sleep(50)  // let a few profiling samples accumulate
            recording.stop()
            recording.dump(jfrFile)
        }

        // Convert via jfrtofp — must not throw
        val output = ByteArrayOutputStream()
        SimpleProcessor(Config(), jfrFile).process(output)
        val json = output.toString(Charsets.UTF_8)

        // Both custom event types must appear as markers in the output
        assertTrue(json.contains("test.WeirdCustomEvent"), "WeirdCustomEvent should appear in markers output")
        assertTrue(json.contains("test.AnotherCustom"), "AnotherCustom should appear in markers output")
    }
}
