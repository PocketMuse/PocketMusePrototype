package com.example.pocketmuseprototype.download

import android.database.sqlite.SQLiteFullException
import com.example.pocketmuseprototype.catalog.CatalogCheckpoint
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ScheduledState { Waiting, Running, Finished }

internal interface DownloadScheduler {
    suspend fun enqueue(key: String, id: String)
    suspend fun state(id: String): ScheduledState?
}

internal enum class EnqueueResult { Queued, Exists, StorageUnavailable, SchedulingFailed }

internal data class LibraryItem(val model: LibraryModel, val thumbnail: File?)

/** Process-scoped owner of Room state and filesystem mutations. Workers and UI share this instance. */
internal class ModelDownloads(
    private val store: LibraryStore,
    private val storage: ModelStorage,
    private val scheduler: DownloadScheduler,
    private val token: suspend () -> String,
    private val transfer: CivitaiTransfer,
    private val writeThumbnail: (ByteArray, File) -> Unit
) {
    val models = store.observe().map { models ->
        models.map { model ->
            val thumbnail = try {
                storage.thumbnail(model.storageKey).takeIf(File::isFile)
            } catch (_: IOException) {
                null
            }
            LibraryItem(model, thumbnail)
        }
    }.flowOn(Dispatchers.IO)
    private val commands = Mutex()
    private val transfers = Mutex()
    private var active: Pair<String, Job>? = null

    suspend fun enqueue(candidate: CatalogCheckpoint): EnqueueResult = withContext(Dispatchers.IO + NonCancellable) {
        commands.withLock {
            val key = ModelStorage.key(candidate.modelVersionId, candidate.fileId)
            if (store.find(key) != null) return@withLock EnqueueResult.Exists
            try {
                storage.prepare(key)
            } catch (_: IOException) {
                return@withLock EnqueueResult.StorageUnavailable
            }
            val model = LibraryModel(
                key, candidate.modelId, candidate.modelVersionId, candidate.fileId,
                candidate.modelName, candidate.versionName, candidate.fileName,
                (candidate.sizeKB * 1024).toLong(), candidate.sha256, candidate.thumbnailUrl.stableThumbnailUrl(),
                UUID.randomUUID().toString()
            )
            store.insert(model)
            if (schedule(model)) EnqueueResult.Queued else EnqueueResult.SchedulingFailed
        }
    }

    suspend fun remove(model: LibraryModel) = withContext(Dispatchers.IO) {
        commands.withLock {
            val current = store.find(model.storageKey)?.takeIf { it.workId == model.workId } ?: return@withLock
            when {
                model.downloadStatus.pending && current.downloadStatus.pending -> {
                    store.update(current.copy(downloadStatus = DownloadStatus.Cancelling))
                    val job = active?.takeIf { it.first == current.storageKey }?.second
                    if (job == null) deleteFiles(current) else job.cancel()
                }
                !model.downloadStatus.pending &&
                    current.downloadStatus in setOf(DownloadStatus.Downloaded, DownloadStatus.Failed) -> {
                    store.update(current.copy(downloadStatus = DownloadStatus.Deleting))
                    deleteFiles(current)
                }
            }
        }
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        commands.withLock {
            for (model in store.all()) {
                val key = model.storageKey
                if (active?.first == key) continue
                when (model.downloadStatus) {
                    DownloadStatus.Cancelling, DownloadStatus.Deleting -> deleteFiles(model)
                    DownloadStatus.Downloaded -> try {
                        storage.clearPartial(key)
                        if (!published(model)) fail(model, DownloadError.MissingFile)
                    } catch (_: IOException) {
                        // The volume can be temporarily unavailable. Recheck on the next foreground visit.
                    }
                    DownloadStatus.Failed -> if (model.errorCode != DownloadError.Cleanup) {
                        try {
                            storage.clearSource(key)
                        } catch (_: IOException) {
                            fail(model, DownloadError.Cleanup)
                        }
                    }
                    else -> restorePending(model)
                }
            }
        }
    }

    private suspend fun restorePending(model: LibraryModel) {
        try {
            if (model.downloadStatus == DownloadStatus.Verifying && published(model)) {
                complete(model)
                return
            }
            val scheduled = scheduler.state(model.workId)
            if (scheduled == ScheduledState.Running) return
            storage.clearSource(model.storageKey)
            when (scheduled) {
                null -> schedule(reset(model))
                ScheduledState.Finished -> fail(model, DownloadError.Interrupted)
                ScheduledState.Waiting -> reset(model)
                ScheduledState.Running -> Unit
            }
        } catch (failure: DownloadFailure) {
            fail(model, failure.code)
        } catch (_: IOException) {
            fail(model, DownloadError.Cleanup)
        }
    }

    private suspend fun schedule(model: LibraryModel): Boolean = try {
        scheduler.enqueue(model.storageKey, model.workId)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fail(model, DownloadError.Scheduling)
        false
    }

    /** The transfer lock covers cancelled workers until their HTTP response and file handles close. */
    suspend fun run(
        key: String,
        id: String,
        foreground: suspend (LibraryModel) -> Unit,
        notification: (LibraryModel) -> Unit
    ) = withContext(Dispatchers.IO) {
        transfers.withLock {
            supervisorScope {
                var registered = false
                var failure: DownloadError? = null
                try {
                    val task = commands.withLock {
                        val model = store.find(key)?.takeIf { it.workId == id && it.downloadStatus.pending }
                            ?: return@withLock null
                        async(start = CoroutineStart.LAZY) {
                            if (model.downloadStatus == DownloadStatus.Verifying && published(model)) {
                                updatePending(key) { it.copy(downloadStatus = DownloadStatus.Downloaded,
                                    fileSize = checkNotNull(it.totalBytes)) }
                                return@async
                            }
                            foreground(model)
                            transfer(model, notification)
                        }.also {
                            active = key to it
                            registered = true
                        }
                    }
                    task?.await()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (error: DownloadFailure) {
                    failure = error.code
                } catch (_: SQLiteFullException) {
                    failure = DownloadError.NoSpace
                } catch (_: IOException) {
                    failure = DownloadError.Network
                } finally {
                    if (registered) withContext(NonCancellable) {
                        // await() can return early when the worker is stopped by the OS.
                        val task = commands.withLock { active?.second }
                        task?.cancel()
                        task?.join()
                        commands.withLock {
                            active = null
                            val model = store.find(key) ?: return@withLock
                            finish(model, failure)
                        }
                    }
                }
            }
        }
    }

    private suspend fun transfer(model: LibraryModel, notification: (LibraryModel) -> Unit) {
        val key = model.storageKey
        try {
            storage.clearSource(key)
            val directory = storage.prepare(key)
            if (directory.usableSpace == 0L) throw DownloadFailure(DownloadError.NoSpace)
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (_: IOException) {
            throw DownloadFailure(DownloadError.FileWrite)
        }
        val accessToken = token()
        saveThumbnail(model)
        val result = transfer.download(model, accessToken, storage.partial(key), progress = { progress ->
            notification(updatePending(key) {
                it.copy(downloadStatus = DownloadStatus.Downloading, downloadedBytes = progress.bytes,
                    totalBytes = progress.total)
            })
        }, verifying = { bytes ->
            notification(updatePending(key) {
                it.copy(downloadStatus = DownloadStatus.Verifying, downloadedBytes = bytes, totalBytes = bytes)
            })
        })
        commands.withLock {
            currentCoroutineContext().ensureActive()
            val current = pending(key)
            withContext(NonCancellable) {
                val verified = current.copy(sha256 = result.sha256)
                check(result.bytes == verified.totalBytes)
                store.update(verified)
                try {
                    storage.publishCheckpoint(key)
                } catch (_: IOException) {
                    throw DownloadFailure(DownloadError.FileWrite)
                }
                complete(verified)
            }
        }
    }

    private suspend fun saveThumbnail(model: LibraryModel) {
        val url = model.thumbnailUrl ?: return
        val key = model.storageKey
        try {
            if (storage.thumbnail(key).isFile) return
            val bytes = transfer.thumbnail(url)
            writeThumbnail(bytes, storage.thumbnailPartial(key))
            currentCoroutineContext().ensureActive()
            storage.publishThumbnail(key)
        } catch (_: IOException) {
            // The checkpoint can still be downloaded with a placeholder.
        }
    }

    private suspend fun updatePending(key: String, update: (LibraryModel) -> LibraryModel): LibraryModel =
        commands.withLock { update(pending(key)).also { store.update(it) } }

    private suspend fun pending(key: String): LibraryModel =
        store.find(key)?.takeIf { it.downloadStatus.pending } ?: throw CancellationException()

    private suspend fun finish(model: LibraryModel, failure: DownloadError?) {
        when {
            model.downloadStatus == DownloadStatus.Cancelling -> deleteFiles(model)
            model.downloadStatus.pending -> {
                try {
                    if (model.downloadStatus == DownloadStatus.Verifying && published(model)) {
                        complete(model)
                        return
                    }
                    storage.clearSource(model.storageKey)
                } catch (error: DownloadFailure) {
                    fail(model, failure ?: error.code)
                    return
                } catch (_: IOException) {
                    fail(model, DownloadError.Cleanup)
                    return
                }
                if (failure == null) reset(model) else fail(model, failure)
            }
        }
    }

    private suspend fun deleteFiles(model: LibraryModel) {
        try {
            storage.delete(model.storageKey)
        } catch (_: IOException) {
            fail(model, DownloadError.Cleanup)
            return
        }
        store.delete(model)
    }

    private fun published(model: LibraryModel): Boolean {
        val bytes = model.totalBytes?.takeIf { it > 0 } ?: return false
        return storage.hasCheckpoint(model.storageKey, bytes)
    }

    private suspend fun complete(model: LibraryModel) {
        val bytes = checkNotNull(model.totalBytes)
        store.update(model.copy(downloadStatus = DownloadStatus.Downloaded, fileSize = bytes))
    }

    private suspend fun reset(model: LibraryModel): LibraryModel =
        model.copy(downloadStatus = DownloadStatus.Queued, downloadedBytes = 0, totalBytes = null)
            .also { if (it != model) store.update(it) }

    private suspend fun fail(model: LibraryModel, error: DownloadError) {
        store.update(model.copy(downloadStatus = DownloadStatus.Failed, errorCode = error))
    }
}
