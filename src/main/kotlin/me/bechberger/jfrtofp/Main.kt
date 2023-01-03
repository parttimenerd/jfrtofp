package me.bechberger.jfrtofp

import me.bechberger.jfrtofp.other.D3FlamegraphGenerator
import me.bechberger.jfrtofp.other.SpeedscopeGenerator
import me.bechberger.jfrtofp.processor.ConfigMixin
import me.bechberger.jfrtofp.util.store
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(
    name = "jfrtofp",
    mixinStandardHelpOptions = true,
    description = ["Converting JFR files to Firefox Profiler profiles"]
)
class Main : Callable<Int> {

    @Parameters(index = "0", description = ["The JFR file to convert"])
    lateinit var file: Path

    @Option(names = ["-o", "--output"], description = ["The output file"])
    var output: Path? = null

    @Option(names = ["--mode", "-m"], description = ["fp, speedscope", "d3-flamegraph"])
    var mode = "fp"

    @Mixin var config: ConfigMixin = ConfigMixin()

    override fun call(): Int {
        when (mode) {
            "fp" -> {
                return runFP()
            }
            "speedscope", "d3-flamegraph" -> {
                if (output != null && !output!!.toString().endsWith(".json")) {
                    println("Output file must end with .json")
                    return 1
                }
                Files.write(
                    output
                        ?: Path.of(file.toString().replace(".jfr", ".json")),
                    (
                        when (mode) {
                            "speedscope" -> SpeedscopeGenerator(file)
                            "d3-flamegraph" -> D3FlamegraphGenerator(file)
                            else -> throw IllegalArgumentException("Unknown mode $mode")
                        }
                        ).generate().toByteArray()
                )
                return 0
            }
            else -> {
                println("Unknown mode $mode")
                return 1
            }
        }
    }

    fun runFP(): Int {
        if (output != null && !output!!.toString().endsWith(".json") && !output!!.toString().endsWith(".json.gz")) {
            println("Output file must end with .json or .json.gz")
            return 1
        }
        val outputFile = output ?: Path.of(file.toString().replace(".jfr", ".json.gz"))
        val time = System.currentTimeMillis()
        FirefoxProfileGenerator(file, config = config.toConfig()).generate().store(outputFile)
        System.err.println("Took ${System.currentTimeMillis() - time} ms")
        return 0
    }
}

fun main(args: Array<String>): Unit = exitProcess(CommandLine(Main()).execute(*args))
