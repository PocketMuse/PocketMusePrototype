package com.example.pocketmuseprototype.download

import com.example.pocketmuseprototype.conversion.ConversionErrorCode
import com.example.pocketmuseprototype.conversion.ConversionRequest
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator
import java.util.UUID

/** App-owned paths shared by the download and conversion workers. */
internal class ModelStorage(
    private val noBackupFiles: () -> File,
    private val cleanupPartial: (File) -> ConversionErrorCode = ConversionRequest.Companion::cleanupPartial,
    private val externalFiles: () -> File?
) {
    fun prepare(key: String): File {
        val source = sourceDirectory(key)
        Files.createDirectories(source.toPath())
        return source
    }

    fun checkpoint(key: String): File = safeChild(sourceDirectory(key), "checkpoint.safetensors")
    fun partial(key: String): File = safeChild(sourceDirectory(key), "checkpoint.safetensors.part")
    fun thumbnail(key: String): File = safeChild(directory(key), "thumbnail.webp")
    fun thumbnailPartial(key: String): File = safeChild(directory(key), "thumbnail.webp.part")

    fun conversionOutput(key: String, id: String): File {
        require(UUID.fromString(id).toString() == id)
        return safeChild(convertedDirectory(key), id)
    }

    /** Resolves the cache path without creating it. Runtime owns creation and file access. */
    fun openClCacheDirectory(key: String, id: String): File? {
        require(UUID.fromString(id).toString() == id)
        return accessCache { safeChild(modelCacheDirectory(key), id) }
    }

    fun hasCheckpoint(key: String, bytes: Long): Boolean {
        val path = checkpoint(key).toPath()
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return false
        }
        return attributes.isRegularFile && attributes.size() == bytes
    }

    fun publishCheckpoint(key: String) {
        Files.move(partial(key).toPath(), checkpoint(key).toPath(), ATOMIC_MOVE)
    }

    fun publishThumbnail(key: String) {
        Files.move(thumbnailPartial(key).toPath(), thumbnail(key).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun clearPartial(key: String) {
        Files.deleteIfExists(partial(key).toPath())
        Files.deleteIfExists(thumbnailPartial(key).toPath())
    }

    fun clearSource(key: String) {
        clearPartial(key)
        Files.deleteIfExists(checkpoint(key).toPath())
    }

    fun delete(key: String) {
        val path = directory(key).toPath()
        val cleanup = try {
            cleanupRetiredStaging(key, current = null)
        } catch (unavailable: LinkageError) {
            throw IOException("Converter cleanup unavailable", unavailable)
        }
        if (cleanup != ConversionErrorCode.NONE) throw IOException("Converter staging cleanup failed: $cleanup")
        deleteTree(path.toFile())
        accessCache { deleteTree(modelCacheDirectory(key)) }
    }

    fun cleanupStaging(output: File): ConversionErrorCode {
        val partial = output.toPath().resolveSibling("${output.name}.partial")
        if (Files.notExists(partial, NOFOLLOW_LINKS)) return ConversionErrorCode.NONE
        return cleanupPartial(output)
    }

    fun cleanupRetiredStaging(key: String, current: File?): ConversionErrorCode {
        for (output in stagingOutputs(key)) {
            if (output == current) continue
            val cleanup = cleanupStaging(output)
            if (cleanup != ConversionErrorCode.NONE) return cleanup
        }
        return ConversionErrorCode.NONE
    }

    private fun stagingOutputs(key: String): List<File> {
        val path = convertedDirectory(key).toPath()
        val staging = try {
            Files.newDirectoryStream(path, "*.partial")
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        return try {
            staging.use { entries ->
                entries.map { entry ->
                    val id = entry.fileName.toString().removeSuffix(".partial")
                    try {
                        conversionOutput(key, id)
                    } catch (invalid: IllegalArgumentException) {
                        throw IOException("Unrecognized conversion staging path", invalid)
                    }
                }
            }
        } catch (error: DirectoryIteratorException) {
            throw IOException("Cannot list conversion staging", error.cause)
        }
    }

    /** A final directory under this attempt ID is app-owned. Staging belongs to the Converter. */
    fun discardConversion(key: String, id: String) {
        deleteTree(conversionOutput(key, id))
        accessCache { openClCacheDirectory(key, id)?.let(::deleteTree) }
    }

    private inline fun <T> accessCache(action: () -> T): T? = try {
        action()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun deleteTree(file: File) {
        val files = try {
            Files.walk(file.toPath())
        } catch (_: NoSuchFileException) {
            return
        }
        // Do not follow links inside a model directory when removing its contents.
        try {
            files.use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        } catch (error: UncheckedIOException) {
            throw error.cause ?: IOException()
        }
    }

    private fun convertedDirectory(key: String): File = safeChild(directory(key), "converted")
    private fun sourceDirectory(key: String): File = safeChild(directory(key), "source")

    private fun modelCacheDirectory(key: String): File {
        require(key.matches(storageKeyPattern))
        return safeChild(safeChild(noBackupFiles(), "opencl-cache"), key)
    }

    private fun directory(key: String): File {
        require(key.matches(storageKeyPattern))
        val external = externalFiles() ?: throw DownloadFailure(DownloadError.StorageUnavailable)
        return safeChild(safeChild(external, "models"), key)
    }

    private fun safeChild(parent: File, name: String): File = File(parent, name).also {
        if (Files.isSymbolicLink(it.toPath())) throw IOException()
    }

    companion object {
        private val storageKeyPattern = Regex("[1-9][0-9]*_[1-9][0-9]*")

        fun key(versionId: Long, fileId: Long): String {
            require(versionId > 0 && fileId > 0)
            return "${versionId}_$fileId"
        }
    }
}
