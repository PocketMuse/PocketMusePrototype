package com.example.pocketmuseprototype

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.example.pocketmuseprototype.download.DownloadError
import com.example.pocketmuseprototype.download.Sd15LcmLora
import com.example.pocketmuseprototype.download.SharedLcmDownloads
import com.example.pocketmuseprototype.download.SharedLcmState
import com.example.pocketmuseprototype.download.SharedLcmStorage
import com.example.pocketmuseprototype.download.WorkManagerSharedLcm
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SharedLcmHistoryAndroidTest {
    @Test fun keepReplacesTerminalAttemptsAndRecoveryUsesTheMatchingResult() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PocketMuseApplication>()
        // This test intentionally uses the production work name and explicitly prunes its test database.
        assumeTrue(app.packageName.endsWith(".converterbenchmark"))
        val manager = WorkManager.getInstance(app)
        val scheduler = WorkManagerSharedLcm(manager)
        val history = manager.getWorkInfosForUniqueWorkFlow(Sd15LcmLora.WORK)
        assertTrue(history.first().none { !it.state.isFinished })
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "lcm-history").toFile()
        fun storage() = SharedLcmStorage(
            directory, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
        fun owner() = SharedLcmDownloads(storage(), scheduler)
        val downloads = owner()

        suspend fun finish(failed: Boolean = false): UUID {
            val request = OneTimeWorkRequestBuilder<SharedLcmHistoryFixtureWorker>()
                .setInputData(workDataOf("fail" to failed))
                .build()
            manager.enqueueUniqueWork(Sd15LcmLora.WORK, ExistingWorkPolicy.KEEP, request).await()
            val rows = withTimeout(15_000) {
                history.first { rows -> rows.any { it.id == request.id && it.state.isFinished } }
            }
            assertEquals(request.id, rows.single().id)
            assertEquals(if (failed) WorkInfo.State.FAILED else WorkInfo.State.SUCCEEDED, rows.single().state)
            assertEquals(request.id, scheduler.work.first()?.id)
            return request.id
        }

        suspend fun publish(id: UUID) {
            storage().apply {
                prepare().writeText("abc")
                publish()
            }
            downloads.recordResult(id, null)
            assertEquals(SharedLcmState.Ready, owner().state.first())
        }

        try {
            repeat(2) {
                val id = finish()
                publish(id)
                assertTrue(downloads.delete())
                assertEquals("Removed", storage().lastResult(id))
                assertEquals(SharedLcmState.Missing, owner().state.first())
            }
            val failure = SharedLcmState.Failed(DownloadError.Network)
            val failedId = finish(failed = true)
            assertNull(storage().lastResult(failedId))
            assertEquals(failure, owner().state.first())
            downloads.recordResult(failedId, DownloadError.Network)
            assertEquals(DownloadError.Network.name, storage().lastResult(failedId))
            publish(finish())
            assertTrue(downloads.delete())

            // Parallel work is deliberately inserted here to exercise the ambiguous-history fallback.
            val terminal = OneTimeWorkRequestBuilder<SharedLcmHistoryFixtureWorker>()
                .setInputData(workDataOf("fail" to true))
                .build()
            val pending = OneTimeWorkRequestBuilder<SharedLcmHistoryFixtureWorker>()
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            manager.beginUniqueWork(Sd15LcmLora.WORK, ExistingWorkPolicy.KEEP, listOf(terminal, pending))
                .enqueue().await()
            withTimeout(15_000) {
                history.first { rows -> rows.any { it.id == terminal.id && it.state == WorkInfo.State.FAILED } }
            }
            scheduler.enqueue()
            assertEquals(setOf(terminal.id, pending.id), history.first().map { it.id }.toSet())
            assertEquals(pending.id, scheduler.work.first()?.id)
            assertEquals(SharedLcmState.Queued, owner().state.first())
            scheduler.cancel(pending.id)
            assertEquals(2, history.first().count { it.state.isFinished })
            assertNull(scheduler.work.first())
            downloads.recordResult(terminal.id, DownloadError.Network)
            assertEquals(failure, owner().state.first())

            // KEEP after FAILED/CANCELLED removes both rows, without using their ordering.
            val latestFailed = finish(failed = true)
            downloads.recordResult(latestFailed, DownloadError.Network)
            manager.pruneWork().await()
            assertTrue(history.first().isEmpty())
            assertEquals(DownloadError.Network.name, storage().lastResult(latestFailed))
            assertEquals(failure, owner().state.first())
            publish(finish())
            assertTrue(downloads.delete())
        } finally {
            manager.cancelUniqueWork(Sd15LcmLora.WORK).await()
            directory.deleteRecursively()
        }
        Unit
    }
}

class SharedLcmHistoryFixtureWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = if (inputData.getBoolean("fail", false)) {
        Result.failure(workDataOf("error" to DownloadError.Network.name))
    } else {
        Result.success()
    }
}
