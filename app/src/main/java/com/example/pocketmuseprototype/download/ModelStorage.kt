package com.example.pocketmuseprototype.download

import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator

/** Files are owned by the download coordinator until their verified source has been published. */
internal class ModelStorage(private val externalFiles: () -> File?) {
    fun prepare(key: String): File {
        val source = sourceDirectory(key)
        Files.createDirectories(source.toPath())
        return source
    }

    fun checkpoint(key: String): File = safeChild(sourceDirectory(key), "checkpoint.safetensors")
    fun partial(key: String): File = safeChild(sourceDirectory(key), "checkpoint.safetensors.part")
    fun thumbnail(key: String): File = safeChild(directory(key), "thumbnail.webp")
    fun thumbnailPartial(key: String): File = safeChild(directory(key), "thumbnail.webp.part")

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
        val files = try {
            Files.walk(path)
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

    private fun sourceDirectory(key: String): File = safeChild(directory(key), "source")

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
