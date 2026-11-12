package com.example.pocketmuseprototype.download

import com.example.pocketmuseprototype.conversion.sha256
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID

/** Final-file operations are serialized by SharedLcmDownloads on an I/O dispatcher. */
internal class SharedLcmStorage(
    base: File,
    private val size: Long = Sd15LcmLora.SIZE,
    private val sha256: String = Sd15LcmLora.SHA256
) {
    private val assets = File(base, "shared-assets")
    private val directory = File(assets, Sd15LcmLora.ID)
    private val final = File(directory, Sd15LcmLora.FILE_NAME)
    private val partial = File(directory, "${Sd15LcmLora.FILE_NAME}.part")
    private val result = File(directory, "last-result")
    private var checked: Pair<Fingerprint, DownloadError?>? = null

    private data class Fingerprint(val key: Any?, val modified: FileTime, val created: FileTime, val size: Long)

    fun problem(): DownloadError? {
        checkDirectory()
        val before = try {
            fingerprint(final)
        } catch (_: NoSuchFileException) {
            checked = null
            return DownloadError.MissingFile
        } catch (failure: DownloadFailure) {
            checked = null
            return failure.code
        }
        checked?.let { (identity, error) -> if (identity == before) return error }
        val error = when {
            before.size != size -> DownloadError.InvalidLength
            final.sha256() != sha256 -> DownloadError.InvalidHash
            else -> null
        }
        if (fingerprint(final) != before) return DownloadError.FileWrite
        checked = before to error
        return error
    }

    fun verifiedFile(): File? = final.takeIf { problem() == null }

    fun prepare(): File {
        checkDirectory()
        Files.createDirectories(directory.toPath())
        clearPartial()
        return partial
    }

    fun publish() {
        val verified = fingerprint(partial)
        if (verified.size != size) throw DownloadFailure(DownloadError.InvalidLength)
        Files.move(partial.toPath(), final.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        checked = verified to null
    }

    fun clearPartial() {
        checkDirectory()
        Files.deleteIfExists(partial.toPath())
    }

    fun lastResult(id: UUID? = null): String? {
        checkDirectory()
        if (!Files.isRegularFile(result.toPath(), NOFOLLOW_LINKS)) return null
        val fields = result.readText().split(' ', limit = 2)
        return fields.getOrNull(1)?.takeIf { id == null || fields.first() == id.toString() }
    }

    fun recordResult(id: UUID?, value: String) {
        checkDirectory()
        Files.createDirectories(directory.toPath())
        val marker = File(directory, "last-result.part").toPath()
        Files.deleteIfExists(marker)
        Files.write(marker, "$id $value".toByteArray())
        Files.move(marker, result.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun delete(id: UUID?) {
        recordResult(id, REMOVED)
        removeFinal()
    }

    fun removeFinal() {
        checkDirectory()
        Files.deleteIfExists(final.toPath())
        checked = null
    }

    private fun checkDirectory() {
        if (Files.isSymbolicLink(assets.toPath()) || Files.isSymbolicLink(directory.toPath())) throw IOException()
    }

    private fun fingerprint(file: File): Fingerprint {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) throw DownloadFailure(DownloadError.InvalidLength)
        return Fingerprint(attributes.fileKey(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.size())
    }
}

internal const val REMOVED = "Removed"
