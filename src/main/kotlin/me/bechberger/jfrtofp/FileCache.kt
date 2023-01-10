package me.bechberger.jfrtofp

import me.bechberger.jfrtofp.processor.Config
import me.bechberger.jfrtofp.processor.SimpleProcessor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import me.bechberger.jfrtofp.util.fileExtension
import kotlin.math.max
import kotlin.streams.asSequence

/** cache the conversion result for JFR files */
class FileCache(
    location: Path? = null,
    maxSize: Long = 2_000_000_000,
    private val extension: String = ".json.gz"
) {
    private val tmpLocation = location ?: DEFAULT_LOCATION
    init {
        try {
            Files.createDirectories(tmpLocation)
        } catch (_: IOException) {}
    }
    private val maxSize = AtomicLong(maxSize)

    fun close() {
        try {
            tmpLocation.toFile().deleteRecursively()
        } catch (_: IOException) {}
    }

    fun get(jfrFile: Path, config: Config): Path {
        synchronized(this) {
            val filePath = filePath(jfrFile, config)
            if (!Files.exists(filePath)) {
                create(jfrFile, config, filePath)
            }
            return filePath
        }
    }

    fun has(jfrFile: Path, config: Config): Boolean {
        return Files.exists(filePath(jfrFile, config))
    }

    private fun create(jfrFile: Path, config: Config, filePath: Path) {
        Files.newOutputStream(filePath).use { baas ->
            val processor = SimpleProcessor(config, jfrFile)
            when (filePath.fileExtension) {
                "json" -> processor.process(baas)
                "gz" -> processor.processZipped(baas)
                else -> throw IllegalArgumentException("Unknown file extension: ${filePath.fileExtension}")
            }
            ensureFreeSpace(0)
        }
    }

    private fun ensureFreeSpace(amount: Long) {
        while (cacheSize() > max(0, maxSize.get() - amount)) {
            val oldest = Files.list(tmpLocation).asSequence().minByOrNull { Files.getLastModifiedTime(it).toMillis() }
            if (oldest != null) {
                Files.delete(oldest)
            }
        }
    }

    private fun cacheSize() = Files.list(tmpLocation).mapToLong { it.toFile().length() }.sum()

    private fun filePath(jfrFile: Path, config: Config): Path {
        return tmpLocation.resolve(hashSum(jfrFile, config) + extension)
    }

    private fun hashSum(jfrFile: Path, config: Config): String {
        return hashSum(jfrFile) + hashSum(config)
    }

    private fun hashSum(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use {
            val buffer = ByteArray(BUFFER_SIZE)
            var read = it.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = it.read(buffer)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest()).replace("/", "_")
    }

    fun setMaxSize(size: Long) {
        maxSize.set(size)
        ensureFreeSpace(0)
    }

    fun getMaxSize() = maxSize.get()

    private fun hashSum(config: Config): String {
        return config.toString().hashCode().toString()
    }

    companion object {
        const val BUFFER_SIZE = 1024
        val DEFAULT_LOCATION: Path = Files.createTempDirectory("jfrtofp")
    }
}
