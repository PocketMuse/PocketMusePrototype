package com.example.pocketmuseprototype.download

import androidx.work.WorkInfo
import com.example.pocketmuseprototype.catalog.CatalogCheckpoint
import com.example.pocketmuseprototype.conversion.ConversionErrorCode
import com.example.pocketmuseprototype.conversion.HqqTemplate
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloadsTest {
    @get:Rule val folder = TemporaryFolder()
    private val store = MemoryStore()
    private val scheduler = FakeScheduler()
    private val conversions = FakeScheduler()
    private var mounted = true
    private var cleanStaging: (File) -> ConversionErrorCode = { error("Unexpected staging cleanup") }
    private val storage = ModelStorage(cleanupPartial = { cleanStaging(it) }) { folder.root.takeIf { mounted } }
    private val checkpoint = safeTensor()
    private val digest = MessageDigest.getInstance("SHA-256").digest(checkpoint).toHexString()
    private var token: String? = "fake-token"
    private var downloadCode = 302
    private var thumbnailRequests = 0
    private var body = { checkpoint.toResponseBody() }
    private var selectedFile = 30L
    private var listener = EventListener.NONE
    private var metadataHash: String? = null
    private var time = 0L
    private val lora = "verified fixture".encodeToByteArray()
    private val sharedWork = MutableStateFlow<SharedLcmWork?>(null)
    private val loraStorage by lazy {
        SharedLcmStorage(folder.root, lora.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(lora).toHexString())
    }
    private val shared by lazy {
        SharedLcmDownloads(loraStorage, object : SharedLcmScheduler {
            override val work = sharedWork
            override suspend fun enqueue() {
                work.value = SharedLcmWork(UUID.randomUUID(), WorkInfo.State.ENQUEUED)
            }
            override suspend fun cancel(id: UUID) {
                work.value = SharedLcmWork(id, WorkInfo.State.CANCELLED)
            }
        })
    }

    @Before fun publishLora() {
        loraStorage.prepare().writeBytes(lora)
        loraStorage.publish()
    }

    @Test fun publishedCheckpointWaitsUntilVerifiedLoraIsReady() = runBlocking {
        loraStorage.removeFinal()
        val downloads = downloads()
        val model = downloadedFixture(downloads)
        assertEquals(DownloadStatus.Downloaded, model.downloadStatus)
        assertEquals(ModelConversionStatus.WaitingForLcm, model.conversionStatus)
        assertTrue(storage.checkpoint(model.storageKey).isFile)
        assertTrue(conversions.enqueued.isEmpty())
        // Give the asset the expected length but the wrong digest.
        val invalid = loraStorage.prepare()
        invalid.writeBytes(ByteArray(lora.size))
        Files.move(invalid.toPath(), invalid.resolveSibling(Sd15LcmLora.FILE_NAME).toPath())
        downloads.reconcile()
        assertTrue(conversions.enqueued.isEmpty())

        publishLora()
        val reopened = downloads()
        val first = async { reopened.reconcile() }
        val duplicate = async { reopened.reconcile() }
        first.await()
        duplicate.await()
        assertEquals(ModelConversionStatus.Queued, store.all().single().conversionStatus)
        assertEquals(listOf(model.conversionWorkId), conversions.enqueued)
    }

    @Test fun concurrentReadyAndCheckpointCompletionScheduleExactlyOnce() = runBlocking {
        loraStorage.removeFinal()
        val downloads = downloads()
        val model = enqueue(downloads)
        val verifying = CompletableDeferred<Unit>()
        val publishCheckpoint = CountDownLatch(1)
        val finishing = async {
            downloads.run(model.storageKey, model.workId, {}, notification = {
                if (it.downloadStatus == DownloadStatus.Verifying) {
                    verifying.complete(Unit)
                    check(publishCheckpoint.await(10, TimeUnit.SECONDS))
                }
            })
        }
        try {
            withTimeout(10_000) { verifying.await() }
            publishLora()
            val ready = async { downloads.reconcile() }
            publishCheckpoint.countDown()
            finishing.await()
            ready.await()
        } finally {
            publishCheckpoint.countDown()
        }
        val queued = store.all().single()
        assertEquals(ModelConversionStatus.Queued, queued.conversionStatus)
        assertEquals(STATIC_LCM_RECIPE, queued.conversionRecipeId)
        assertEquals(listOf(queued.conversionWorkId), conversions.enqueued)
    }

    @Test fun failedAndCancelledLoraKeepCheckpointUntilRedownload() = runBlocking {
        assertTrue(shared.delete())
        val downloads = downloads()
        val waiting = downloadedFixture(downloads)
        assertTrue(shared.ensure())
        sharedWork.value = checkNotNull(sharedWork.value).copy(state = WorkInfo.State.FAILED, error = DownloadError.Network)
        assertEquals(SharedLcmState.Failed(DownloadError.Network), shared.state.first())
        downloads.reconcile()
        assertTrue(shared.ensure())
        assertEquals(SharedLcmCancellation.Cancelled, shared.cancel())
        downloads.reconcile()
        assertEquals(waiting, store.all().single())
        assertTrue(storage.checkpoint(waiting.storageKey).isFile)
        assertTrue(conversions.enqueued.isEmpty())
        assertTrue(shared.ensure())
        publishLora()
        downloads.reconcile()
        assertEquals(listOf(waiting.conversionWorkId), conversions.enqueued)
    }

    @Test fun readyEventSkipsDeletedAndCancelledModels() = runBlocking {
        assertTrue(shared.delete())
        val downloads = downloads()
        val deleted = downloadedFixture(downloads)
        val cancelled = enqueue(downloads, candidate(fileId = 31))
        selectedFile = cancelled.fileId
        run(downloads, cancelled)
        downloads.remove(deleted)
        downloads.cancelConversion(checkNotNull(store.find(cancelled.storageKey)))
        publishLora()
        downloads.reconcile()
        assertTrue(conversions.enqueued.isEmpty())
        assertFalse(storage.checkpoint(deleted.storageKey).exists())
        assertEquals(ModelConversionStatus.Cancelled, store.all().single().conversionStatus)
        assertTrue(storage.checkpoint(cancelled.storageKey).isFile)
    }

    @Test fun oneSharedLoraQueuesEachWaitingModelWithItsOwnOutputPath() = runBlocking {
        assertTrue(shared.delete())
        val downloads = downloads()
        downloadedFixture(downloads)
        val second = enqueue(downloads, candidate(fileId = 31))
        selectedFile = second.fileId
        run(downloads, second)
        assertTrue(store.all().all { it.conversionStatus == ModelConversionStatus.WaitingForLcm })
        publishLora()
        downloads.reconcile()
        downloads.reconcile()
        val queued = store.all()
        assertTrue(queued.all { it.conversionStatus == ModelConversionStatus.Queued })
        assertEquals(queued.map { it.conversionWorkId }, conversions.enqueued)
        val paths = queued.map { storage.conversionOutput(it.storageKey, checkNotNull(it.conversionWorkId)) }
        assertEquals(2, paths.toSet().size)
        assertTrue(queued.all { storage.checkpoint(it.storageKey).isFile })
        assertTrue(shared.isReady())
    }

    @Test fun enqueueExceptionRecoversWithThePersistedAttemptId() = runBlocking {
        conversions.failEnqueue = true
        val downloads = downloads()
        val queued = downloadedFixture(downloads)
        assertEquals(ModelConversionStatus.Queued, queued.conversionStatus)
        assertEquals("SCHEDULING", queued.conversionErrorCode)
        assertTrue(conversions.enqueued.isEmpty())
        conversions.failEnqueue = false
        val reopened = downloads()
        reopened.reconcile()
        reopened.reconcile()
        assertEquals(listOf(queued.conversionWorkId), conversions.enqueued)
        assertNull(store.all().single().conversionErrorCode)
    }

    @Test fun loraDeletedBetweenSchedulingAndFileAcquisitionReturnsModelToWaiting() = runBlocking {
        val downloads = downloads { HqqTemplate("fixture", folder.root, mapOf("fixture.mnn" to 4L)) }
        val queued = downloadedFixture(downloads)
        val id = checkNotNull(queued.conversionWorkId)
        downloads.runConversion(queued.storageKey, id, { assertTrue(shared.delete()) }, {})
        val waiting = store.all().single()
        assertEquals(ModelConversionStatus.WaitingForLcm, waiting.conversionStatus)
        assertTrue(waiting.conversionWorkId != id)
        assertNull(waiting.conversionErrorCode)
        assertTrue(storage.checkpoint(queued.storageKey).isFile)
        publishLora()
        downloads.reconcile()
        assertEquals(listOf(id, waiting.conversionWorkId), conversions.enqueued)
    }

    @Test fun streamsVerifiesPublishesAndKeepsThumbnailFailureNonfatal() = runBlocking {
        val downloads = downloads()
        val candidate = candidate(hash = digest, thumbnail = "https://images.example/preview?signature=fake")
        val first = async { downloads.enqueue(candidate) }
        val second = async { downloads.enqueue(candidate) }
        assertEquals(setOf(EnqueueResult.Queued, EnqueueResult.Exists), setOf(first.await(), second.await()))
        val model = store.all().single()
        assertEquals(listOf(model.workId), scheduler.enqueued)
        assertEquals("https://images.example/preview", model.thumbnailUrl)
        val updates = mutableListOf<LibraryModel>()
        downloads.run(model.storageKey, model.workId, {}, notification = {
            assertFalse(storage.checkpoint(model.storageKey).exists())
            updates.add(it)
        })
        assertEquals(
            listOf(DownloadStatus.Queued, DownloadStatus.Downloading, DownloadStatus.Verifying, DownloadStatus.Downloaded),
            store.statuses.distinct()
        )
        assertEquals(0.0f, TransferProgress(updates.first().downloadedBytes, updates.first().totalBytes).fraction)
        assertEquals(checkpoint.size.toLong(), updates.last().downloadedBytes)
        assertEquals(checkpoint.size.toLong(), store.all().single().fileSize)
        assertEquals(digest, store.all().single().sha256)
        val downloaded = store.all().single()
        assertEquals(ModelConversionStatus.Queued, downloaded.conversionStatus)
        assertEquals(STATIC_LCM_RECIPE, downloaded.conversionRecipeId)
        assertEquals(listOf(downloaded.conversionWorkId), conversions.enqueued)
        assertTrue(storage.checkpoint(model.storageKey).readBytes().contentEquals(checkpoint))
        assertFalse(storage.partial(model.storageKey).exists())
        assertFalse(storage.thumbnail(model.storageKey).exists())
        assertEquals(1, thumbnailRequests)
        assertEquals(EnqueueResult.Exists, downloads.enqueue(candidate))
    }

    @Test fun failedDownloadDoesNotScheduleConversion() = runBlocking {
        downloadCode = 403
        val downloads = downloads()
        val model = enqueue(downloads)
        downloads.run(model.storageKey, model.workId, {}, {})
        val failed = store.all().single()
        assertEquals(DownloadStatus.Failed, failed.downloadStatus)
        assertEquals(ModelConversionStatus.NotStarted, failed.conversionStatus)
        assertTrue(conversions.enqueued.isEmpty())
    }

    @Test fun checkpointFailureAndCancellationLeaveSharedWorkQueued() = runBlocking {
        val sharedWork = MutableStateFlow<SharedLcmWork?>(null)
        val shared = SharedLcmDownloads(SharedLcmStorage(folder.root), object : SharedLcmScheduler {
            override val work = sharedWork
            override suspend fun enqueue() {
                work.value = SharedLcmWork(UUID.randomUUID(), WorkInfo.State.ENQUEUED)
            }
            override suspend fun cancel(id: UUID) = error("Checkpoint cancellation reached shared work")
        })
        assertTrue(shared.ensure())
        val original = sharedWork.value
        val downloads = downloads()
        val failing = enqueue(downloads)
        downloadCode = 403
        downloads.run(failing.storageKey, failing.workId, {}, {})
        assertEquals(DownloadStatus.Failed, store.find(failing.storageKey)?.downloadStatus)
        val cancelled = enqueue(downloads, candidate(fileId = 31))
        downloads.remove(cancelled)
        assertNull(store.find(cancelled.storageKey))
        assertEquals(original, sharedWork.value)
        assertEquals(SharedLcmState.Queued, shared.state.first())
    }

    @Test fun persistedConversionIntentIsScheduledOnceAndCancelledWorkerCannotRun() = runBlocking {
        val downloads = downloads()
        val model = downloadedFixture(downloads)
        val id = checkNotNull(model.conversionWorkId)
        conversions.states.remove(id)
        conversions.enqueued.clear()
        downloads.reconcile()
        downloads.reconcile()
        assertEquals(listOf(id), conversions.enqueued)
        downloads.cancelConversion(model)
        downloads.runConversion(model.storageKey, id, { error("Cancelled work started") }, {})
        val cancelled = store.all().single()
        assertEquals(ModelConversionStatus.Cancelled, cancelled.conversionStatus)
        val first = async { downloads.startConversion(cancelled) }
        val second = async { downloads.startConversion(cancelled) }
        first.await()
        second.await()
        val retry = store.all().single()
        assertEquals(2, conversions.enqueued.size)
        assertTrue(retry.conversionWorkId != id)
        assertTrue(storage.checkpoint(model.storageKey).isFile)
        downloads.remove(retry)
        downloads.runConversion(model.storageKey, checkNotNull(retry.conversionWorkId), { error("Deleted work started") }, {})
        assertNull(store.find(model.storageKey))
    }

    @Test fun legacyDownloadWaitsForUserAndFailedConversionCanBeRetried() = runBlocking {
        val downloads = downloads()
        val model = enqueue(downloads)
        storage.checkpoint(model.storageKey).writeBytes(checkpoint)
        val legacy = model.copy(downloadStatus = DownloadStatus.Downloaded, totalBytes = checkpoint.size.toLong())
        store.update(legacy)
        downloads.reconcile()
        assertTrue(conversions.enqueued.isEmpty())
        downloads.startConversion(legacy)
        val queued = store.all().single()
        downloads.runConversion(queued.storageKey, checkNotNull(queued.conversionWorkId), {}, {})
        val failed = store.all().single()
        assertEquals("TEMPLATE_INVALID", failed.conversionErrorCode)
        assertEquals(DownloadStatus.Downloaded, failed.downloadStatus)
        assertTrue(storage.checkpoint(queued.storageKey).isFile)
        downloads.startConversion(failed)
        assertEquals(ModelConversionStatus.Queued, store.all().single().conversionStatus)
        assertEquals(2, conversions.enqueued.size)
    }

    @Test fun legacyOutputSurvivesFailedReplacementAndRetiresAfterStaticPublication() = runBlocking {
        val downloads = downloads()
        val model = downloadedFixture(downloads)
        val id = checkNotNull(model.conversionWorkId)
        val oldOutput = storage.conversionOutput(model.storageKey, id).apply { check(mkdirs()) }
        oldOutput.resolve("fixture.mnn").writeText("HQQ1")
        store.update(model.copy(
            conversionStatus = ModelConversionStatus.Finalizing,
            conversionRecipeId = LEGACY_HQQ_RECIPE,
            conversionTemplateId = "fixture",
            conversionFiles = """{"fixture.mnn":4}"""
        ))
        conversions.states[id] = ScheduledState.Finished
        downloads.reconcile()
        val legacy = store.all().single()
        assertEquals(ModelConversionStatus.Converted, legacy.conversionStatus)
        assertEquals(LEGACY_HQQ_RECIPE, legacy.conversionRecipeId)
        assertEquals(listOf(id), conversions.enqueued)

        val source = storage.checkpoint(model.storageKey)
        assertTrue(source.delete())
        assertFalse(downloads.startConversion(legacy))
        assertEquals(legacy, store.all().single())
        assertTrue(oldOutput.isDirectory)
        source.writeBytes(checkpoint)

        assertTrue(downloads.startConversion(legacy))
        val replacement = store.all().single()
        val replacementId = checkNotNull(replacement.conversionWorkId)
        assertTrue(id != replacementId)
        downloads.runConversion(replacement.storageKey, replacementId, {}, {})
        val failed = store.all().single()
        assertEquals(ModelConversionStatus.Failed, failed.conversionStatus)
        assertEquals(id, failed.legacyOutput?.workId)
        assertEquals(legacy.conversionHashes, failed.legacyOutput?.hashes)
        assertTrue(oldOutput.isDirectory)

        assertTrue(downloads.startConversion(failed))
        val retry = store.all().single()
        val retryId = checkNotNull(retry.conversionWorkId)
        val newOutput = storage.conversionOutput(retry.storageKey, retryId).apply { check(mkdirs()) }
        newOutput.resolve("fixture.mnn").writeText("LCM1")
        store.update(retry.copy(
            conversionStatus = ModelConversionStatus.Finalizing,
            conversionTemplateId = "fixture",
            conversionFiles = """{"fixture.mnn":4}"""
        ))
        conversions.states[retryId] = ScheduledState.Finished
        downloads.reconcile()
        val converted = store.all().single()
        assertEquals(ModelConversionStatus.Converted, converted.conversionStatus)
        assertEquals(STATIC_LCM_RECIPE, converted.conversionRecipeId)
        assertTrue(converted.conversionHashes != legacy.conversionHashes)
        assertNull(converted.legacyOutput)
        assertFalse(oldOutput.exists())
        assertTrue(newOutput.isDirectory)
        assertTrue(storage.checkpoint(model.storageKey).isFile)
    }

    @Test fun recoversPublishedOutputAndRejectsSameSizeCorruption() = runBlocking {
        val downloads = downloads()
        val model = downloadedFixture(downloads)
        val id = checkNotNull(model.conversionWorkId)
        val output = storage.conversionOutput(model.storageKey, id)
        assertTrue(output.mkdirs())
        val file = File(output, "fixture.mnn").apply { writeText("MNN1") }
        store.update(model.copy(
            conversionStatus = ModelConversionStatus.Finalizing,
            conversionTemplateId = "fixture",
            conversionFiles = """{"fixture.mnn":4}"""
        ))
        conversions.states.remove(id)
        assertTrue(shared.delete())
        downloads.reconcile()
        val converted = store.all().single()
        assertEquals(ModelConversionStatus.Converted, converted.conversionStatus)
        assertEquals(STATIC_LCM_RECIPE, converted.conversionRecipeId)
        assertTrue(converted.conversionHashes.orEmpty().contains(file.name))
        val timestamp = file.lastModified()
        file.writeText("MNN2")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(timestamp + 5_000))
        downloads.reconcile()
        val failed = store.all().single()
        assertEquals("OUTPUT_INVALID", failed.conversionErrorCode)
        assertTrue(storage.checkpoint(model.storageKey).isFile)
        downloads.startConversion(failed)
        assertFalse(output.exists())
    }

    @Test fun invalidOutputMetadataFailsOnlyItsOwnModelAndAllowsRetry() = runBlocking {
        val downloads = downloads()
        val model = downloadedFixture(downloads).copy(
            conversionStatus = ModelConversionStatus.Converted,
            conversionTemplateId = "fixture"
        )
        val id = checkNotNull(model.conversionWorkId)
        val output = storage.conversionOutput(model.storageKey, id).apply { check(mkdirs()) }
        val peer = model.copy(
            storageKey = ModelStorage.key(21, 31), modelVersionId = 21, fileId = 31,
            workId = UUID.randomUUID().toString(), conversionWorkId = UUID.randomUUID().toString(),
            conversionStatus = ModelConversionStatus.Finalizing, conversionFiles = """{"peer.mnn":4}"""
        )
        storage.prepare(peer.storageKey)
        storage.checkpoint(peer.storageKey).writeBytes(checkpoint)
        val peerId = checkNotNull(peer.conversionWorkId)
        val peerOutput = storage.conversionOutput(peer.storageKey, peerId).apply { check(mkdirs()) }
        peerOutput.resolve("peer.mnn").writeText("MNN1")
        store.insert(peer)
        conversions.states[peerId] = ScheduledState.Finished

        val invalidMetadata = listOf(
            "{" to "fixture.mnn",
            """["fixture.mnn",4]""" to "fixture.mnn",
            """{"fixture.mnn":{"size":4}}""" to "fixture.mnn",
            """{"fixture.mnn":"4"}""" to "fixture.mnn",
            """{"bad name.mnn":4}""" to "bad name.mnn",
            """{"empty.mnn":0}""" to "empty.mnn",
            """{"fixture.mnn":-1}""" to "fixture.mnn"
        )
        for ((metadata, name) in invalidMetadata) {
            val file = output.resolve(name).apply { writeText(if (name == "empty.mnn") "" else "MNN1") }
            store.update(model.copy(conversionFiles = metadata))
            downloads.reconcile()
            val failed = checkNotNull(store.find(model.storageKey))
            assertEquals(metadata, ModelConversionStatus.Failed, failed.conversionStatus)
            assertEquals(metadata, "OUTPUT_INVALID", failed.conversionErrorCode)
            assertTrue(file.delete())
        }

        assertEquals(ModelConversionStatus.Converted, store.find(peer.storageKey)?.conversionStatus)
        assertTrue(storage.checkpoint(model.storageKey).readBytes().contentEquals(checkpoint))
        downloads.startConversion(checkNotNull(store.find(model.storageKey)))
        assertEquals(ModelConversionStatus.Queued, store.find(model.storageKey)?.conversionStatus)
        assertFalse(output.exists())
    }

    @Test fun invalidOutputMetadataStopsAnInterruptedAttemptBeforeNativeExecution() = runBlocking {
        val downloads = downloads()
        val model = downloadedFixture(downloads).copy(
            conversionStatus = ModelConversionStatus.Finalizing,
            conversionTemplateId = "fixture", conversionFiles = "{"
        )
        store.update(model)
        val id = checkNotNull(model.conversionWorkId)
        downloads.runConversion(model.storageKey, id, { error("Invalid metadata reached foreground work") }, {})
        assertEquals("OUTPUT_INVALID", store.find(model.storageKey)?.conversionErrorCode)
        store.update(model)
        conversions.states[id] = ScheduledState.Finished
        downloads.reconcile()
        assertEquals("OUTPUT_INVALID", store.find(model.storageKey)?.conversionErrorCode)
        assertTrue(storage.checkpoint(model.storageKey).isFile)
    }

    @Test fun retiredStagingCleanupSkipsCurrentAndDoesNothingWhenEmpty() {
        val key = ModelStorage.key(20, 30)
        val current = storage.conversionOutput(key, UUID.randomUUID().toString())
        val retired = storage.conversionOutput(key, UUID.randomUUID().toString())
        assertEquals(ConversionErrorCode.NONE, storage.cleanupRetiredStaging(key, current))
        val currentPartial = current.resolveSibling("${current.name}.partial").apply { check(mkdirs()) }
        val retiredPartial = retired.resolveSibling("${retired.name}.partial").apply { check(mkdirs()) }
        cleanStaging = { output ->
            assertEquals(retired, output)
            assertTrue(retiredPartial.delete())
            ConversionErrorCode.NONE
        }
        assertEquals(ConversionErrorCode.NONE, storage.cleanupRetiredStaging(key, current))
        assertFalse(retiredPartial.exists())
        assertTrue(currentPartial.isDirectory)
    }

    @Test fun refusedRetiredStagingBlocksConversionAndDeletionUntilCleanupCanSucceed() = runBlocking {
        val downloads = downloads { HqqTemplate("fixture", folder.root, mapOf("fixture.mnn" to Long.MAX_VALUE)) }
        val model = downloadedFixture(downloads)
        val retired = storage.conversionOutput(model.storageKey, UUID.randomUUID().toString())
        val partial = retired.resolveSibling("${retired.name}.partial").apply { check(mkdirs()) }
        val foreign = partial.resolve("foreign.txt").apply { writeText("keep") }
        cleanStaging = { output ->
            assertEquals(retired, output)
            ConversionErrorCode.CLEANUP_FAILED
        }
        downloads.runConversion(model.storageKey, checkNotNull(model.conversionWorkId), {}, {})
        val failed = store.all().single()
        assertEquals("CLEANUP_FAILED", failed.conversionErrorCode)
        assertEquals(DownloadStatus.Downloaded, failed.downloadStatus)
        assertEquals("keep", foreign.readText())
        assertTrue(storage.checkpoint(model.storageKey).isFile)

        downloads.startConversion(failed)
        val retry = store.all().single()
        val owned = partial.resolve("fixture.mnn")
        assertTrue(foreign.renameTo(owned))
        cleanStaging = { output ->
            assertEquals(retired, output)
            assertTrue(owned.delete())
            assertTrue(partial.delete())
            ConversionErrorCode.NONE
        }
        downloads.runConversion(model.storageKey, checkNotNull(retry.conversionWorkId), {}, {})
        assertEquals("INSUFFICIENT_SPACE", store.all().single().conversionErrorCode)

        assertTrue(partial.mkdir())
        foreign.writeText("keep")
        cleanStaging = { ConversionErrorCode.CLEANUP_FAILED }
        downloads.remove(store.all().single())
        val deleting = store.all().single()
        assertEquals(DownloadStatus.Deleting, deleting.downloadStatus)
        assertEquals(DownloadError.Cleanup, deleting.errorCode)
        assertEquals("keep", foreign.readText())
        assertTrue(storage.checkpoint(model.storageKey).isFile)
        assertTrue(foreign.delete())
        assertTrue(partial.delete())
        downloads.reconcile()
        assertTrue(store.all().isEmpty())
        assertFalse(storage.checkpoint(model.storageKey).exists())
    }

    @Test fun insufficientSpaceKeepsTheSourceAndPreventsNativeConversion() = runBlocking {
        val downloads = downloads {
            HqqTemplate("fixture", folder.root, mapOf("fixture.mnn" to Long.MAX_VALUE))
        }
        val model = downloadedFixture(downloads)
        val id = checkNotNull(model.conversionWorkId)
        downloads.runConversion(model.storageKey, id, {}, { error("Native conversion started") })
        val failed = store.all().single()
        assertEquals(ModelConversionStatus.Failed, failed.conversionStatus)
        assertEquals("INSUFFICIENT_SPACE", failed.conversionErrorCode)
        assertTrue(storage.checkpoint(model.storageKey).isFile)
        assertFalse(storage.conversionOutput(model.storageKey, id).exists())
    }

    private suspend fun downloadedFixture(downloads: ModelDownloads): LibraryModel {
        val model = enqueue(downloads)
        downloads.run(model.storageKey, model.workId, {}, {})
        return store.all().single()
    }

    @Test fun unknownLengthReportsBytesAndDoesNotClaimHashVerification() = runBlocking {
        val downloads = downloads()
        body = { Buffer().write(checkpoint).asResponseBody(contentLength = -1) }
        val model = enqueue(downloads)
        val updates = mutableListOf<LibraryModel>()
        downloads.run(model.storageKey, model.workId, {}, updates::add)
        val progress = updates.first { it.downloadStatus == DownloadStatus.Downloading && it.downloadedBytes > 0 }
        assertNull(progress.totalBytes)
        assertNull(TransferProgress(progress.downloadedBytes, progress.totalBytes).fraction)
        assertEquals(0.25f, TransferProgress(25L, 100L).fraction)
        assertEquals(DownloadStatus.Downloaded, store.all().single().downloadStatus)
        assertNull(store.all().single().sha256)
    }

    @Test fun rejectsTruncationHashMismatchAndInvalidHeaderAndAllowsNewRequestAfterDeletion() = runBlocking {
        val downloads = downloads()
        body = { Buffer().write(checkpoint).asResponseBody(contentLength = checkpoint.size.toLong() + 1) }
        assertFailureAndDelete(downloads, enqueue(downloads), DownloadError.InvalidLength)

        body = { checkpoint.toResponseBody() }
        assertFailureAndDelete(downloads, enqueue(downloads, candidate(hash = "0".repeat(64))), DownloadError.InvalidHash)

        body = { safeTensor(end = 3).toResponseBody() }
        assertFailureAndDelete(downloads, enqueue(downloads), DownloadError.InvalidCheckpoint)

        body = { safeTensor(shape = "2,2").toResponseBody() }
        assertFailureAndDelete(downloads, enqueue(downloads), DownloadError.InvalidCheckpoint)

        body = { Buffer().write(checkpoint).asResponseBody(contentLength = Long.MAX_VALUE) }
        assertFailureAndDelete(downloads, enqueue(downloads), DownloadError.NoSpace)

        body = { checkpoint.toResponseBody() }
        metadataHash = digest.uppercase()
        val retry = enqueue(downloads)
        run(downloads, retry)
        assertEquals(digest, store.all().single().sha256)
        assertEquals(DownloadStatus.Downloaded, store.all().single().downloadStatus)
    }

    @Test fun classifiesAuthHttpMetadataAndNetworkErrors() = runBlocking {
        val downloads = downloads()
        token = null
        assertFailureAndDelete(downloads, enqueue(downloads), DownloadError.AuthRequired)
        token = "fake-token"
        for ((code, expected) in mapOf(
            401 to DownloadError.AuthRequired, 403 to DownloadError.Forbidden,
            404 to DownloadError.NotFound, 429 to DownloadError.RateLimited, 503 to DownloadError.Server
        )) {
            downloadCode = code
            assertFailureAndDelete(downloads, enqueue(downloads), expected)
        }
        downloadCode = 302
        selectedFile = 999
        assertFailureAndDelete(downloads, enqueue(downloads), DownloadError.NotFound)
        selectedFile = 30
        body = {
            object : Source {
                private var sent = false
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (sent) throw IOException()
                    sent = true
                    sink.write(checkpoint, 0, 16)
                    return 16
                }

                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer().asResponseBody(contentLength = -1)
        }
        assertFailureAndDelete(downloads, enqueue(downloads), DownloadError.Network)
    }

    @Test fun cancelInterruptsBlockedNetworkReadAndWaitsForHandlesBeforeRemoving() = runBlocking {
        val readBlocked = CompletableDeferred<Unit>()
        val cancelledCall = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        listener = object : EventListener() {
            override fun canceled(call: Call) {
                if (call.request().url.host == "cdn.example") cancelledCall.complete(Unit)
            }
        }
        body = {
            object : Source {
                private var sent = false
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!sent) {
                        sent = true
                        sink.write(checkpoint, 0, 16)
                        return 16
                    }
                    readBlocked.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                    throw IOException()
                }

                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer().asResponseBody(contentLength = -1)
        }
        val downloads = downloads()
        val model = enqueue(downloads)
        val worker = launch { run(downloads, model) }
        try {
            withTimeout(10_000) {
                readBlocked.await()
                downloads.remove(model)
                cancelledCall.await()
                assertEquals(DownloadStatus.Cancelling, store.all().single().downloadStatus)
                assertTrue(storage.partial(model.storageKey).isFile)
            }
        } finally {
            release.countDown()
            worker.join()
        }
        assertTrue(store.all().isEmpty())
        assertFalse(storage.partial(model.storageKey).exists())
    }

    @Test fun stoppedWorkerReturnsToQueueAndNextTransferWaitsForIt() = runBlocking {
        val downloads = downloads()
        val first = enqueue(downloads)
        val second = enqueue(downloads, candidate(fileId = 31))
        val entered = CompletableDeferred<Unit>()
        val waitForStop = CompletableDeferred<Unit>()
        val firstWorker = launch {
            downloads.run(first.storageKey, first.workId, {
                storage.partial(first.storageKey).writeText("partial")
                entered.complete(Unit)
                waitForStop.await()
            }, {})
        }
        entered.await()
        val secondWorker = launch {
            downloads.run(second.storageKey, second.workId, {
                assertFalse(storage.partial(first.storageKey).exists())
                assertEquals(DownloadStatus.Queued, store.find(first.storageKey)?.downloadStatus)
                throw DownloadFailure(DownloadError.AuthRequired)
            }, {})
        }
        firstWorker.cancelAndJoin()
        secondWorker.join()
        assertEquals(DownloadStatus.Queued, store.find(first.storageKey)?.downloadStatus)
        assertEquals(DownloadError.AuthRequired, store.find(second.storageKey)?.errorCode)
    }

    @Test fun reconcilesInterruptedWritesCompletedRenameMissingFilesAndPendingDeletion() = runBlocking {
        val downloads = downloads()
        val interrupted = enqueue(downloads)
        storage.partial(interrupted.storageKey).writeText("partial")
        store.update(interrupted.copy(downloadStatus = DownloadStatus.Downloading, downloadedBytes = 7))
        val published = enqueue(downloads, candidate(fileId = 31))
        storage.checkpoint(published.storageKey).writeBytes(checkpoint)
        store.update(published.copy(downloadStatus = DownloadStatus.Verifying, totalBytes = checkpoint.size.toLong()))
        val deleting = enqueue(downloads, candidate(fileId = 32))
        storage.checkpoint(deleting.storageKey).writeBytes(checkpoint)
        store.update(deleting.copy(downloadStatus = DownloadStatus.Deleting))
        val unscheduled = enqueue(downloads, candidate(fileId = 33))
        scheduler.states.remove(unscheduled.workId)
        val finished = enqueue(downloads, candidate(fileId = 34))
        scheduler.states[finished.workId] = ScheduledState.Finished

        downloads.reconcile()
        assertEquals(DownloadStatus.Queued, store.find(interrupted.storageKey)?.downloadStatus)
        assertFalse(storage.partial(interrupted.storageKey).exists())
        assertEquals(DownloadStatus.Downloaded, store.find(published.storageKey)?.downloadStatus)
        assertTrue(storage.checkpoint(published.storageKey).exists())
        assertNull(store.find(deleting.storageKey))
        assertFalse(storage.checkpoint(deleting.storageKey).exists())
        assertEquals(ScheduledState.Waiting, scheduler.states[unscheduled.workId])
        assertEquals(DownloadError.Interrupted, store.find(finished.storageKey)?.errorCode)

        storage.checkpoint(published.storageKey).delete()
        downloads.reconcile()
        assertEquals(DownloadError.MissingFile, store.find(published.storageKey)?.errorCode)
    }

    @Test fun unavailableStoragePreventsEnqueueAndFailedDeletionRetainsRecord() = runBlocking {
        val downloads = downloads()
        mounted = false
        assertEquals(EnqueueResult.StorageUnavailable, downloads.enqueue(candidate()))
        assertTrue(store.all().isEmpty())
        mounted = true
        val model = enqueue(downloads)
        run(downloads, model)
        mounted = false
        downloads.remove(store.all().single())
        assertEquals(DownloadError.Cleanup, store.all().single().errorCode)
        mounted = true
        downloads.remove(store.all().single())
        assertTrue(store.all().isEmpty())
        assertFalse(storage.checkpoint(model.storageKey).exists())
    }

    private suspend fun enqueue(downloads: ModelDownloads, candidate: CatalogCheckpoint = candidate()): LibraryModel {
        assertEquals(EnqueueResult.Queued, downloads.enqueue(candidate))
        return checkNotNull(store.find(ModelStorage.key(candidate.modelVersionId, candidate.fileId)))
    }

    private suspend fun run(downloads: ModelDownloads, model: LibraryModel) {
        downloads.run(model.storageKey, model.workId, {}, {})
    }

    private suspend fun assertFailureAndDelete(downloads: ModelDownloads, model: LibraryModel, expected: DownloadError) {
        run(downloads, model)
        val failed = store.all().single()
        assertEquals(DownloadStatus.Failed, failed.downloadStatus)
        assertEquals(expected, failed.errorCode)
        assertFalse(storage.checkpoint(model.storageKey).exists())
        assertFalse(storage.partial(model.storageKey).exists())
        assertEquals(EnqueueResult.Exists, downloads.enqueue(candidate()))
        downloads.remove(failed)
        assertTrue(store.all().isEmpty())
    }

    private fun downloads(template: () -> HqqTemplate = { throw IOException("No template for download tests") }): ModelDownloads {
        val client = OkHttpClient.Builder().eventListener(listener).addInterceptor(::intercept).build()
        return ModelDownloads(
            store, storage, scheduler, { token ?: throw DownloadFailure(DownloadError.AuthRequired) },
            CivitaiTransfer(client) { time.also { time += 600 } }, { _, _ -> error("Image request should fail") },
            conversions, template, shared
        )
    }

    private fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
        return when {
            request.url.encodedPath.startsWith("/api/v1/") -> {
                assertEquals("Bearer fake-token", request.header("Authorization"))
                val hash = metadataHash?.let { ""","hashes":{"SHA256":"$it"}""" }.orEmpty()
                response.code(200).body("""
                    {"id":20,"modelId":10,"files":[{"id":$selectedFile,"type":"Model","name":"fixture.safetensors",
                      "metadata":{"format":"SafeTensor"},"downloadUrl":"https://civitai.com/api/download/models/20?token=fake"$hash}]}
                """.trimIndent().toResponseBody()).build()
            }
            request.url.host == "civitai.com" -> {
                assertEquals("Bearer fake-token", request.header("Authorization"))
                assertEquals(selectedFile.toString(), request.url.queryParameter("fileId"))
                assertNull(request.url.queryParameter("token"))
                response.code(downloadCode).header("Location", "https://cdn.example/file?signature=fake")
                    .body("".toResponseBody()).build()
            }
            request.url.host == "cdn.example" -> {
                assertNull(request.header("Authorization"))
                response.code(200).body(body()).build()
            }
            else -> {
                assertNull(request.header("Authorization"))
                thumbnailRequests++
                response.code(404).body("".toResponseBody()).build()
            }
        }
    }
}

private fun candidate(fileId: Long = 30, hash: String? = null, thumbnail: String? = null) = CatalogCheckpoint(
    10, "Fixture", 20, "v1", fileId, "../../remote.safetensors", 999.0,
    "https://civitai.com/api/download/models/20", hash, thumbnail
)

private fun safeTensor(end: Int = 4, shape: String = "1"): ByteArray {
    val header = """{"weight":{"dtype":"F32","shape":[$shape],"data_offsets":[0,$end]}}""".encodeToByteArray()
    return ByteBuffer.allocate(Long.SIZE_BYTES + header.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        .putLong(header.size.toLong()).put(header).putInt(0).array()
}

private class MemoryStore : LibraryStore {
    private val models = MutableStateFlow<List<LibraryModel>>(emptyList())
    val statuses = mutableListOf<DownloadStatus>()
    override fun observe() = models.asStateFlow()
    override suspend fun all() = models.value
    override suspend fun find(key: String) = models.value.firstOrNull { it.storageKey == key }
    override suspend fun insert(model: LibraryModel) {
        check(find(model.storageKey) == null)
        models.value = models.value + model
        statuses.add(model.downloadStatus)
    }
    override suspend fun update(model: LibraryModel) {
        models.value = models.value.map { if (it.storageKey == model.storageKey) model else it }
        statuses.add(model.downloadStatus)
    }
    override suspend fun delete(model: LibraryModel) {
        models.value = models.value.filterNot { it.storageKey == model.storageKey }
    }
}

private class FakeScheduler : ModelWorkScheduler {
    val states = mutableMapOf<String, ScheduledState>()
    val enqueued = mutableListOf<String>()
    var failEnqueue = false
    override suspend fun enqueue(key: String, id: String) {
        if (failEnqueue) throw IOException("Scheduler unavailable")
        enqueued.add(id)
        states[id] = ScheduledState.Waiting
    }
    override suspend fun state(id: String) = states[id]
}
