package com.example.pocketmuseprototype.download

import androidx.work.WorkInfo
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface SharedLcmState {
    data object Missing : SharedLcmState
    data object Queued : SharedLcmState
    data class Downloading(val bytes: Long) : SharedLcmState
    data object Verifying : SharedLcmState
    data object Ready : SharedLcmState
    data class Failed(val error: DownloadError) : SharedLcmState
}

internal enum class SharedLcmCancellation { Cancelled, AlreadyReady, NoActiveWork, Failed }

internal data class SharedLcmWork(
    val id: UUID,
    val state: WorkInfo.State,
    val bytes: Long? = null,
    val verifying: Boolean = false,
    val error: DownloadError? = null
)

internal interface SharedLcmScheduler {
    val work: Flow<SharedLcmWork?>
    suspend fun enqueue()
    suspend fun cancel(id: UUID)
}

/** The app, Worker, and future conversion callers share this process-scoped owner. */
internal class SharedLcmDownloads(
    private val storage: SharedLcmStorage,
    private val scheduler: SharedLcmScheduler,
    private val transfer: SharedLcmTransfer = SharedLcmTransfer()
) {
    private val commands = Mutex()
    private val transfers = Mutex()
    private val files = Mutex()
    private val fileUse = Mutex()
    private val revision = MutableStateFlow(0)
    private val schedulingError = MutableStateFlow<DownloadError?>(null)

    val state: Flow<SharedLcmState> = combine(scheduler.work, revision, schedulingError) { work, _, scheduleError ->
        files.withLock {
            try {
                val problem = storage.problem()
                when {
                    problem == null -> SharedLcmState.Ready
                    work?.state == WorkInfo.State.RUNNING -> when {
                        work.verifying -> SharedLcmState.Verifying
                        work.bytes != null -> SharedLcmState.Downloading(work.bytes)
                        else -> SharedLcmState.Queued
                    }
                    work != null && !work.state.isFinished -> SharedLcmState.Queued
                    scheduleError != null -> SharedLcmState.Failed(scheduleError)
                    problem != DownloadError.MissingFile -> SharedLcmState.Failed(problem)
                    else -> missingState(work)
                }
            } catch (_: IOException) {
                SharedLcmState.Failed(DownloadError.FileWrite)
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun missingState(work: SharedLcmWork?): SharedLcmState {
        val saved = storage.lastResult(work?.id)
        if (saved == REMOVED || work?.state == WorkInfo.State.CANCELLED) return SharedLcmState.Missing
        val error = when (work?.state) {
            WorkInfo.State.SUCCEEDED -> DownloadError.MissingFile
            WorkInfo.State.FAILED -> work.error ?: DownloadError.Interrupted
            else -> DownloadError.entries.firstOrNull { it.name == saved }
        }
        return error?.let(SharedLcmState::Failed) ?: SharedLcmState.Missing
    }

    private fun refresh() = revision.update { it + 1 }

    suspend fun reconcile() = commands.withLock {
        try {
            withContext(Dispatchers.IO) {
                val work = scheduler.work.first()
                if (work != null && !work.state.isFinished &&
                    files.withLock { storage.lastResult(work.id) == REMOVED }
                ) {
                    stopAndClean(work.id)
                    return@withContext
                }
                if (!transfers.tryLock()) return@withContext
                try {
                    if (work == null || work.state.isFinished) storage.clearPartial()
                    if (work?.state == WorkInfo.State.CANCELLED) {
                        files.withLock {
                            if (storage.problem() != null && storage.lastResult(work.id) != REMOVED) {
                                storage.recordResult(work.id, REMOVED)
                            }
                        }
                    }
                } finally {
                    transfers.unlock()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A recorded cancellation can be retried at the next reconciliation.
        } finally {
            refresh()
        }
    }

    /** Returns true when this request registered work. */
    suspend fun ensure(): Boolean = commands.withLock {
        try {
            val work = scheduler.work.first()
            if (work != null && !work.state.isFinished) return@withLock false
            val ready = withContext(Dispatchers.IO) { files.withLock { storage.problem() == null } }
            if (ready) return@withLock false
            scheduler.enqueue()
            schedulingError.value = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            schedulingError.value = DownloadError.FileWrite
            false
        } catch (_: Exception) {
            schedulingError.value = DownloadError.Scheduling
            false
        } finally {
            refresh()
        }
    }

    suspend fun cancel(): SharedLcmCancellation = commands.withLock {
        try {
            val work = scheduler.work.first()
            val recorded = withContext(Dispatchers.IO) {
                files.withLock {
                    when {
                        storage.problem() == null -> SharedLcmCancellation.AlreadyReady
                        work == null || work.state.isFinished -> SharedLcmCancellation.NoActiveWork
                        else -> {
                            storage.recordResult(work.id, REMOVED)
                            SharedLcmCancellation.Cancelled
                        }
                    }
                }
            }
            if (recorded == SharedLcmCancellation.Cancelled) stopAndClean(checkNotNull(work).id)
            recorded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SharedLcmCancellation.Failed
        } finally {
            refresh()
        }
    }

    private suspend fun stopAndClean(id: UUID) {
        scheduler.cancel(id)
        withContext(Dispatchers.IO) { transfers.withLock { storage.clearPartial() } }
        schedulingError.value = null
    }

    suspend fun delete(): Boolean = commands.withLock {
        try {
            withContext(Dispatchers.IO) {
                if (!files.withLock { storage.problem() == null }) return@withContext false
                transfers.withLock {
                    fileUse.withLock {
                        val work = scheduler.work.first()
                        // A worker may have published but not yet committed its WorkManager result.
                        if (work != null && !work.state.isFinished) scheduler.cancel(work.id)
                        files.withLock { storage.delete(work?.id) }
                    }
                }
                true
            }.also { if (it) schedulingError.value = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            refresh()
        }
    }

    suspend fun recordResult(id: UUID, error: DownloadError?) = withContext(Dispatchers.IO) {
        files.withLock {
            currentCoroutineContext().ensureActive()
            if (storage.lastResult(id) != REMOVED) {
                storage.recordResult(id, (error ?: DownloadError.MissingFile).name)
            }
        }
        refresh()
    }

    /**
     * Supplies an absolute, SHA-256-verified app-owned file on Dispatchers.IO.
     * Returns null when absent or invalid and propagates storage I/O and callback errors.
     * Keep every read, including native work and cancellation cleanup, inside [read].
     * Deletion and publication wait for [read] to return. Do not call asset commands from [read].
     */
    suspend fun <T : Any> withVerifiedFile(read: suspend (File) -> T): T? = withContext(Dispatchers.IO) {
        fileUse.withLock {
            val file = files.withLock { storage.verifiedFile() } ?: return@withLock null
            read(file)
        }
    }

    /** Returns whether this attempt made or found a verified file. */
    suspend fun run(
        id: UUID,
        foreground: suspend () -> Unit,
        progress: suspend (SharedLcmState) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        transfers.withLock {
            val existing = files.withLock {
                when {
                    storage.lastResult(id) == REMOVED -> false
                    storage.problem() == null -> true
                    else -> null
                }
            }
            if (existing != null) {
                if (!existing) storage.clearPartial()
                return@withLock existing
            }
            foreground()
            progress(SharedLcmState.Downloading(0))
            fileUse.withLock {
                files.withLock {
                    val problem = storage.problem()
                    if (problem != null && problem != DownloadError.MissingFile) storage.removeFinal()
                }
            }
            val partial = storage.prepare()
            try {
                transfer.download(partial, progress)
                fileUse.withLock {
                    files.withLock publish@{
                        currentCoroutineContext().ensureActive()
                        // Cancel and atomic publication commit under the same file lock.
                        if (storage.lastResult(id) == REMOVED) return@publish false
                        if (storage.problem() != null) storage.publish()
                        true
                    }
                }
            } finally {
                try {
                    storage.clearPartial()
                } finally {
                    refresh()
                }
            }
        }
    }
}
