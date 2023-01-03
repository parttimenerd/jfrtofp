package me.bechberger.jfrtofp.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import me.bechberger.jfrtofp.types.Profile
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.extension

// source: https://github.com/Kotlin/kotlinx.serialization/issues/296#issuecomment-1132714147
fun Collection<*>.toJsonElement(): JsonElement = JsonArray(mapNotNull { it.toJsonElement() })

fun Map<*, *>.toJsonElement(): JsonElement = JsonObject(
    mapNotNull {
        (it.key as? String ?: return@mapNotNull null) to it.value.toJsonElement()
    }.toMap()
)

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Map<*, *> -> toJsonElement()
    is Collection<*> -> toJsonElement()
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

@OptIn(ExperimentalSerializationApi::class)
private val jsonFormat = Json {
    prettyPrint = false
    encodeDefaults = true
    explicitNulls = false
}

fun Profile.generateJSON(): String {
    return jsonFormat.encodeToString(this)
}

@OptIn(ExperimentalSerializationApi::class)
fun Profile.encodeToJSONStream(output: OutputStream) {
    jsonFormat.encodeToStream(this, output)
}

@OptIn(ExperimentalSerializationApi::class)
fun InputStream.decodeToProfile(): Profile {
    return jsonFormat.decodeFromStream(this)
}

fun Profile.encodeToZippedStream(output: OutputStream) {
    GZIPOutputStream(output).use { zipped ->
        encodeToJSONStream(zipped)
    }
}

fun Profile.encodeToJSONStream(): InputStream {
    val input = PipedInputStream()
    val out = PipedOutputStream(input)
    Runnable { encodeToJSONStream(out) }.run()
    return input
}

fun Profile.encodeToZippedStream(): InputStream {
    val input = PipedInputStream()
    val out = PipedOutputStream(input)
    Runnable { encodeToZippedStream(out) }.run()
    return input
}

fun Profile.store(path: Path) {
    Files.newOutputStream(path).use { stream ->
        when (path.extension) {
            "json" -> encodeToJSONStream(stream)
            "gz" -> encodeToZippedStream(stream)
            else -> throw IllegalArgumentException("Unknown file extension: ${path.extension}")
        }
    }
}
