package com.example.pocketmuseprototype.download

import androidx.work.WorkInfo
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedLcmDownloadsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scheduler = TestScheduler()
    private val directory: File get() = File(folder.root, "shared-assets/${Sd15LcmLora.ID}")
    private val finalFile: File get() = File(directory, Sd15LcmLora.FILE_NAME)
    private val partial: File get() = File(directory, "${Sd15LcmLora.FILE_NAME}.part")
    private var body: () -> ResponseBody = { "abc".toResponseBody() }
    private var redirect = "https://cdn.example/fixture"
    private var requests = 0

    private fun downloads(): SharedLcmDownloads {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("Authorization"))
            assertEquals("identity", request.header("Accept-Encoding"))
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            if (request.url.host == "huggingface.co") {
                assertEquals(Sd15LcmLora.url, request.url)
                response.code(302).header("Location", redirect).body("".toResponseBody()).build()
            } else {
                requests++
                response.code(200).body(body()).build()
            }
        }.build()
        return SharedLcmDownloads(
            SharedLcmStorage(folder.root, SIZE, HASH), scheduler, SharedLcmTransfer(http, SIZE, HASH)
        )
    }

    @Test fun simultaneousRequestsShareOneJobAndVerifiedFileSurvivesReopen() = runBlocking {
        val downloads = downloads()
        assertEquals(SharedLcmState.Missing, downloads.state.first())
        val firstModel = async { downloads.ensure() }
        val secondModel = async { downloads.ensure() }
        assertEquals(setOf(true, false), setOf(firstModel.await(), secondModel.await()))
        assertEquals(1, scheduler.enqueued)
        assertEquals(SharedLcmState.Queued, downloads.state.first())
        scheduler.update(WorkInfo.State.RUNNING)
        downloads.run({}, { state ->
            assertFalse(finalFile.exists())
            scheduler.work.value = checkNotNull(scheduler.work.value).copy(
                bytes = (state as? SharedLcmState.Downloading)?.bytes,
                verifying = state == SharedLcmState.Verifying
            )
            assertEquals(state, downloads.state.first())
        })
        assertFalse(partial.exists())
        assertEquals("abc", downloads.withVerifiedFile { it.readText() })
        // Simulate process death after rename but before WorkManager commits success.
        assertEquals(SharedLcmState.Ready, downloads().state.first())
        scheduler.update(WorkInfo.State.SUCCEEDED)
        assertFalse(downloads().ensure())
        assertEquals(1, requests)
        assertEquals(1, scheduler.enqueued)
    }

    @Test fun rejectsWrongLengthsHashAndInsecureRedirectAndCanRetry() = runBlocking {
        val downloads = downloads()
        val cases = listOf(
            DownloadError.InvalidLength to { "ab".toResponseBody() },
            DownloadError.InvalidLength to { Buffer().writeUtf8("abcd").asResponseBody(contentLength = -1) },
            DownloadError.InvalidHash to { "xyz".toResponseBody() }
        )
        for ((error, response) in cases) {
            body = response
            assertTrue(downloads.ensure())
            scheduler.update(WorkInfo.State.RUNNING)
            assertEquals(error, (runCatching { downloads.run({}, {}) }.exceptionOrNull() as DownloadFailure).code)
            scheduler.update(WorkInfo.State.FAILED, error)
            assertEquals(SharedLcmState.Failed(error), downloads.state.first())
            assertNull(downloads.withVerifiedFile { it })
            assertFalse(finalFile.exists())
            assertFalse(partial.exists())
        }
        redirect = "http://cdn.example/fixture"
        assertTrue(downloads.ensure())
        val error = runCatching { downloads.run({}, {}) }.exceptionOrNull() as DownloadFailure
        assertEquals(DownloadError.InvalidMetadata, error.code)
        assertEquals(3, requests)
    }

    @Test fun interruptedTransferDiscardsPartialAndRestartsFromByteZero() = runBlocking {
        val downloads = downloads()
        assertTrue(downloads.ensure())
        scheduler.update(WorkInfo.State.RUNNING)
        val verifying = CompletableDeferred<Unit>()
        val worker = launch {
            downloads.run({}, { state ->
                if (state == SharedLcmState.Verifying) {
                    verifying.complete(Unit)
                    awaitCancellation()
                }
            })
        }
        withTimeout(5_000) { verifying.await() }
        assertEquals("abc", partial.readText())
        assertFalse(finalFile.exists())
        worker.cancelAndJoin()
        assertFalse(partial.exists())
        // A process kill can leave an old partial without executing the finally block.
        partial.writeText("stale")
        scheduler.update(WorkInfo.State.ENQUEUED)
        val reopened = downloads()
        assertEquals(SharedLcmState.Queued, reopened.state.first())
        assertNull(reopened.withVerifiedFile { it })
        body = { throw IOException("fixture connection lost") }
        val error = runCatching { reopened.run({}, {}) }.exceptionOrNull() as DownloadFailure
        assertEquals(DownloadError.Network, error.code)
        body = { "abc".toResponseBody() }
        reopened.run({}, {})
        assertEquals("abc", finalFile.readText())
        assertFalse(partial.exists())
    }

    @Test fun missingAndTamperedFinalsRecoverAndExplicitDeletionSurvivesReopen() = runBlocking {
        val downloads = downloads()
        assertTrue(downloads.ensure())
        scheduler.update(WorkInfo.State.SUCCEEDED)
        assertEquals(SharedLcmState.Failed(DownloadError.MissingFile), downloads.state.first())
        directory.mkdirs()
        finalFile.writeText("xyz")
        assertEquals(SharedLcmState.Failed(DownloadError.InvalidHash), downloads().state.first())
        assertTrue(downloads.ensure())
        downloads.run({}, {})
        scheduler.update(WorkInfo.State.SUCCEEDED)
        assertEquals(SharedLcmState.Ready, downloads.state.first())
        val before = Files.getLastModifiedTime(finalFile.toPath())
        finalFile.writeText("xyz")
        Files.setLastModifiedTime(finalFile.toPath(), FileTime.fromMillis(before.toMillis() + 2_000))
        assertEquals(SharedLcmState.Failed(DownloadError.InvalidHash), downloads.state.first())
        assertTrue(downloads.ensure())
        downloads.run({}, {})
        scheduler.update(WorkInfo.State.SUCCEEDED)
        assertTrue(downloads.delete())
        assertEquals(SharedLcmState.Missing, downloads().state.first())
        assertTrue(downloads().ensure())
    }

    @Test fun workerOutcomeSurvivesPruningAndDeletingPublishedFileCancelsUnfinishedWork() = runBlocking {
        val downloads = downloads()
        assertTrue(downloads.ensure())
        downloads.recordResult(checkNotNull(scheduler.work.value).id, DownloadError.Network)
        scheduler.work.value = null
        assertEquals(SharedLcmState.Failed(DownloadError.Network), downloads().state.first())
        assertTrue(downloads.ensure())
        scheduler.update(WorkInfo.State.CANCELLED)
        downloads().reconcile()
        scheduler.work.value = null
        assertEquals(SharedLcmState.Missing, downloads().state.first())
        assertTrue(downloads.ensure())
        downloads.run({}, {})
        assertEquals(SharedLcmState.Ready, downloads.state.first())
        assertTrue(downloads.delete())
        assertEquals(WorkInfo.State.CANCELLED, scheduler.work.value?.state)
        assertTrue(downloads.ensure())
    }

    @Test fun cancellationIsPersistedBeforeWorkManagerStopsTheWorker() = runBlocking {
        val downloads = downloads()
        assertTrue(downloads.ensure())
        downloads.recordResult(checkNotNull(scheduler.work.value).id, DownloadError.Network)
        scheduler.update(WorkInfo.State.FAILED, DownloadError.Network)
        assertTrue(downloads.ensure())
        scheduler.beforeCancel = { assertTrue(downloads().wasRemoved(it)) }
        assertTrue(downloads.cancel())
        scheduler.work.value = null
        assertEquals(SharedLcmState.Missing, downloads().state.first())
    }

    @Test fun deletingWaitsForVerifiedFileReaderAndOnlyRemovesSharedAsset() = runBlocking {
        val downloads = downloads()
        assertTrue(downloads.ensure())
        downloads.run({}, {})
        scheduler.update(WorkInfo.State.SUCCEEDED)
        val checkpoint = File(folder.root, "models/1_2/source/checkpoint.safetensors")
        checkpoint.parentFile!!.mkdirs()
        checkpoint.writeText("checkpoint")
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reader = async {
            downloads.withVerifiedFile {
                reading.complete(Unit)
                release.await()
                it.readText()
            }
        }
        withTimeout(5_000) { reading.await() }
        val deleting = async { downloads.delete() }
        assertEquals(SharedLcmState.Ready, downloads.state.first())
        release.complete(Unit)
        assertEquals("abc", reader.await())
        assertTrue(deleting.await())
        assertEquals("checkpoint", checkpoint.readText())
        assertFalse(finalFile.exists())
    }

    private class TestScheduler : SharedLcmScheduler {
        override val work = MutableStateFlow<SharedLcmWork?>(null)
        var beforeCancel: suspend (UUID) -> Unit = {}
        var enqueued = 0
            private set

        override suspend fun enqueue() {
            enqueued++
            work.value = SharedLcmWork(UUID.randomUUID(), WorkInfo.State.ENQUEUED)
        }

        override suspend fun cancel(id: UUID) {
            assertEquals(work.value?.id, id)
            beforeCancel(id)
            update(WorkInfo.State.CANCELLED)
        }

        fun update(state: WorkInfo.State, error: DownloadError? = null) {
            work.value = checkNotNull(work.value).copy(state = state, error = error)
        }
    }

    private companion object {
        const val SIZE = 3L
        const val HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
