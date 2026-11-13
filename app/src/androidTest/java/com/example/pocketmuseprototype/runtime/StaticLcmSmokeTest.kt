package com.example.pocketmuseprototype.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.Process
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
import java.security.MessageDigest
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
    fun comparesFixedSeedWithAndWithoutFourTaesdPreviews() {
        assumeTrue(args.getString("taesdSmoke") == "true")
        runBlocking {
            val tokenizer = BundledClipTokenizer.prepare(app)
            val taesd = BundledTaesd.prepare(app)
            app.downloads.withStaticLcmOutput(storageKey()) { model, path, cache ->
                report("TAESD device=${Build.MODEL} Android=${Build.VERSION.RELEASE}/API${Build.VERSION.SDK_INT}" +
                    " pid=${Process.myPid()} model=${model.modelName} version=${model.versionName}" +
                    " steps=4 seed=42 prompt=$PROMPT cache=${cache?.relativeTo(app.noBackupFilesDir)}")
                var reference: ByteArray? = null
                for (decoder in listOf(null, taesd, taesd)) {
                    val events = mutableListOf<Progress>()
                    val previews = mutableListOf<Progress>()
                    var previousFrame: ByteArray? = null
                    val before = cacheFiles(cache)
                    val beforePss = Debug.getPss()
                    report("TAESD enabled=${decoder != null} cacheBeforeBytes=$before pssBeforeKiB=$beforePss")
                    val preview = decoder?.let {
                        GenerationPreview { step, total, width, height, rgb, elapsed ->
                            assertEquals(512, width)
                            assertEquals(512, height)
                            assertEquals(786_432, rgb.size)
                            assertTrue("Preview is a single RGB color", (3 until rgb.size).any { rgb[it] != rgb[it % 3] })
                            val digest = sha256(rgb)
                            assertFalse("Preview repeated the previous step", previousFrame?.contentEquals(digest) == true)
                            previousFrame = digest
                            previews += Progress("preview", step, total, elapsed)
                        }
                    }
                    val start = SystemClock.elapsedRealtimeNanos()
                    val image = StaticLcmRequest(path, tokenizer, PROMPT, cache, previewDecoder = decoder, seed = 42)
                        .generate(onPreview = preview) { stage, step, total, elapsed ->
                            events += Progress(stage, step, total, elapsed)
                        }
                    val totalMs = ms(SystemClock.elapsedRealtimeNanos() - start)
                    val after = cacheFiles(cache)
                    report("TAESD enabled=${decoder != null} totalMs=$totalMs generateMs=${ms(image.generateNanos)}" +
                        " vaeMs=${ms(image.generateNanos - events.single { it.stage == "vae_decoder" }.nanos)}" +
                        " cacheAfterBytes=$after pssAfterKiB=${Debug.getPss()}")
                    assertEquals(42L, image.seed)
                    if (decoder == null) {
                        assertTrue(previews.isEmpty())
                        reference = sha256(image.rgb)
                    } else {
                        assertEquals(listOf(1, 2, 3, 4), previews.map { it.step })
                        assertTrue(previews.all { it.total == 4 })
                        previews.forEach { frame ->
                            val denoising = events.single { it.stage == "denoising" && it.step == frame.step }
                            assertTrue(frame.nanos >= denoising.nanos)
                            report("TAESD step=${frame.step} elapsedMs=${ms(frame.nanos)}" +
                                " sinceDenoisingMs=${ms(frame.nanos - denoising.nanos)}")
                        }
                        assertTrue("TAESD changed the fixed-seed final RGB8 output",
                            checkNotNull(reference).contentEquals(sha256(image.rgb)))
                        assertTrue(after.keys.any { File(it).name.startsWith("preview-") })
                        assertTrue(after.keys.containsAll(before.keys))
                    }
                }
            }
        }
    }

    @Test
    fun cancelsFromTheFirstTaesdCallbackAndPreservesCallbackFailure() {
        assumeTrue(args.getString("taesdSmoke") == "true")
        runBlocking {
            val tokenizer = BundledClipTokenizer.prepare(app)
            val taesd = BundledTaesd.prepare(app)
            app.downloads.withStaticLcmOutput(storageKey()) { _, path, cache ->
                val cancelled = StaticLcmRequest(path, tokenizer, PROMPT, cache, previewDecoder = taesd)
                var frames = 0
                try {
                    cancelled.generate(onPreview = GenerationPreview { step, _, _, _, _, _ ->
                        assertEquals(1, step)
                        frames++
                        cancelled.requestCancel()
                    })
                    fail("Expected cancellation from the TAESD callback")
                } catch (_: CancellationException) {
                    assertEquals(1, frames)
                    report("TAESD cancelled from step 1 pssAfterKiB=${Debug.getPss()}")
                }
                val expected = IllegalStateException("Preview callback test failure")
                try {
                    StaticLcmRequest(path, tokenizer, PROMPT, cache, previewDecoder = taesd)
                        .generate(onPreview = GenerationPreview { _, _, _, _, _, _ -> throw expected })
                    fail("Expected TAESD callback failure")
                } catch (actual: IllegalStateException) {
                    assertTrue("JNI must propagate the original exception", actual === expected)
                }
                try {
                    StaticLcmRequest(path, tokenizer, PROMPT, cache, previewDecoder = taesd.resolveSibling("missing.mnn"))
                        .generate(onPreview = GenerationPreview { _, _, _, _, _, _ -> fail("Unexpected preview") })
                    fail("Expected a missing decoder to fail generation")
                } catch (error: RuntimeGenerationException) {
                    assertTrue(error.message.orEmpty().contains("TAESD"))
                }
            }
        }
    }

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
                " pid=${Process.myPid()} storageKey=$key backend=OpenCLBuffer precision=Low tuning=Fast lowMemory=true" +
                " sampling=LCM steps=4 guidance=1.0 preview=off dynamicLoRA=off prompt=$PROMPT")

            val image = app.downloads.withStaticLcmOutput(key) { model, path, modelCache ->
                val run = args.getString("cacheRun")
                val cache = if (run == null) modelCache else {
                    require(UUID.fromString(run).toString() == run)
                    app.noBackupFilesDir.resolve("runtime-smoke/opencl-cache/$run/$key/${path.name}")
                }
                val before = cacheFiles(cache)
                if (run != null) {
                    if (args.getString("expectColdCache") == "true") {
                        check(cache != null && (!cache.exists() || cache.list()?.isEmpty() == true)) {
                            "Cold requires an empty test cache. Pass a fresh cacheRun UUID."
                        }
                    } else {
                        assertTrue("Warm run requires an existing cache", before.isNotEmpty())
                    }
                }
                report("model=${model.modelName} version=${model.versionName} output=${path.name}" +
                    " cache=${cache?.relativeTo(app.noBackupFilesDir)} cacheBeforeBytes=$before")
                val result = StaticLcmRequest(path, tokenizer, PROMPT, cache).generate { stage, step, total, elapsed ->
                    events += Progress(stage, step, total, elapsed)
                    Log.i(TAG, "$stage $step/$total generateElapsedMs=${ms(elapsed)}")
                }
                val after = cacheFiles(cache)
                report("cacheAfterBytes=$after")
                if (run != null) {
                    assertTrue("Runtime must use the supplied cache directory", after.isNotEmpty())
                }
                result
            }
            report("seed=${image.seed} runtimeCreationMs=${ms(image.runtimeNanos)}" +
                " generateMs=${ms(image.generateNanos)}")
            assertTrue(image.seed in 0..0xffff_ffffL)
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
                StaticLcmRequest(missing, missing, PROMPT, cacheDir = null).generate()
                fail("Expected initialization error")
            } catch (expected: RuntimeInitializationException) {
                assertTrue(expected.message.orEmpty().contains("Missing model"))
                report("initialization error propagated")
            }
            val request = StaticLcmRequest(missing, missing, PROMPT, cacheDir = null)
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
            app.downloads.withStaticLcmOutput(storageKey()) { _, path, cache ->
                val callbackFailure = IllegalStateException("Smoke callback failure")
                try {
                    StaticLcmRequest(path, tokenizer, PROMPT, cache).generate { _, _, _, _ -> throw callbackFailure }
                    fail("Expected callback failure")
                } catch (error: IllegalStateException) {
                    assertTrue(error === callbackFailure)
                    report("original callback exception propagated")
                }

                val request = StaticLcmRequest(path, tokenizer, PROMPT, cache)
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

        val storage = ModelStorage(noBackupFiles = { app.noBackupFilesDir }) { external }
        val version = 90401L
        val fileId = 90402L
        val key = ModelStorage.key(version, fileId)
        val id = UUID.randomUUID().toString()
        val checkpoint = storage.checkpoint(key)
        val output = storage.conversionOutput(key, id)
        val modelRoot = output.parentFile!!.parentFile!!
        val cacheDir = app.noBackupFilesDir.resolve("opencl-cache/$key/$id")
        val db = Room.databaseBuilder(app, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .addMigrations(*LibraryDatabase.migrations)
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
                    app.downloads.withStaticLcmOutput(key) { _, path, cache ->
                        assertEquals(cacheDir, cache)
                        try {
                            StaticLcmRequest(path, tokenizer, PROMPT, cache).generate { stage, step, _, _ ->
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
                            filesAtNativeReturn.complete(cacheDir.isDirectory && checkpoint.isFile &&
                                names.all { output.resolve(it).isFile })
                        }
                    }
                }
                try {
                    withTimeout(300_000) { firstStep.await() }
                    try {
                        StaticLcmRequest(missing, missing, PROMPT, cacheDir = null).generate()
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
                    assertTrue(cacheFiles(cacheDir).isNotEmpty())
                    report("fixture model and cache remain while Deleting at denoising step 1")
                } finally {
                    releaseCallback.countDown()
                }
                withTimeout(30_000) { generation.join() }
                assertTrue(generation.isCancelled)
                assertTrue(filesAtNativeReturn.await())
                assertTrue("Cancellation must reach native before all four steps", steps.get() in 1..3)
                assertNull(dao.find(key))
                assertFalse(modelRoot.exists())
                assertFalse(cacheDir.parentFile!!.exists())
                try {
                    StaticLcmRequest(missing, missing, PROMPT, cacheDir = null).generate()
                    fail("Expected the missing model to fail after the native guard was released")
                } catch (_: RuntimeInitializationException) {
                    report("coroutine cancellation reached native at step ${steps.get()}; fixture removed after JNI returned")
                }
            }
        } finally {
            db.close()
        }
    }

    private fun cacheFiles(directory: File?): Map<String, Long> =
        directory?.walkTopDown()?.filter { it.isFile && it.length() > 0 }
            ?.associate { it.relativeTo(directory).path to it.length() }?.toSortedMap().orEmpty()

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
        val bitmap = image.toBitmap()
        val pixels = IntArray(image.width * image.height)
        try {
            bitmap.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            assertTrue("Generated image is a single color", pixels.any { it != pixels[0] })
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

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

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
