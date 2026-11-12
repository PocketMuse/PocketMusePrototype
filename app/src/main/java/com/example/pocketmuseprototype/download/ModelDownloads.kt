package com.example.pocketmuseprototype.download

import android.database.sqlite.SQLiteFullException
import android.util.Log
import com.example.pocketmuseprototype.catalog.CatalogCheckpoint
import com.example.pocketmuseprototype.conversion.ConversionErrorCode
import com.example.pocketmuseprototype.conversion.ConversionOptions
import com.example.pocketmuseprototype.conversion.ConversionProgress
import com.example.pocketmuseprototype.conversion.ConversionRequest
import com.example.pocketmuseprototype.conversion.ConversionResult
import com.example.pocketmuseprototype.conversion.ConversionStage
import com.example.pocketmuseprototype.conversion.ConversionStatus
import com.example.pocketmuseprototype.conversion.HqqTemplate
import com.example.pocketmuseprototype.conversion.decodeOutputFiles
import com.example.pocketmuseprototype.conversion.outputAttributes
import com.example.pocketmuseprototype.conversion.sha256
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal enum class ScheduledState { Waiting, Running, Finished }

internal interface ModelWorkScheduler {
    suspend fun enqueue(key: String, id: String)
    suspend fun state(id: String): ScheduledState?
}

internal enum class EnqueueResult { Queued, Exists, StorageUnavailable, SchedulingFailed }

internal data class LibraryItem(val model: LibraryModel, val thumbnail: File?)

/** Process-scoped owner of Room state and filesystem mutations. Workers and UI share this instance. */
internal class ModelDownloads(
    private val store: LibraryStore,
    private val storage: ModelStorage,
    private val scheduler: ModelWorkScheduler,
    private val token: suspend () -> String,
    private val transfer: CivitaiTransfer,
    private val writeThumbnail: (ByteArray, File) -> Unit,
    private val conversionScheduler: ModelWorkScheduler,
    private val template: () -> HqqTemplate,
    private val sharedLcm: SharedLcmDownloads
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
    private val reconciliation = Mutex()
    private val transfers = Mutex()
    private val conversions = Mutex()
    private var active: Pair<String, Job>? = null
    private var convertingKey: String? = null
    private var conversionRequest: ConversionRequest? = null
    private data class CheckedOutput(val timestamps: Map<String, FileTime>, val hashes: String)

    private val checkedOutputs = ConcurrentHashMap<String, CheckedOutput>()

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
                    current.downloadStatus in setOf(DownloadStatus.Downloaded, DownloadStatus.Failed, DownloadStatus.Deleting) -> {
                    val deleting = current.copy(downloadStatus = DownloadStatus.Deleting, errorCode = null)
                    store.update(deleting)
                    if (convertingKey == current.storageKey) conversionRequest?.requestCancel()
                    else deleteFiles(deleting)
                }
            }
        }
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        reconciliation.withLock {
            val outputs = commands.withLock {
                val checks = mutableListOf<LibraryModel>()
                for (model in store.all()) {
                    val key = model.storageKey
                    val waitingForLcm = model.downloadStatus == DownloadStatus.Downloaded &&
                        model.conversionStatus == ModelConversionStatus.WaitingForLcm
                    if (!waitingForLcm && (active?.first == key || convertingKey == key)) continue
                    when (model.downloadStatus) {
                        DownloadStatus.Cancelling, DownloadStatus.Deleting -> deleteFiles(model)
                        DownloadStatus.Downloaded -> try {
                            storage.clearPartial(key)
                            if (!published(model)) fail(model, DownloadError.MissingFile)
                            else if (reconcileConversion(model)) checks += model
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
                checks
            }
            for (model in outputs) reconcileOutput(model)
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
                                commands.withLock { complete(pending(key)) }
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
            val failed = model.copy(downloadStatus = DownloadStatus.Deleting, errorCode = DownloadError.Cleanup)
            if (failed != model) store.update(failed)
            return
        }
        model.conversionWorkId?.let(checkedOutputs::remove)
        store.delete(model)
    }

    private fun published(model: LibraryModel): Boolean {
        val bytes = model.totalBytes?.takeIf { it > 0 } ?: return false
        return storage.hasCheckpoint(model.storageKey, bytes)
    }

    private suspend fun complete(model: LibraryModel) {
        val bytes = checkNotNull(model.totalBytes)
        startStaticConversion(model.copy(downloadStatus = DownloadStatus.Downloaded, fileSize = bytes))
    }

    private suspend fun reset(model: LibraryModel): LibraryModel =
        model.copy(downloadStatus = DownloadStatus.Queued, downloadedBytes = 0, totalBytes = null)
            .also { if (it != model) store.update(it) }

    private suspend fun fail(model: LibraryModel, error: DownloadError) {
        store.update(model.copy(downloadStatus = DownloadStatus.Failed, errorCode = error))
    }

    suspend fun startConversion(model: LibraryModel): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        commands.withLock {
            val current = store.find(model.storageKey) ?: return@withLock false
            if (current.workId != model.workId || current.conversionWorkId != model.conversionWorkId ||
                current.downloadStatus != DownloadStatus.Downloaded || !current.canStartConversion ||
                convertingKey == current.storageKey
            ) return@withLock false
            try {
                if (!published(current)) throw ConversionFailure("SOURCE_INVALID")
                if (current.conversionStatus == ModelConversionStatus.Converted) {
                    val legacy = LegacyOutput(
                        current.conversionWorkId ?: return@withLock false,
                        current.conversionTemplateId ?: return@withLock false,
                        current.conversionFiles ?: return@withLock false,
                        current.conversionHashes ?: return@withLock false
                    )
                    startStaticConversion(current.copy(legacyOutput = legacy))
                } else {
                    current.conversionWorkId?.let { storage.discardConversion(current.storageKey, it) }
                    startStaticConversion(current)
                }
                true
            } catch (failure: ConversionFailure) {
                if (current.conversionStatus != ModelConversionStatus.Converted) conversionFailed(current, failure.code)
                false
            } catch (_: IOException) {
                if (current.conversionStatus != ModelConversionStatus.Converted) {
                    conversionFailed(current, ConversionErrorCode.CLEANUP_FAILED.name)
                }
                false
            }
        }
    }

    suspend fun cancelConversion(model: LibraryModel) = withContext(Dispatchers.IO + NonCancellable) {
        commands.withLock {
            val current = store.find(model.storageKey) ?: return@withLock
            if (current.workId != model.workId ||
                current.downloadStatus != DownloadStatus.Downloaded || !current.conversionStatus.canCancel
            ) return@withLock
            if (current.conversionStatus == ModelConversionStatus.WaitingForLcm) {
                store.update(current.copy(conversionStatus = ModelConversionStatus.Cancelled))
                return@withLock
            }
            val cancelling = current.copy(
                conversionStatus = ModelConversionStatus.Cancelling,
                conversionProgress = null
            )
            store.update(cancelling)
            if (convertingKey == current.storageKey) conversionRequest?.requestCancel()
            else finishCancellation(cancelling)
        }
    }

    private suspend fun startStaticConversion(model: LibraryModel) {
        val waiting = waitForLcm(model)
        if (sharedLcm.isReady()) scheduleConversion(resetConversionProgress(waiting))
    }

    private suspend fun waitForLcm(model: LibraryModel): LibraryModel {
        model.conversionWorkId?.let(checkedOutputs::remove)
        val waiting = model.copy(
            conversionStatus = ModelConversionStatus.WaitingForLcm,
            conversionWorkId = UUID.randomUUID().toString(),
            conversionRecipeId = STATIC_LCM_RECIPE,
            conversionProgress = null,
            conversionErrorCode = null,
            conversionTemplateId = null,
            conversionFiles = null,
            conversionHashes = null
        )
        store.update(waiting)
        return waiting
    }

    private suspend fun scheduleConversion(model: LibraryModel) {
        try {
            conversionScheduler.enqueue(model.storageKey, checkNotNull(model.conversionWorkId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Room retains the attempt ID. Reconciliation checks whether WorkManager accepted it.
            if (model.conversionErrorCode != "SCHEDULING") {
                store.update(model.copy(conversionErrorCode = "SCHEDULING"))
            }
            return
        }
        if (model.conversionErrorCode == "SCHEDULING") store.update(model.copy(conversionErrorCode = null))
    }

    /** Returns true when the output needs to be checked after releasing the command lock. */
    private suspend fun reconcileConversion(model: LibraryModel): Boolean {
        when {
            model.conversionStatus == ModelConversionStatus.Converted -> return true
            model.conversionStatus == ModelConversionStatus.Cancelling -> finishCancellation(model)
            model.conversionStatus == ModelConversionStatus.WaitingForLcm -> {
                if (sharedLcm.isReady()) scheduleConversion(resetConversionProgress(model))
            }
            model.conversionStatus.pending -> when (conversionScheduler.state(checkNotNull(model.conversionWorkId))) {
                null -> {
                    if (model.conversionTemplateId != null) return true
                    scheduleUnregistered(model)
                }
                ScheduledState.Finished -> return true
                ScheduledState.Waiting -> resetConversionProgress(model)
                ScheduledState.Running -> Unit
            }
        }
        return false
    }

    private suspend fun scheduleUnregistered(model: LibraryModel) {
        if (model.conversionRecipeId == STATIC_LCM_RECIPE && !sharedLcm.isReady()) {
            storage.discardConversion(model.storageKey, checkNotNull(model.conversionWorkId))
            waitForLcm(model)
        } else {
            scheduleConversion(resetConversionProgress(model))
        }
    }

    private suspend fun reconcileOutput(model: LibraryModel) {
        val wasConverted = model.conversionStatus == ModelConversionStatus.Converted
        val (hashes, error) = try {
            checkOutput(model) to if (wasConverted) "OUTPUT_INVALID" else "INTERRUPTED"
        } catch (failure: ConversionFailure) {
            null to failure.code
        } catch (_: IOException) {
            return
        }
        commands.withLock {
            // A delete or retry can win while a checksum is being read.
            if (convertingKey == model.storageKey || store.find(model.storageKey) != model) return@withLock
            when {
                hashes != null -> if (wasConverted) retireLegacyOutput(model) else converted(model, hashes)
                error == "INTERRUPTED" -> {
                    when (conversionScheduler.state(checkNotNull(model.conversionWorkId))) {
                        null -> try {
                            scheduleUnregistered(model)
                        } catch (_: IOException) {
                            conversionFailed(model, "CLEANUP_FAILED")
                        }
                        ScheduledState.Finished -> conversionFailed(model, error)
                        ScheduledState.Waiting, ScheduledState.Running -> Unit
                    }
                }
                else -> conversionFailed(model, error)
            }
        }
    }

    private suspend fun resetConversionProgress(model: LibraryModel): LibraryModel =
        model.copy(conversionStatus = ModelConversionStatus.Queued, conversionProgress = null)
            .also { if (it != model) store.update(it) }

    suspend fun runConversion(
        key: String,
        id: String,
        foreground: suspend (LibraryModel) -> Unit,
        notification: (LibraryModel) -> Unit
    ) = withContext(Dispatchers.IO) {
        // WorkManager can release the next worker before a stopped native call has returned.
        conversions.withLock {
            val model = commands.withLock {
                val current = pendingConversion(key, id) ?: return@withLock null
                convertingKey = key
                current
            } ?: return@withLock
            try {
                convert(model, id, foreground, notification)
            } catch (error: ConversionFailure) {
                commands.withLock { pendingConversion(key, id)?.let { conversionFailed(it, error.code) } }
            } catch (_: SQLiteFullException) {
                commands.withLock { pendingConversion(key, id)?.let { conversionFailed(it, "INSUFFICIENT_SPACE") } }
            } catch (_: IOException) {
                commands.withLock { pendingConversion(key, id)?.let { conversionFailed(it, "FILE_IO") } }
            } catch (_: LinkageError) {
                commands.withLock { pendingConversion(key, id)?.let { conversionFailed(it, "NATIVE_UNAVAILABLE") } }
            } finally {
                val stopped = !currentCoroutineContext().isActive
                withContext(NonCancellable) {
                    val check = commands.withLock {
                        conversionRequest = null
                        convertingKey = null
                        val current = store.find(key) ?: return@withLock null
                        when {
                            current.downloadStatus == DownloadStatus.Deleting -> deleteFiles(current)
                            current.conversionStatus == ModelConversionStatus.Cancelling -> finishCancellation(current)
                            stopped && current.conversionStatus.pending -> if (reconcileConversion(current)) {
                                return@withLock current
                            }
                        }
                        null
                    }
                    if (check != null) reconcileOutput(check)
                }
            }
        }
    }

    private suspend fun convert(
        model: LibraryModel,
        id: String,
        foreground: suspend (LibraryModel) -> Unit,
        notification: (LibraryModel) -> Unit
    ) {
        val key = model.storageKey
        val validating = commands.withLock {
            val current = pendingConversion(key, id) ?: return
            current.copy(conversionStatus = ModelConversionStatus.Validating, conversionProgress = null)
                .also { store.update(it) }
        }
        val checkpoint = storage.checkpoint(key)
        if (!published(model) || !checkpoint.hasCheckpointHeader()) throw ConversionFailure("SOURCE_INVALID")
        val output = storage.conversionOutput(key, id)
        val recovered = checkOutput(model)
        if (recovered != null) {
            commands.withLock { pendingConversion(key, id)?.let { converted(it, recovered) } }
            return
        }
        val staticLcm = when (model.conversionRecipeId) {
            STATIC_LCM_RECIPE -> true
            LEGACY_HQQ_RECIPE -> false
            else -> throw ConversionFailure("OUTPUT_INVALID")
        }
        if (staticLcm && !sharedLcm.isReady()) {
            restoreLcmWait(key, id)
            return
        }
        foreground(validating)
        // A previous attempt at this work ID may have published an invalid final directory.
        storage.discardConversion(key, id)
        val hqq = try {
            template()
        } catch (_: IOException) {
            throw ConversionFailure("TEMPLATE_INVALID")
        }
        val parent = checkNotNull(output.parentFile)
        Files.createDirectories(parent.toPath())
        val cleanup = storage.cleanupStaging(output)
        if (cleanup != ConversionErrorCode.NONE) throw ConversionFailure(cleanup.name)
        val retiredCleanup = storage.cleanupRetiredStaging(key, output)
        if (retiredCleanup != ConversionErrorCode.NONE) throw ConversionFailure(retiredCleanup.name)
        if (parent.usableSpace < hqq.files.values.sum()) throw ConversionFailure("INSUFFICIENT_SPACE")
        val files = Json.encodeToString(hqq.files)
        suspend fun execute(lora: File?) {
            val request = ConversionRequest(
                ConversionOptions(checkpoint, hqq.directory, output, threadCount = 4, lcmLora = lora)
            )
            // This metadata binds any native publication at this path to the attempt's durable recipe.
            commands.withLock {
                val current = pendingConversion(key, id) ?: return
                store.update(current.copy(conversionTemplateId = hqq.id, conversionFiles = files,
                    conversionErrorCode = null))
                conversionRequest = request
            }
            checkNativeResult(runNative(request) { progress -> updateConversionProgress(key, id, progress, notification) }, hqq)
        }
        if (staticLcm) {
            if (sharedLcm.withVerifiedFile { execute(it) } == null) {
                restoreLcmWait(key, id)
                return
            }
        } else {
            execute(null)
        }
        val finalizing = commands.withLock {
            val current = pendingConversion(key, id) ?: return
            current.copy(conversionStatus = ModelConversionStatus.Finalizing, conversionProgress = null)
                .also {
                    store.update(it)
                    notification(it)
                }
        }
        val hashes = checkOutput(finalizing) ?: throw ConversionFailure("OUTPUT_INVALID")
        commands.withLock { pendingConversion(key, id)?.let { converted(it, hashes) } }
    }

    private suspend fun restoreLcmWait(key: String, id: String) {
        commands.withLock {
            val current = pendingConversion(key, id) ?: return@withLock
            storage.discardConversion(key, id)
            // The Ready event can arrive between a failed lease acquisition and this state update.
            startStaticConversion(current)
        }
    }

    private suspend fun updateConversionProgress(
        key: String, id: String, progress: ConversionProgress, notification: (LibraryModel) -> Unit
    ) {
        val status = when (progress.stage) {
            ConversionStage.Validating -> ModelConversionStatus.Validating
            ConversionStage.Converting -> ModelConversionStatus.Converting
            ConversionStage.Finalizing, ConversionStage.Completed -> ModelConversionStatus.Finalizing
        }
        commands.withLock {
            val current = pendingConversion(key, id) ?: return@withLock
            val percent = if (status == ModelConversionStatus.Converting) {
                (progress.fraction.coerceIn(0.0, 1.0) * 100).toInt()
            } else null
            val updated = current.copy(conversionStatus = status, conversionProgress = percent)
            if (updated != current) {
                store.update(updated)
                notification(updated)
            }
        }
    }

    private fun checkNativeResult(result: ConversionResult, hqq: HqqTemplate) {
        when (result.status) {
            ConversionStatus.Cancelled -> throw ConversionFailure("INTERRUPTED")
            ConversionStatus.Failed -> throw ConversionFailure(result.errorCode.name)
            ConversionStatus.Succeeded -> Unit
        }
        if (result.files.associate { it.name to it.sizeBytes } != hqq.files) throw ConversionFailure("OUTPUT_INVALID")
        Log.d("ModelConversion", "Native conversion: ${result.elapsedMillis} ms, HQQ: ${result.hqqComputeMillis} ms")
    }

    private suspend fun runNative(request: ConversionRequest, publish: suspend (ConversionProgress) -> Unit) =
        coroutineScope {
            val progress = Channel<ConversionProgress>(Channel.CONFLATED)
            val updates = launch { for (value in progress) publish(value) }
            val native = async {
                var stage: ConversionStage? = null
                var lastUpdate = 0L
                request.run { value ->
                    val now = System.nanoTime()
                    if (stage != value.stage || now - lastUpdate >= PROGRESS_INTERVAL_NS) {
                        stage = value.stage
                        lastUpdate = now
                        progress.trySend(value)
                    }
                }
            }
            try {
                native.await()
            } finally {
                request.requestCancel()
                withContext(NonCancellable) {
                    native.join()
                    updates.cancelAndJoin()
                }
            }
        }

    private suspend fun pendingConversion(key: String, id: String): LibraryModel? =
        store.find(key)?.takeIf {
            it.downloadStatus == DownloadStatus.Downloaded && it.conversionWorkId == id && it.conversionStatus.pending
        }

    private fun checkOutput(model: LibraryModel): String? {
        val id = model.conversionWorkId ?: return null
        if (model.conversionRecipeId != LEGACY_HQQ_RECIPE && model.conversionRecipeId != STATIC_LCM_RECIPE) {
            throw ConversionFailure("OUTPUT_INVALID")
        }
        if (model.conversionTemplateId == null) return null
        val files = model.conversionFiles ?: return null
        val expected = decodeOutputFiles(files) ?: throw ConversionFailure("OUTPUT_INVALID")
        val output = storage.conversionOutput(model.storageKey, id)
        val attributes = outputAttributes(output, expected) ?: return null
        val timestamps = attributes.mapValues { it.value.lastModifiedTime() }
        val recorded = model.conversionHashes
        if (recorded != null && checkedOutputs[id] == CheckedOutput(timestamps, recorded)) return recorded
        val hashes = Json.encodeToString(expected.mapValues { (name, _) -> File(output, name).sha256() })
        if (recorded != null && recorded != hashes) return null
        if (outputAttributes(output, expected)?.mapValues { it.value.lastModifiedTime() } != timestamps) return null
        checkedOutputs[id] = CheckedOutput(timestamps, hashes)
        return hashes
    }

    private suspend fun converted(model: LibraryModel, hashes: String) {
        val published = model.copy(
            conversionStatus = ModelConversionStatus.Converted,
            conversionProgress = 100,
            conversionErrorCode = null,
            conversionHashes = hashes
        )
        store.update(published)
        retireLegacyOutput(published)
    }

    private suspend fun retireLegacyOutput(model: LibraryModel) {
        val legacy = model.legacyOutput ?: return
        try {
            storage.discardConversion(model.storageKey, legacy.workId)
        } catch (_: IOException) {
            // Keep the previous path recorded until deletion succeeds.
            return
        }
        checkedOutputs.remove(legacy.workId)
        store.update(model.copy(legacyOutput = null))
    }

    private suspend fun finishCancellation(model: LibraryModel) {
        try {
            val id = checkNotNull(model.conversionWorkId)
            val cleanup = storage.cleanupStaging(storage.conversionOutput(model.storageKey, id))
            if (cleanup == ConversionErrorCode.BUSY) return
            if (cleanup != ConversionErrorCode.NONE) throw ConversionFailure(cleanup.name)
            storage.discardConversion(model.storageKey, id)
            store.update(model.copy(conversionStatus = ModelConversionStatus.Cancelled, conversionProgress = null))
        } catch (error: ConversionFailure) {
            conversionFailed(model, error.code)
        } catch (_: IOException) {
            conversionFailed(model, "CLEANUP_FAILED")
        } catch (_: LinkageError) {
            conversionFailed(model, "NATIVE_UNAVAILABLE")
        }
    }

    private suspend fun conversionFailed(model: LibraryModel, error: String) {
        store.update(model.copy(
            conversionStatus = ModelConversionStatus.Failed,
            conversionProgress = null,
            conversionErrorCode = error
        ))
    }
}

internal class ConversionFailure(val code: String) : IOException()

private const val PROGRESS_INTERVAL_NS = 500_000_000L
