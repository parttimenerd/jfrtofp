package me.bechberger.jfrtofp

import me.bechberger.jfrtofp.other.SpeedscopeProcessor
import picocli.CommandLine
import picocli.CommandLine.Command
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

    @Option(names = ["--mode", "-m"], description = ["fp, speedscope"])
    var mode = "fp"

    override fun call(): Int {
        when (mode) {
            "fp" -> {
                return runFP()
            }
            "speedscope" -> {
                println("Converting to speedscope")
                println(output)
                if (output != null && !output!!.toString().endsWith(".json")) {
                    println("Output file must end with .json")
                    return 1
                }
                Files.write(
                    output
                        ?: Path.of(file.toString().replace(".jfr", ".json")),
                    SpeedscopeProcessor(file).generate().toByteArray()
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
        FirefoxProfileGenerator(file, config = Config()).generate().store(outputFile)
        return 0
    }
}

fun main(args: Array<String>): Unit = exitProcess(CommandLine(Main()).execute(*args))
