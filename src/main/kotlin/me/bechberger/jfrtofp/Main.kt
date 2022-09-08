package me.bechberger.jfrtofp

import java.nio.file.Path

fun main(args: Array<String>) {
    val profile = FirefoxProfileGenerator(Path.of(args[0]), config = Config()).generate()
    profile.store(Path.of(args[0].replace(".jfr", ".json")))
    profile.store(Path.of(args[0].replace(".jfr", ".json.gz")))
}
