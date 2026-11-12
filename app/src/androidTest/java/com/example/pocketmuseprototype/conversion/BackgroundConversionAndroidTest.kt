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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.pocketmuseprototype.MainActivity
import com.example.pocketmuseprototype.PocketMuseApplication
import com.example.pocketmuseprototype.download.DownloadStatus
import com.example.pocketmuseprototype.download.LEGACY_HQQ_RECIPE
import com.example.pocketmuseprototype.download.LibraryDatabase
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.ModelConversionStatus
import com.example.pocketmuseprototype.download.ModelStorage
import com.example.pocketmuseprototype.download.STATIC_LCM_RECIPE
import com.example.pocketmuseprototype.download.Sd15LcmLora
import com.example.pocketmuseprototype.download.SharedLcmState
import com.example.pocketmuseprototype.download.SharedLcmStorage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun migratesActualVersionTwoSchemaWithoutChangingExistingAttempts() = runBlocking {
        val name = "library-v2-${UUID.randomUUID()}"
        val file = app.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val schema = instrumentation.context.assets
            .open("${LibraryDatabase::class.java.name}/2.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
            }
        val states = listOf(ModelConversionStatus.NotStarted, ModelConversionStatus.Queued,
            ModelConversionStatus.Converting, ModelConversionStatus.Converted,
            ModelConversionStatus.Failed, ModelConversionStatus.Cancelled)
        val rows = states.mapIndexed { index, state ->
            val version = (index + 1).toLong()
            val attempt = index.takeIf { it > 0 }?.let { UUID.randomUUID().toString() }
            LibraryModel("${version}_31", 10, version, 31, "Fixture", "v1", "fixture.safetensors",
                40, "source-sha", "https://example.test/image", "download-$index",
                DownloadStatus.Downloaded, 40, 40, null,
                state, attempt, index, if (state == ModelConversionStatus.Failed) "INTERRUPTED" else null,
                attempt?.let { "sd15-hqq-b128-manual-v1" }, attempt?.let { """{"unet.mnn":40}""" },
                if (state == ModelConversionStatus.Converted) """{"unet.mnn":"prior-hash"}""" else null)
        }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                val entity = schema.getValue("entities").jsonArray.single().jsonObject
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", "models"))
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                for (row in rows) {
                    db.execSQL("INSERT INTO models VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
                        row.storageKey, row.modelId, row.modelVersionId, row.fileId, row.modelName, row.versionName,
                        row.fileName, row.fileSize, row.sha256, row.thumbnailUrl, row.workId, row.downloadStatus.name,
                        row.downloadedBytes, row.totalBytes, row.errorCode, row.conversionStatus.name,
                        row.conversionWorkId, row.conversionProgress, row.conversionErrorCode,
                        row.conversionTemplateId, row.conversionFiles, row.conversionHashes
                    ))
                }
                db.version = 2
            }
            withDatabase(name) { db ->
                assertEquals(rows.map { row -> row.copy(
                    conversionRecipeId = row.conversionWorkId?.let { LEGACY_HQQ_RECIPE }
                ) }, db.models().all())
            }
        } finally {
            app.deleteDatabase(name)
        }
        Unit
    }

    @Test fun legacyAttemptStartsTheRealWorkerWithoutLoraAndReportsTensorMismatch() = runBlocking {
        val key = "90201_90202"
        ActivityScenario.launch(MainActivity::class.java).use {
            val checkpoint = writeIncompleteCheckpoint(key)
            try {
                val model = publishedDownload(key, checkpoint)
                withDatabase {
                    it.models().insert(model.copy(
                        downloadStatus = DownloadStatus.Downloaded,
                        conversionStatus = ModelConversionStatus.Queued,
                        conversionWorkId = UUID.randomUUID().toString(),
                        conversionRecipeId = LEGACY_HQQ_RECIPE
                    ))
                }
                app.downloads.reconcile()
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

    @Test fun staticNativeFailureReleasesTheOfficialLora() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifiedStaticFixture") == "true")
        check(app.packageName.endsWith(".converterbenchmark"))
        val key = "90401_90402"
        assertEquals(SharedLcmState.Ready, app.sharedLcm.state.first())
        ActivityScenario.launch(MainActivity::class.java).use {
            val checkpoint = writeIncompleteCheckpoint(key)
            try {
                seedPublishedDownload(key, checkpoint)
                val failed = awaitModel(key) { it.conversionStatus == ModelConversionStatus.Failed }
                assertEquals(STATIC_LCM_RECIPE, failed.conversionRecipeId)
                assertEquals("TENSOR_MISMATCH", failed.conversionErrorCode)
                assertTrue(checkpoint.isFile)
                app.downloads.remove(failed)
                assertFalse(checkpoint.exists())
                val size = withTimeout(10_000) { app.sharedLcm.withVerifiedFile { it.length() } }
                assertEquals(Sd15LcmLora.SIZE, size)
            } finally {
                removeFixture(key)
            }
        }
    }

    private fun writeIncompleteCheckpoint(key: String): File {
        val storage = ModelStorage { app.getExternalFilesDir(null) }
        val header = """{"weight":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}""".encodeToByteArray()
        storage.prepare(key)
        return storage.checkpoint(key).apply {
            writeBytes(ByteBuffer.allocate(12 + header.size).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(header.size.toLong()).put(header).putInt(0).array())
        }
    }

    @Test fun realCheckpointCancelsThenCompletesInBackground() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackgroundConversion") == "true")
        check(app.packageName.endsWith(".converterbenchmark"))
        val key = "90301_90302"
        val storage = ModelStorage { app.getExternalFilesDir(null) }
        val checkpoint = storage.checkpoint(key)
        assertEquals(4_265_146_304L, checkpoint.length())
        assertEquals(CHECKPOINT_HASH, checkpoint.sha256())
        val loraInput = File(app.getExternalFilesDir(null), "converter-smoke/lcm-lora.safetensors")
        assertEquals(Sd15LcmLora.SIZE, loraInput.length())
        assertEquals(Sd15LcmLora.SHA256, loraInput.sha256())
        val shared = SharedLcmStorage(app.noBackupFilesDir)
        assertEquals(SharedLcmState.Missing, app.sharedLcm.state.first())
        fun publishLora() {
            loraInput.copyTo(shared.prepare(), overwrite = true)
            shared.publish()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            seedPublishedDownload(key, checkpoint)
            val waiting = awaitModel(key) { it.conversionStatus == ModelConversionStatus.WaitingForLcm }
            val manager = WorkManager.getInstance(app)
            val waitingId = UUID.fromString(checkNotNull(waiting.conversionWorkId))
            assertNull(manager.getWorkInfoByIdFlow(waitingId).first())
            publishLora()
            app.sharedLcm.reconcile()
            val running = awaitModel(key) { it.conversionStatus == ModelConversionStatus.Converting }
            assertEquals(waiting.conversionWorkId, running.conversionWorkId)
            val deleteLora = async { app.sharedLcm.delete() }
            assertTrue(app.sharedLcm.isReady())
            app.downloads.cancelConversion(running)
            val cancelled = awaitModel(key) { it.conversionStatus == ModelConversionStatus.Cancelled }
            assertTrue(withTimeout(60_000) { deleteLora.await() })
            assertEquals(SharedLcmState.Missing, app.sharedLcm.state.first())
            val oldOutput = storage.conversionOutput(key, checkNotNull(cancelled.conversionWorkId))
            assertFalse(oldOutput.exists())
            assertFalse(oldOutput.resolveSibling("${oldOutput.name}.partial").exists())
            assertTrue(checkpoint.isFile)
            assertTrue(app.downloads.startConversion(cancelled))
            awaitModel(key) { it.conversionStatus == ModelConversionStatus.WaitingForLcm }
            publishLora()
            val start = SystemClock.elapsedRealtime()
            app.sharedLcm.reconcile()
            awaitModel(key) { it.conversionStatus == ModelConversionStatus.Converting }
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
            ).use { it.readBytes() }
            val completed = awaitModel(key) { it.conversionStatus == ModelConversionStatus.Converted }
            val seconds = (SystemClock.elapsedRealtime() - start) / 1_000.0
            Log.i("BackgroundConversionTest", "Retry through Library registration took ${seconds}s")
            assertEquals(STATIC_LCM_RECIPE, completed.conversionRecipeId)
            val id = checkNotNull(completed.conversionWorkId)
            val finished = withTimeout(60_000) {
                manager.getWorkInfoByIdFlow(UUID.fromString(id)).first { it?.state?.isFinished == true }
            }
            assertEquals(WorkInfo.State.SUCCEEDED, finished?.state)
            val output = storage.conversionOutput(key, id)
            val files = checkNotNull(output.listFiles())
            val sizes = Json.decodeFromString<Map<String, Long>>(checkNotNull(completed.conversionFiles))
            assertEquals(sizes, files.associate { it.name to it.length() })
            val hashes = Json.decodeFromString<Map<String, String>>(checkNotNull(completed.conversionHashes))
            assertEquals(hashes, files.associate { it.name to it.sha256() })
            assertFalse(output.resolveSibling("${output.name}.partial").exists())
            assertEquals(4_265_146_304L, checkpoint.length())
            assertEquals(SharedLcmState.Ready, app.sharedLcm.state.first())
            app.downloads.reconcile()
            assertEquals(completed, awaitModel(key) { it.conversionStatus == ModelConversionStatus.Converted })
        }
    }

    private suspend fun seedPublishedDownload(key: String, checkpoint: File) {
        withDatabase { it.models().insert(publishedDownload(key, checkpoint)) }
        app.downloads.reconcile()
    }

    private fun publishedDownload(key: String, checkpoint: File): LibraryModel {
        val (version, file) = key.split('_').map(String::toLong)
        return LibraryModel(key, 1, version, file, "Conversion fixture", "v1", checkpoint.name,
            checkpoint.length(), null, null, UUID.randomUUID().toString(),
            DownloadStatus.Verifying, checkpoint.length(), checkpoint.length())
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
            .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3).build()
        return try {
            block(db)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val CHECKPOINT_HASH = "6ce0161689b3853acaa03779ec93eafe75a02f4ced659bee03f50797806fa2fa"
    }
}
