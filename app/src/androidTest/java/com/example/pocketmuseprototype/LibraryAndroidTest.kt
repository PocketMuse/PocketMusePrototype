package com.example.pocketmuseprototype

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.pocketmuseprototype.download.DownloadNotifications
import com.example.pocketmuseprototype.download.DownloadStatus
import com.example.pocketmuseprototype.download.LibraryDatabase
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.ModelStorage
import com.example.pocketmuseprototype.download.writeWebpThumbnail
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun librarySelectionSurvivesActivityRecreation() {
        val library = compose.onNodeWithText("Library")
        library.performClick()
        compose.activityRule.scenario.recreate()
        library.assertIsSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.library_downloads)).assertIsDisplayed()
    }

    @Test fun drawerSavesHqqIterationsAcrossActivityRecreation() = runBlocking<Unit> {
        val activity = compose.activity
        val settings = (activity.application as PocketMuseApplication).conversionSettings
        val initial = settings.hqqIterations.first()
        val menu = activity.getString(R.string.open_menu)
        val label = activity.getString(R.string.conversion_hqq_iterations)
        try {
            compose.onNodeWithContentDescription(menu).performClick()
            val slider = compose.onNodeWithContentDescription(label)
            if (initial != 5) {
                slider.performSemanticsAction(SemanticsActions.SetProgress) { check(it(5f)) }
            }
            withTimeout(5_000) { settings.hqqIterations.first { it == 5 } }
            slider.performSemanticsAction(SemanticsActions.SetProgress) { check(it(15f)) }
            withTimeout(5_000) { settings.hqqIterations.first { it == 15 } }
            compose.activityRule.scenario.recreate()
            val drawerTitle = compose.onNodeWithText(activity.getString(R.string.conversion_settings_title))
            if (!drawerTitle.isDisplayed()) compose.onNodeWithContentDescription(menu).performClick()
            slider.assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, activity.getString(R.string.conversion_hqq_count, 15)
            ))
        } finally {
            settings.setHqqIterations(initial)
        }
    }
}

class LibraryStorageAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val model = LibraryModel("20_30", 10, 20, 30, "Fixture", "v1", "fixture.safetensors",
        4, null, null, "fixture", DownloadStatus.Downloaded, 4, 4)

    @Test fun roomPersistsModelAndAppSpecificStorageRemovesOnlyItsFiles() = runBlocking(Dispatchers.IO) {
        val name = "library-test-${UUID.randomUUID()}"
        val base = File(checkNotNull(context.getExternalFilesDir(null)), name)
        val storage = ModelStorage(noBackupFiles = { File(context.noBackupFilesDir, name) }) { base }
        val other = "21_31"
        fun database() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            storage.prepare(model.storageKey)
            storage.prepare(other)
            storage.partial(model.storageKey).writeText("test")
            storage.publishCheckpoint(model.storageKey)
            storage.partial(other).writeText("other")
            val image = ByteArrayOutputStream().use { stream ->
                val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
                stream.toByteArray()
            }
            writeWebpThumbnail(image, storage.thumbnailPartial(model.storageKey))
            storage.publishThumbnail(model.storageKey)
            val thumbnail = BitmapFactory.decodeFile(storage.thumbnail(model.storageKey).absolutePath)
            assertEquals(320, thumbnail.width)
            thumbnail.recycle()

            val initial = database()
            try {
                initial.models().insert(model)
            } finally {
                initial.close()
            }
            val db = database()
            try {
                assertEquals(listOf(model), db.models().observe().first())
                assertTrue(storage.checkpoint(model.storageKey).isFile)
                storage.delete(model.storageKey)
                db.models().delete(model)
                assertTrue(db.models().all().isEmpty())
            } finally {
                db.close()
            }
            assertFalse(storage.thumbnail(model.storageKey).exists())
            assertFalse(storage.checkpoint(model.storageKey).exists())
            assertEquals("other", storage.partial(other).readText())
        } finally {
            context.deleteDatabase(name)
            base.deleteRecursively()
        }
        Unit
    }

    @Test fun workerUsesDeclaredDataSyncForegroundServiceAndLowImportanceChannel() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, "androidx.work.impl.foreground.SystemForegroundService"), 0
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
        val foreground = DownloadNotifications(context, 42).foreground(model.copy(downloadStatus = DownloadStatus.Queued))
        assertEquals(42, foreground.notificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foreground.foregroundServiceType)
        assertTrue(foreground.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(foreground.notification.contentIntent)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(foreground.notification.channelId)
        if (!channel.hasUserSetImportance()) {
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        }
    }
}
