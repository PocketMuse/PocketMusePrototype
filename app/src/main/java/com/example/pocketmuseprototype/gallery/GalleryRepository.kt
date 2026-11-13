package com.example.pocketmuseprototype.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class GalleryImage(val record: GeneratedImageRecord, val file: File)

/** The application shares one repository so file publication, deletion and recovery are serialized. */
internal class GalleryRepository(
    private val directory: File,
    private val images: GeneratedImageDao,
) {
    private val filesLock = Mutex()
    private var recovered = false

    val gallery: Flow<List<GalleryImage>> = images.observeAll()
        .onStart { filesLock.withLock { recover() } }
        .map { rows ->
            filesLock.withLock {
                rows.mapNotNull { row ->
                    fileFor(row)?.takeIf(File::isFile)?.let { GalleryImage(row, it) }
                }
            }
        }
        .flowOn(Dispatchers.IO)

    /** Borrows [bitmap] until the PNG and its metadata have been committed or cleaned up. */
    suspend fun save(bitmap: Bitmap, modelName: String, prompt: String, steps: Int): GalleryImage =
        withContext(Dispatchers.IO) {
            filesLock.withLock {
                recover()
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Gallery directory unavailable")
                val id = UUID.randomUUID().toString()
                val record = GeneratedImageRecord(id, "$id.png", System.currentTimeMillis(), modelName, prompt, steps)
                val file = File(directory, record.fileName)
                val partial = File(directory, "${file.name}.partial")
                try {
                    partial.outputStream().use {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG encoding failed")
                    }
                    val decoded = BitmapFactory.decodeFile(partial.path) ?: throw IOException("Invalid PNG")
                    val dimensionsMatch = decoded.width == bitmap.width && decoded.height == bitmap.height
                    decoded.recycle()
                    if (!dimensionsMatch) throw IOException("PNG dimensions changed")
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        Files.move(partial.toPath(), file.toPath(), ATOMIC_MOVE)
                        try {
                            images.insert(record)
                        } catch (failure: Throwable) {
                            discard(file)
                            throw failure
                        }
                    }
                    GalleryImage(record, file)
                } finally {
                    discard(partial)
                }
            }
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        filesLock.withLock {
            recover()
            val row = images.find(id) ?: return@withLock
            val file = fileFor(row)
            withContext(NonCancellable) {
                val pending = file?.takeIf(File::isFile)?.let {
                    File(directory, "${it.name}.deleting").also { target ->
                        Files.move(it.toPath(), target.toPath(), ATOMIC_MOVE)
                    }
                }
                try {
                    images.delete(id)
                } catch (failure: Throwable) {
                    if (pending != null) {
                        try {
                            Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE)
                        } catch (restoreFailure: IOException) {
                            recovered = false
                            failure.addSuppressed(restoreFailure)
                        }
                    }
                    throw failure
                }
                pending?.let(::discard)
            }
        }
    }

    private suspend fun recover() {
        if (recovered) return
        val rows = images.all()
        val registered = rows.mapNotNull { fileFor(it)?.name }.toSet()
        var cleaned = true
        val files = if (directory.exists()) {
            directory.listFiles() ?: throw IOException("Cannot read Gallery directory")
        } else emptyArray()
        for (file in files) {
            if (!file.isFile || !galleryFileName.matches(file.name)) continue
            val name = file.name.removeSuffix(".deleting")
            if (name != file.name && name in registered) {
                Files.move(file.toPath(), File(directory, name).toPath(), ATOMIC_MOVE)
            } else if (file.name !in registered) {
                cleaned = discard(file) && cleaned
            }
        }
        recovered = cleaned
    }

    private fun discard(file: File): Boolean {
        val removed = file.delete() || !file.exists()
        if (!removed) recovered = false
        return removed
    }

    private fun fileFor(row: GeneratedImageRecord): File? =
        if (idPattern.matches(row.id) && row.fileName == "${row.id}.png") File(directory, row.fileName) else null

    private companion object {
        val idPattern = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        val galleryFileName = Regex("${idPattern.pattern}\\.png(\\.partial|\\.deleting)?")
    }
}
