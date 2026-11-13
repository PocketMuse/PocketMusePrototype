package com.example.pocketmuseprototype.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.PocketMuseApplication
import com.example.pocketmuseprototype.conversion.BundledHqqTemplate
import com.example.pocketmuseprototype.conversion.sha256
import com.example.pocketmuseprototype.download.DownloadStatus
import com.example.pocketmuseprototype.download.LibraryDatabase
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.ModelConversionStatus
import com.example.pocketmuseprototype.download.ModelStorage
import com.example.pocketmuseprototype.download.STATIC_LCM_RECIPE
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaticLcmSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val app = instrumentation.targetContext.applicationContext as PocketMuseApplication

    @Test
    fun generatesAndDecodesPng() {
        assumeTrue(args.getString("staticLcmSmoke") == "true")
        runBlocking {
            val start = SystemClock.elapsedRealtimeNanos()
            val tokenizer = verifiedTokenizer()
            val key = storageKey()
            val events = mutableListOf<Progress>()
            val beforePss = Debug.getPss()
            report("device=${Build.MODEL} Android=${Build.VERSION.RELEASE}/API${Build.VERSION.SDK_INT}" +
                " ABI=${Build.SUPPORTED_ABIS.first()} build=debug runtimeOptimization=O3" +
                " storageKey=$key backend=OpenCLBuffer precision=Low tuning=Fast lowMemory=true" +
                " sampling=LCM steps=4 guidance=1.0 cache=off preview=off dynamicLoRA=off prompt=$PROMPT")

            val image = app.downloads.withStaticLcmOutput(key) { model, path ->
                report("model=${model.modelName} version=${model.versionName} output=${path.name}")
                StaticLcmRequest(path, tokenizer, PROMPT).generate { stage, step, total, elapsed ->
                    events += Progress(stage, step, total, elapsed)
                    Log.i(TAG, "$stage $step/$total generateElapsedMs=${ms(elapsed)}")
                }
            }
            report("seed=${image.seed} runtimeCreationMs=${ms(image.runtimeNanos)}" +
                " generateMs=${ms(image.generateNanos)}")
            assertEquals(42L, image.seed)
            assertEquals(512, image.width)
            assertEquals(512, image.height)
            assertEquals(786_432, image.rgb.size)
            assertEquals(
                listOf("text_encoder" to 0, "unet" to 0, "denoising" to 1, "denoising" to 2,
                    "denoising" to 3, "denoising" to 4, "vae_decoder" to 0),
                events.map { it.stage to it.step }
            )
            assertTrue(events.all { it.total == 4 })
            val boundaries = events.map { it.nanos } + image.generateNanos
            val durations = boundaries.zipWithNext { begin, end ->
                assertTrue(end >= begin)
                ms(end - begin)
            }
            report("clipEntryMs=${ms(boundaries.first())} clipMs=${durations[0]}" +
                " unetEntryMs=${ms(boundaries[1])} unetLoadAndStep1Ms=${durations[1]}" +
                " step2Ms=${durations[2]} step3Ms=${durations[3]} step4Ms=${durations[4]}" +
                " unetReleaseMs=${durations[5]} vaeMs=${durations[6]}")

            val pngStart = SystemClock.elapsedRealtimeNanos()
            val directory = File(app.filesDir, "runtime-smoke/results")
            check(directory.isDirectory || directory.mkdirs())
            val output = File.createTempFile("static-lcm-$key-", ".png", directory)
            try {
                saveAndCheckPng(image, output)
            } catch (error: Throwable) {
                output.delete()
                throw error
            }
            val finished = SystemClock.elapsedRealtimeNanos()
            report("png=${output.relativeTo(app.filesDir)} pngSaveAndDecodeMs=${ms(finished - pngStart)}" +
                " totalMs=${ms(finished - start)} pssBeforeKiB=$beforePss pssAfterKiB=${Debug.getPss()}")
        }
    }

    @Test
    fun reportsInitializationErrorAndPreCancellation() {
        assumeTrue(args.getString("runtimeContract") == "true")
        runBlocking {
            val missing = File(app.cacheDir, "missing-static-lcm-model")
            check(!missing.exists())
            try {
                StaticLcmRequest(missing, missing, PROMPT).generate()
                fail("Expected initialization error")
            } catch (expected: RuntimeInitializationException) {
                assertTrue(expected.message.orEmpty().contains("Missing model"))
                report("initialization error propagated")
            }
            val request = StaticLcmRequest(missing, missing, PROMPT)
            request.requestCancel()
            try {
                request.generate()
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                report("pre-cancellation propagated before model initialization")
            }
        }
    }

    @Test
    fun propagatesCallbackFailureAndCancelsAfterFirstDenoisingStep() {
        assumeTrue(args.getString("staticLcmCancelSmoke") == "true")
        runBlocking {
            val tokenizer = verifiedTokenizer()
            app.downloads.withStaticLcmOutput(storageKey()) { _, path ->
                val callbackFailure = IllegalStateException("Smoke callback failure")
                try {
                    StaticLcmRequest(path, tokenizer, PROMPT).generate { _, _, _, _ -> throw callbackFailure }
                    fail("Expected callback failure")
                } catch (error: IllegalStateException) {
                    assertTrue(error === callbackFailure)
                    report("original callback exception propagated")
                }

                val request = StaticLcmRequest(path, tokenizer, PROMPT)
                val events = mutableListOf<Pair<String, Int>>()
                try {
                    request.generate { stage, step, _, _ ->
                        events += stage to step
                        if (stage == "denoising" && step == 1) request.requestCancel()
                    }
                    fail("Expected cancellation")
                } catch (_: CancellationException) {
                    assertEquals(listOf("text_encoder" to 0, "unet" to 0, "denoising" to 1), events)
                    report("cancelled after denoising 1/4 and returned after native cleanup")
                }
            }
        }
    }

    @Test
    fun coroutineCancellationKeepsFixtureFilesUntilNativeReturn() {
        assumeTrue(args.getString("staticLcmDeleteSmoke") == "true")
        check(app.packageName == "com.example.pocketmuseprototype.runtimefixture") {
            "This test deletes a model and requires -PruntimeFixture=true"
        }
        val tokenizer = verifiedTokenizer()
        val external = checkNotNull(app.getExternalFilesDir(null))
        val input = external.resolve("runtime-cancel-input")
        val names = listOf("text_encoder.mnn", "unet.mnn", "vae_decoder.mnn")
        val inputs = names.associateWith(input::resolve)
        check(inputs.values.all { it.isFile && it.length() > 0 })
        val sizes = inputs.mapValues { it.value.length() }
        val hashes = inputs.mapValues { it.value.sha256() }

        val storage = ModelStorage { external }
        val version = 90401L
        val fileId = 90402L
        val key = ModelStorage.key(version, fileId)
        val id = UUID.randomUUID().toString()
        val checkpoint = storage.checkpoint(key)
        val output = storage.conversionOutput(key, id)
        val modelRoot = output.parentFile!!.parentFile!!
        val db = Room.databaseBuilder(app, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4)
            .build()
        try {
            runBlocking {
                val dao = db.models()
                check(dao.find(key) == null && !modelRoot.exists()) { "Fixture key already in use: $key" }
                check(output.mkdirs())
                for ((name, file) in inputs) check(file.renameTo(output.resolve(name)))
                storage.prepare(key)
                checkpoint.writeText("Isolated Runtime fixture")
                val bytes = checkpoint.length()
                val model = LibraryModel(
                    key, version, version, fileId, "ToonYou deletion fixture", "Beta 6",
                    checkpoint.name, bytes, null, null, UUID.randomUUID().toString(),
                    downloadStatus = DownloadStatus.Downloaded, downloadedBytes = bytes, totalBytes = bytes,
                    conversionStatus = ModelConversionStatus.Converted, conversionWorkId = id,
                    conversionProgress = 100, conversionTemplateId = BundledHqqTemplate.ID,
                    conversionRecipeId = STATIC_LCM_RECIPE, conversionFiles = Json.encodeToString(sizes),
                    conversionHashes = Json.encodeToString(hashes),
                )
                dao.insert(model)

                val firstStep = CompletableDeferred<Unit>()
                val releaseCallback = CountDownLatch(1)
                val steps = AtomicInteger()
                val filesAtNativeReturn = CompletableDeferred<Boolean>()
                val missing = File(app.cacheDir, "missing-${UUID.randomUUID()}")
                val generation = launch {
                    app.downloads.withStaticLcmOutput(key) { _, path ->
                        try {
                            StaticLcmRequest(path, tokenizer, PROMPT).generate { stage, step, _, _ ->
                                if (stage == "denoising") {
                                    steps.set(step)
                                    if (step == 1) {
                                        firstStep.complete(Unit)
                                        check(releaseCallback.await(30, TimeUnit.SECONDS))
                                    }
                                }
                            }
                            fail("A cancelled generation must not return an image")
                        } finally {
                            filesAtNativeReturn.complete(checkpoint.isFile && names.all { output.resolve(it).isFile })
                        }
                    }
                }
                try {
                    withTimeout(300_000) { firstStep.await() }
                    try {
                        StaticLcmRequest(missing, missing, PROMPT).generate()
                        fail("Expected concurrent generation to be rejected")
                    } catch (error: RuntimeGenerationException) {
                        assertTrue(error.message.orEmpty().contains("in progress"))
                    }
                    generation.cancel()
                    withTimeout(10_000) { app.downloads.remove(model) }
                    assertEquals(DownloadStatus.Deleting, dao.find(key)?.downloadStatus)
                    assertFalse(generation.isCompleted)
                    assertFalse(filesAtNativeReturn.isCompleted)
                    assertTrue(checkpoint.isFile && names.all { output.resolve(it).isFile })
                    report("fixture is Deleting while the UNet session is held at denoising step 1")
                } finally {
                    releaseCallback.countDown()
                }
                withTimeout(30_000) { generation.join() }
                assertTrue(generation.isCancelled)
                assertTrue(filesAtNativeReturn.await())
                assertTrue("Cancellation must reach native before all four steps", steps.get() in 1..3)
                assertNull(dao.find(key))
                assertFalse(modelRoot.exists())
                try {
                    StaticLcmRequest(missing, missing, PROMPT).generate()
                    fail("Expected the missing model to fail after the native guard was released")
                } catch (_: RuntimeInitializationException) {
                    report("coroutine cancellation reached native at step ${steps.get()}; fixture removed after JNI returned")
                }
            }
        } finally {
            db.close()
        }
    }

    private fun verifiedTokenizer(): File =
        File(app.filesDir, "runtime-smoke/tokenizer").also { directory ->
            for ((name, sha) in TOKENIZER_HASHES) {
                val file = File(directory, name)
                check(file.isFile && file.sha256() == sha) { "Missing or invalid tokenizer asset: $file" }
            }
        }

    private fun storageKey(): String = requireNotNull(args.getString("storageKey")) {
        "Pass -e storageKey <modelVersionId_fileId>"
    }.also { require(it.matches(Regex("[1-9][0-9]*_[1-9][0-9]*"))) }

    private fun saveAndCheckPng(image: GeneratedImage, output: File) {
        val pixels = IntArray(image.width * image.height) { index ->
            val offset = index * 3
            val r = image.rgb[offset].toInt() and 0xff
            val g = image.rgb[offset + 1].toInt() and 0xff
            val b = image.rgb[offset + 2].toInt() and 0xff
            0xff000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        assertTrue("Generated image is a single color", pixels.any { it != pixels[0] })
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val decoded = checkNotNull(BitmapFactory.decodeFile(output.absolutePath))
        try {
            assertEquals(image.width, decoded.width)
            assertEquals(image.height, decoded.height)
            val decodedPixels = IntArray(pixels.size)
            decoded.getPixels(decodedPixels, 0, image.width, 0, 0, image.width, image.height)
            assertTrue("PNG changed RGB pixels", decodedPixels.contentEquals(pixels))
        } finally {
            decoded.recycle()
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        println("$TAG $message")
    }

    private fun ms(nanos: Long): Double = nanos / 1_000_000.0

    private data class Progress(val stage: String, val step: Int, val total: Int, val nanos: Long)

    companion object {
        private const val TAG = "StaticLcmSmoke"
        private const val PROMPT = "masterpiece, best quality, a cute cat"
        private val TOKENIZER_HASHES = mapOf(
            "vocab.json" to "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349",
            "merges.txt" to "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a",
        )
    }
}
