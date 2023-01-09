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
import me.bechberger.jfrtofp.types.Counter
import me.bechberger.jfrtofp.types.Profile
import me.bechberger.jfrtofp.types.ProfileMeta
import me.bechberger.jfrtofp.types.Thread
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
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
val jsonFormat = Json {
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
fun Thread.encodeToJSONStream(output: OutputStream) {
    jsonFormat.encodeToStream(this, output)
}

@OptIn(ExperimentalSerializationApi::class)
fun ProfileMeta.encodeToJSONStream(output: OutputStream) {
    jsonFormat.encodeToStream(this, output)
}

@OptIn(ExperimentalSerializationApi::class)
fun List<Counter>.encodeToJSONStream(output: OutputStream) {
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

/** a tiny JSON generator which does not care about pretty printing */
class BasicJSONGenerator(val output: OutputStream) {

    fun writeStartObject() {
        output.write('{'.code)
    }

    fun writeEndObject() {
        output.write('}'.code)
    }

    fun writeStartArray() {
        output.write('['.code)
    }

    fun writeEndArray() {
        output.write(']'.code)
    }

    fun writeFieldName(name: String) {
        output.write("\"$name\":".toByteArray())
    }

    fun writeEmptyArray() {
        output.write("[]".toByteArray())
    }

    fun writeSimpleField(name: String, value: String, last: Boolean = false) {
        writeFieldName(name)
        output.write("\"$value\"".toByteArray())
        if (!last) {
            output.write(','.code)
        }
    }

    fun writeSimpleField(name: String, value: Number, last: Boolean = false) {
        writeFieldName(name)
        write(value.toString())
        if (!last) {
            output.write(','.code)
        }
    }

    fun writeSimpleField(name: String, value: Boolean, last: Boolean = false) {
        writeFieldName(name)
        output.write(value.toString().toByteArray())
        if (!last) {
            output.write(','.code)
        }
    }

    fun writeField(name: String, value: String, last: Boolean = false) {
        writeFieldName(name)
        write(value)
        if (!last) {
            output.write(','.code)
        }
    }

    fun writeRawArrayItem(item: ByteArray, isLast: Boolean = false) {
        output.write(item)
        if (!isLast) {
            output.write(','.code)
        }
    }

    fun writeRawByteArray(item: ByteArray, isCompressed: Boolean) {
        if (isCompressed) {
            GZIPInputStream(item.inputStream()).use { zipped ->
                zipped.copyTo(output)
            }
        } else {
            output.write(item)
        }
    }

    fun write(raw: String) {
        val writer = OutputStreamWriter(output)
        writer.write(raw)
        writer.flush()
    }

    fun writeString(string: String) {
        write("\"$string\"")
    }

    fun <T> writeArrayField(name: String, array: List<T>, writeItem: (T) -> Unit, last: Boolean = false) {
        writeFieldName(name)
        writeArray(array, writeItem)
        if (!last) {
            output.write(','.code)
        }
    }

    fun <T> writeArray(list: List<T>, writeItem: (T) -> Unit) {
        writeStartArray()
        list.forEachIndexed { index, item ->
            writeItem(item)
            if (index < list.size - 1) {
                output.write(','.code)
            }
        }
        writeEndArray()
    }

    fun <T : Number?> writeNumberArrayField(name: String, array: List<T>, last: Boolean = false) {
        writeArrayField(name, array, { if (it == null) write("null") else write(it.toString()) }, last)
    }

    fun <T : Boolean?> writeBooleanArrayField(name: String, array: List<T>, last: Boolean = false) {
        writeArrayField(name, array, { if (it == null) write("null") else write(it.toString()) }, last)
    }

    fun writeSingleValueArrayField(name: String, value: String, size: Int, last: Boolean = false) {
        writeFieldName(name)
        writeStartArray()
        for (i in 0 until size) {
            write(value)
            if (i < size - 1) {
                writeFieldSep()
            }
        }
        writeEndArray()
        if (!last) {
            writeFieldSep()
        }
    }

    fun writeNullArrayField(name: String, size: Int, last: Boolean = false) {
        writeSingleValueArrayField(name, "null", size, last)
    }

    fun writeFieldSep() {
        output.write(','.code)
    }
}
