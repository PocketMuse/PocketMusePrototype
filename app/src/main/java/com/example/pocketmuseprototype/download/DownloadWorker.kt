package com.example.pocketmuseprototype.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.example.pocketmuseprototype.MainActivity
import com.example.pocketmuseprototype.PocketMuseApplication
import com.example.pocketmuseprototype.R
import java.util.UUID
import kotlinx.coroutines.flow.first

internal class WorkManagerDownloads(private val manager: WorkManager) : DownloadScheduler {
    val changes = manager.getWorkInfosForUniqueWorkFlow(QUEUE)

    override suspend fun enqueue(key: String, id: String) {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(UUID.fromString(id))
            .setInputData(workDataOf(STORAGE_KEY to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, work).await()
    }

    override suspend fun state(id: String): ScheduledState? =
        manager.getWorkInfoByIdFlow(UUID.fromString(id)).first()?.state?.let {
            when {
                it.isFinished -> ScheduledState.Finished
                it == WorkInfo.State.RUNNING -> ScheduledState.Running
                else -> ScheduledState.Waiting
            }
        }

    private companion object {
        const val QUEUE = "model-downloads"
    }
}

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val downloads get() = (applicationContext as PocketMuseApplication).downloads
    private val notifications = DownloadNotifications(context, id.hashCode())

    override suspend fun doWork(): Result {
        val key = inputData.getString(STORAGE_KEY) ?: return Result.success()
        downloads.run(key, id.toString(), foreground = { model ->
            try {
                setForeground(notifications.foreground(model))
            } catch (_: IllegalStateException) {
                throw DownloadFailure(DownloadError.Interrupted)
            } catch (_: SecurityException) {
                throw DownloadFailure(DownloadError.Interrupted)
            }
        }, notification = notifications::update)
        // Domain errors live in Room. Completing this work releases the next request.
        return Result.success()
    }
}

internal class DownloadNotifications(private val context: Context, private val notificationId: Int) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun foreground(model: LibraryModel): ForegroundInfo {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW
        ))
        return ForegroundInfo(notificationId, notification(model), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    fun update(model: LibraryModel) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (manager.areNotificationsEnabled()) manager.notify(notificationId, notification(model))
    }

    private fun notification(model: LibraryModel): Notification {
        val openLibrary = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.OPEN_LIBRARY, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, 0, openLibrary, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val verifying = model.downloadStatus == DownloadStatus.Verifying
        val progress = TransferProgress(model.downloadedBytes, model.totalBytes).fraction
        return NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(model.modelName)
            .setContentText(when {
                verifying -> context.getString(R.string.library_verifying)
                model.downloadStatus == DownloadStatus.Queued -> context.getString(R.string.library_preparing)
                else -> context.getString(R.string.library_bytes, Formatter.formatShortFileSize(context, model.downloadedBytes))
            })
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), verifying || progress == null)
            .build()
    }

    companion object {
        const val CHANNEL = "model-downloads"
    }
}

private const val STORAGE_KEY = "storageKey"
