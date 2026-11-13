package com.example.pocketmuseprototype

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.download.LibraryDatabase
import com.example.pocketmuseprototype.gallery.GalleryImage
import com.example.pocketmuseprototype.gallery.GalleryRepository
import com.example.pocketmuseprototype.gallery.GeneratedImageDao
import com.example.pocketmuseprototype.gallery.GeneratedImageRecord
import com.example.pocketmuseprototype.ui.GalleryPanel
import com.example.pocketmuseprototype.ui.theme.PocketMusePrototypeTheme
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class GalleryStorageAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun migratesVersionFourAndPersistsPngAndMetadataAcrossReopen() = runBlocking<Unit>(Dispatchers.IO) {
        val name = "gallery-v4-${UUID.randomUUID()}"
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "gallery").toFile()
        val dbFile = context.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("${LibraryDatabase::class.java.name}/4.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
            }
        val bitmap = Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(*LibraryDatabase.migrations).build()
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                val sql = schema.getValue("entities").jsonArray.single().jsonObject.getValue("createSql")
                db.execSQL(sql.jsonPrimitive.content.replace("\${TABLE_NAME}", "models"))
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL(
                    """INSERT INTO models (storageKey, modelId, modelVersionId, fileId, modelName, versionName,
                        fileName, fileSize, workId, downloadStatus, downloadedBytes, conversionHqqIterations)
                        VALUES ('20_30', 10, 20, 30, 'Fixture', 'v1', 'checkpoint', 4, 'work', 'Downloaded', 4, 7)"""
                )
                db.version = 4
            }
            val saved = open().useDatabase { db ->
                assertEquals(7, db.models().find("20_30")?.conversionHqqIterations)
                val repo = GalleryRepository(directory, db.generatedImages())
                val first = repo.save(bitmap, "Fixture", "First image", 4)
                val second = repo.save(bitmap, "Fixture", "Second image", 8)
                listOf(second, first)
            }
            open().useDatabase { db ->
                val repo = GalleryRepository(directory, db.generatedImages())
                assertEquals(saved, repo.gallery.first())
                val latest = saved.first()
                assertEquals("Second image", latest.record.prompt)
                assertEquals(8, latest.record.steps)
                assertEquals("Fixture", latest.record.modelName)
                val decoded = checkNotNull(BitmapFactory.decodeFile(latest.file.path))
                assertEquals(24, decoded.width)
                assertEquals(12, decoded.height)
                assertEquals(Color.MAGENTA, decoded.getPixel(0, 0))
                decoded.recycle()
                repo.delete(latest.record.id)
                assertFalse(latest.file.exists())
                assertEquals(listOf(saved.last()), repo.gallery.first())
                assertEquals("Fixture", db.models().find("20_30")?.modelName)
                db.models().delete(checkNotNull(db.models().find("20_30")))
                assertEquals(listOf(saved.last()), repo.gallery.first())
            }
        } finally {
            bitmap.recycle()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    @Test fun cleansFailedPublicationAndRecoversInterruptedDeletion() = runBlocking<Unit>(Dispatchers.IO) {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "gallery-errors").toFile()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().useDatabase { db ->
                val dao = db.generatedImages()
                val fileInsteadOfDirectory = File(directory, "blocked").apply { writeText("fixture") }
                expectIoFailure {
                    GalleryRepository(fileInsteadOfDirectory, dao).save(bitmap, "Model", "File failure", 2)
                }
                assertEquals("fixture", fileInsteadOfDirectory.readText())
                val failingDao = object : GeneratedImageDao by dao {
                    override suspend fun insert(record: GeneratedImageRecord) { throw IOException("Insert failed") }
                    override suspend fun delete(id: String) { throw IOException("Delete failed") }
                }
                val failing = GalleryRepository(directory, failingDao)
                expectIoFailure { failing.save(bitmap, "Model", "DB failure", 2) }
                assertEquals(listOf("blocked"), directory.list()?.toList())
                assertTrue(dao.all().isEmpty())
                val saved = GalleryRepository(directory, dao).save(bitmap, "Model", "Recovered", 2)
                expectIoFailure { failing.delete(saved.record.id) }
                assertTrue(saved.file.isFile)
                assertEquals(saved.record, dao.find(saved.record.id))

                val staged = File(directory, "${saved.file.name}.deleting")
                assertTrue(saved.file.renameTo(staged))
                val orphan = File(directory, "${UUID.randomUUID()}.png").apply { writeText("orphan") }
                val partial = File(directory, "${UUID.randomUUID()}.png.partial").apply { writeText("partial") }
                val missingId = UUID.randomUUID().toString()
                dao.insert(saved.record.copy(id = missingId, fileName = "$missingId.png"))
                val invalidId = UUID.randomUUID().toString()
                dao.insert(saved.record.copy(id = invalidId, fileName = "../blocked"))
                val recovered = GalleryRepository(directory, dao)
                assertEquals(listOf(saved), recovered.gallery.first())
                recovered.delete(invalidId)
                assertNull(dao.find(invalidId))
                assertTrue(fileInsteadOfDirectory.isFile)
                assertFalse(orphan.exists())
                assertFalse(partial.exists())
                assertFalse(staged.exists())
                assertTrue(saved.file.isFile)

                assertTrue(saved.file.renameTo(staged))
                dao.delete(saved.record.id)
                assertTrue(GalleryRepository(directory, dao).gallery.first().isEmpty())
                assertFalse(staged.exists())
            }
        } finally {
            bitmap.recycle()
            directory.deleteRecursively()
        }
    }

    private suspend fun expectIoFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
        }
    }

    private inline fun <T> LibraryDatabase.useDatabase(block: (LibraryDatabase) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}

class GalleryPanelAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun opensDetailsConfirmsDeletionAndShowsEmptyState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "gallery-ui").toFile()
        val file = File(directory, "fixture.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val record = GeneratedImageRecord("fixture", file.name, 1, "Fixture Model", "A small fox", 4)
        val images = MutableStateFlow(listOf(GalleryImage(record, file)))
        try {
            compose.setContent {
                PocketMusePrototypeTheme {
                    GalleryPanel(images, onDelete = { id ->
                        assertEquals(record.id, id)
                        images.value = emptyList()
                    }) {}
                }
            }
            compose.onNodeWithContentDescription(context.getString(R.string.gallery_thumbnail, record.prompt)).performClick()
            compose.onNodeWithText(context.getString(R.string.gallery_model, record.modelName)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.gallery_prompt, record.prompt)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.gallery_steps, record.steps)).assertIsDisplayed()
            val delete = context.getString(R.string.gallery_delete)
            compose.onNodeWithText(delete).performClick()
            compose.onNodeWithText(context.getString(R.string.gallery_confirm_delete)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.gallery_cancel)).performClick()
            compose.onNodeWithText(delete).performClick()
            compose.onNodeWithText(context.getString(R.string.gallery_delete_image)).performClick()
            compose.onNodeWithText(context.getString(R.string.gallery_empty)).assertIsDisplayed()
        } finally {
            directory.deleteRecursively()
        }
    }
}
