package com.example.pocketmuseprototype.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.example.pocketmuseprototype.PocketMuseApplication
import java.util.UUID

internal class WorkManagerConversions(private val manager: WorkManager) : ModelWorkScheduler {
    val changes = manager.getWorkInfosForUniqueWorkFlow(QUEUE)

    override suspend fun enqueue(key: String, id: String) {
        val work = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setId(UUID.fromString(id))
            .setInputData(workDataOf(STORAGE_KEY to key))
            .build()
        manager.enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, work).await()
    }

    override suspend fun state(id: String) = manager.scheduledState(id)

    private companion object {
        const val QUEUE = "model-conversions"
    }
}

class ConversionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(STORAGE_KEY) ?: return Result.success()
        val downloads = (applicationContext as PocketMuseApplication).downloads
        val notifications = DownloadNotifications(applicationContext, id.hashCode())
        downloads.runConversion(key, id.toString(), foreground = { model ->
            try {
                setForeground(notifications.foreground(model))
            } catch (_: IllegalStateException) {
                throw ConversionFailure("INTERRUPTED")
            } catch (_: SecurityException) {
                throw ConversionFailure("INTERRUPTED")
            }
        }, notification = notifications::update)
        return Result.success()
    }
}
