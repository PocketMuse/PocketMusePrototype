package com.example.pocketmuseprototype.download

import android.content.Context
import android.text.format.Formatter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.example.pocketmuseprototype.PocketMuseApplication
import com.example.pocketmuseprototype.R
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.map

private const val BYTES = "bytes"
private const val VERIFYING = "verifying"
private const val ERROR = "error"

internal class WorkManagerSharedLcm(private val manager: WorkManager) : SharedLcmScheduler {
    override val work = manager.getWorkInfosForUniqueWorkFlow(Sd15LcmLora.WORK).map { infos ->
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.singleOrNull()
        info?.let {
            SharedLcmWork(
                it.id, it.state, it.progress.getLong(BYTES, -1L).takeIf { bytes -> bytes >= 0 },
                it.progress.getBoolean(VERIFYING, false),
                DownloadError.entries.firstOrNull { error -> error.name == it.outputData.getString(ERROR) }
            )
        }
    }

    override suspend fun enqueue() {
        val request = OneTimeWorkRequestBuilder<SharedLcmWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniqueWork(Sd15LcmLora.WORK, ExistingWorkPolicy.KEEP, request).await()
    }

    override suspend fun cancel(id: UUID) {
        manager.cancelWorkById(id).await()
    }
}

class SharedLcmWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val downloads get() = (applicationContext as PocketMuseApplication).sharedLcm
    private val notifications = DownloadNotifications(context, id.hashCode())
    private val title = context.getString(R.string.library_lcm_name)

    override suspend fun doWork(): Result = try {
        if (download()) Result.success() else Result.failure()
    } catch (failure: DownloadFailure) {
        if (failure.code == DownloadError.Network && runAttemptCount < 2) Result.retry()
        else failed(failure.code)
    } catch (_: IOException) {
        failed(DownloadError.FileWrite)
    }

    private suspend fun download(): Boolean {
        val ready = downloads.run(
            id,
            foreground = {
                try {
                    setForeground(notifications.foreground(title, applicationContext.getString(R.string.library_preparing)))
                } catch (_: IllegalStateException) {
                    throw DownloadFailure(DownloadError.Interrupted)
                } catch (_: SecurityException) {
                    throw DownloadFailure(DownloadError.Interrupted)
                }
            },
            progress = { state ->
                val bytes = (state as? SharedLcmState.Downloading)?.bytes
                val verifying = state == SharedLcmState.Verifying
                setProgress(workDataOf(BYTES to (bytes ?: Sd15LcmLora.SIZE), VERIFYING to verifying))
                val message = if (bytes == null) applicationContext.getString(R.string.library_verifying)
                else applicationContext.getString(R.string.library_bytes, Formatter.formatShortFileSize(applicationContext, bytes))
                val percent = bytes?.let { (it * 100 / Sd15LcmLora.SIZE).toInt() }
                notifications.update(title, message, percent)
            }
        )
        if (ready) downloads.recordResult(id, null)
        return ready
    }

    private suspend fun failed(error: DownloadError): Result {
        try {
            downloads.recordResult(id, error)
        } catch (_: IOException) {
            // WorkManager can still report the failure if the filesystem is unavailable.
        }
        return Result.failure(workDataOf(ERROR to error.name))
    }
}
