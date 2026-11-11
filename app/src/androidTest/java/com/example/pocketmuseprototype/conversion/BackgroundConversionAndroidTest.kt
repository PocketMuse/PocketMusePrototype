package com.example.pocketmuseprototype.conversion

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.MainActivity
import com.example.pocketmuseprototype.PocketMuseApplication
import com.example.pocketmuseprototype.download.DownloadStatus
import com.example.pocketmuseprototype.download.LibraryDatabase
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.ModelConversionStatus
import com.example.pocketmuseprototype.download.ModelStorage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BackgroundConversionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as PocketMuseApplication

    @Test fun migratesVersionOneWithDownloadStateAndIdentityIntact() = runBlocking {
        val name = "library-v1-${UUID.randomUUID()}"
        val file = app.getDatabasePath(name)
        file.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("""
                    CREATE TABLE models (
                        storageKey TEXT NOT NULL PRIMARY KEY, modelId INTEGER NOT NULL,
                        modelVersionId INTEGER NOT NULL, fileId INTEGER NOT NULL, modelName TEXT NOT NULL,
                        versionName TEXT NOT NULL, fileName TEXT NOT NULL, fileSize INTEGER NOT NULL,
                        sha256 TEXT, thumbnailUrl TEXT, workId TEXT NOT NULL, downloadStatus TEXT NOT NULL,
                        downloadedBytes INTEGER NOT NULL, totalBytes INTEGER, errorCode TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO models VALUES
                        ('20_30', 10, 20, 30, 'Fixture', 'v1', 'fixture.safetensors', 40, 'sha', NULL,
                            'old-completed-id', 'Downloaded', 40, 40, NULL),
                        ('21_31', 10, 21, 31, 'Waiting', 'v1', 'waiting.safetensors', 0, NULL, NULL,
                            'old-queued-id', 'Queued', 0, NULL, NULL)
                """.trimIndent())
                db.version = 1
            }
            withDatabase(name) { db ->
                    val (downloaded, queued) = db.models().all()
                    assertEquals("old-completed-id", downloaded.workId)
                    assertEquals("old-queued-id", queued.workId)
                    assertEquals(DownloadStatus.Downloaded, downloaded.downloadStatus)
                    assertEquals(DownloadStatus.Queued, queued.downloadStatus)
                    assertEquals(40L, downloaded.fileSize)
                    assertEquals("sha", downloaded.sha256)
                    assertEquals(ModelConversionStatus.NotStarted, downloaded.conversionStatus)
                    assertEquals(ModelConversionStatus.NotStarted, queued.conversionStatus)
                    assertNull(downloaded.conversionWorkId)
                }
        } finally {
            app.deleteDatabase(name)
        }
        Unit
    }

    @Test fun bundledTemplateVerifiesReusesAndRepairsThePublishedCopy() {
        val files = File(app.cacheDir, "template-${UUID.randomUUID()}")
        try {
            val context = object : ContextWrapper(app) {
                override fun getFilesDir(): File = files
            }
            val loader = BundledHqqTemplate(context)
            val first = loader.load()
            assertTrue(first.directory.toPath().startsWith(files.toPath()))
            assertEquals(setOf("text_encoder.mnn", "unet.mnn", "vae_decoder.mnn"), first.files.keys)
            val graph = first.directory.resolve("graph.bin")
            val hash = graph.sha256()
            val time = graph.lastModified()
            assertEquals(first, loader.load())
            assertEquals(time, graph.lastModified())
            graph.writeText("damaged")
            assertEquals(first, loader.load())
            assertEquals(hash, graph.sha256())
            assertFalse(first.directory.resolveSibling("${first.id}.partial").exists())
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun recoveredDownloadStartsTheRealWorkerAndReportsTensorMismatch() = runBlocking {
        val key = "90201_90202"
        val storage = ModelStorage { app.getExternalFilesDir(null) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val header = """{"weight":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}""".encodeToByteArray()
            storage.prepare(key)
            val checkpoint = storage.checkpoint(key)
            checkpoint.writeBytes(ByteBuffer.allocate(12 + header.size).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(header.size.toLong()).put(header).putInt(0).array())
            try {
                seedPublishedDownload(key, checkpoint)
                val failed = awaitModel(key) { it.conversionStatus == ModelConversionStatus.Failed }
                assertEquals(DownloadStatus.Downloaded, failed.downloadStatus)
                assertEquals("TENSOR_MISMATCH", failed.conversionErrorCode)
                assertTrue(checkpoint.isFile)
                app.downloads.remove(failed)
                assertFalse(checkpoint.exists())
            } finally {
                removeFixture(key)
            }
        }
    }

    @Test fun realCheckpointCancelsThenCompletesInBackground() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackgroundConversion") == "true")
        val key = "90301_90302"
        val storage = ModelStorage { app.getExternalFilesDir(null) }
        val checkpoint = storage.checkpoint(key)
        assertEquals(4_265_146_304L, checkpoint.length())
        ActivityScenario.launch(MainActivity::class.java).use {
            seedPublishedDownload(key, checkpoint)
            val running = awaitModel(key) { it.conversionStatus == ModelConversionStatus.Converting }
            app.downloads.cancelConversion(running)
            val cancelled = awaitModel(key) { it.conversionStatus == ModelConversionStatus.Cancelled }
            val oldOutput = storage.conversionOutput(key, checkNotNull(cancelled.conversionWorkId))
            assertFalse(oldOutput.exists())
            assertFalse(oldOutput.resolveSibling("${oldOutput.name}.partial").exists())
            assertTrue(checkpoint.isFile)
            val start = SystemClock.elapsedRealtime()
            app.downloads.startConversion(cancelled)
            awaitModel(key) { it.conversionStatus == ModelConversionStatus.Converting }
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
            ).use { it.readBytes() }
            val completed = awaitModel(key) { it.conversionStatus == ModelConversionStatus.Converted }
            val seconds = (SystemClock.elapsedRealtime() - start) / 1_000.0
            Log.i("BackgroundConversionTest", "Retry through Library registration took ${seconds}s")
            val output = storage.conversionOutput(key, checkNotNull(completed.conversionWorkId))
            val sizes = Json.decodeFromString<Map<String, Long>>(checkNotNull(completed.conversionFiles))
            assertEquals(sizes, output.listFiles()?.associate { it.name to it.length() })
            assertEquals(4_265_146_304L, checkpoint.length())
        }
    }

    private suspend fun seedPublishedDownload(key: String, checkpoint: File) {
        val (version, file) = key.split('_').map(String::toLong)
        val model = LibraryModel(key, 1, version, file, "Conversion fixture", "v1", checkpoint.name,
            checkpoint.length(), null, null, UUID.randomUUID().toString(),
            DownloadStatus.Verifying, checkpoint.length(), checkpoint.length())
        withDatabase { it.models().insert(model) }
        app.downloads.reconcile()
    }

    private suspend fun awaitModel(key: String, predicate: (LibraryModel) -> Boolean) =
        withTimeout(600_000) {
            app.downloads.models.mapNotNull { rows -> rows.firstOrNull { it.model.storageKey == key }?.model }
                .first { predicate(it) || it.conversionStatus == ModelConversionStatus.Failed }
                .also { check(predicate(it)) { "Conversion failed: ${it.conversionErrorCode}" } }
        }

    private suspend fun removeFixture(key: String) {
        val model = withDatabase { it.models().find(key) } ?: return
        app.downloads.remove(model)
    }

    private suspend fun <T> withDatabase(
        name: String = LibraryDatabase.NAME,
        block: suspend (LibraryDatabase) -> T
    ): T {
        val db = Room.databaseBuilder(app, LibraryDatabase::class.java, name)
            .addMigrations(LibraryDatabase.MIGRATION_1_2).build()
        return try {
            block(db)
        } finally {
            db.close()
        }
    }
}
