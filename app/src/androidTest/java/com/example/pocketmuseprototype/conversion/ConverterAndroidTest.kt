package com.example.pocketmuseprototype.conversion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConverterAndroidTest {
    @Test
    fun deliversResultAndProgressOnCallingThreadWithDefaultHqqIterations() = withFixture { fixture ->
        val callingThread = Thread.currentThread()
        val events = mutableListOf<ConversionProgress>()
        var onCallingThread = true
        val result = ConversionRequest(fixture.options).run { progress ->
            onCallingThread = onCallingThread && Thread.currentThread() == callingThread
            events += progress
        }

        assertEquals(ConversionStatus.Succeeded, result.status)
        assertEquals(ConversionErrorCode.NONE, result.errorCode)
        assertEquals(0, result.hqqIterations)
        assertEquals(listOf(ConvertedFile("model.mnn", 13)), result.files)
        assertTrue(onCallingThread)
        assertEquals(ConversionStage.Validating, events.first().stage)
        assertEquals(null, events.first().remainingMillis)
        assertEquals(ConversionStage.Completed, events.last().stage)
        assertEquals(1.0, events.last().fraction, 0.0)
        assertEquals(13L, events.last().completedBytes)
        assertEquals(13L, events.last().totalBytes)
    }

    @Test
    fun passesUnicodeLoraPathAndMapsNativeErrors() = withFixture { fixture ->
        val lora = fixture.options.templateDir.resolve("어댑터-📦.safetensors")
        val options = fixture.options.copy(lcmLora = lora)
        val stages = mutableListOf<ConversionStage>()

        val missing = ConversionRequest(options).run { stages += it.stage }
        assertEquals(ConversionStatus.Failed, missing.status)
        assertEquals(ConversionErrorCode.READ_FAILED, missing.errorCode)
        assertTrue(missing.files.isEmpty())
        assertEquals(listOf(ConversionStage.Validating), stages)

        val invalidBytes = "not the official adapter".encodeToByteArray()
        lora.writeBytes(invalidBytes)
        val invalid = ConversionRequest(options).run()
        assertEquals(ConversionStatus.Failed, invalid.status)
        assertEquals(ConversionErrorCode.INVALID_INPUT, invalid.errorCode)
        assertTrue(invalid.files.isEmpty())
    }

    @Test
    fun cancellationFromAnotherThreadCleansStagingBeforeReturning() = withFixture { fixture ->
        val request = ConversionRequest(fixture.options)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val result = request.run { progress ->
                if (progress.stage == ConversionStage.Finalizing) {
                    caller.submit { request.requestCancel() }.get(10, TimeUnit.SECONDS)
                }
            }
            assertEquals(ConversionStatus.Cancelled, result.status)
            assertEquals(ConversionErrorCode.NONE, result.errorCode)
            assertTrue(result.files.isEmpty())
            assertFalse(fixture.partial.exists())
            assertFalse(fixture.options.outputDir.exists())
            request.requestCancel()

            val preCancelled = ConversionRequest(fixture.options)
            preCancelled.requestCancel()
            assertEquals(ConversionStatus.Cancelled, preCancelled.run().status)
            assertEquals(ConversionStatus.Succeeded, ConversionRequest(fixture.options).run().status)
        } finally {
            caller.shutdownNow()
        }
    }

    @Test
    fun callbackFailureAndReentryRespectPublicationBoundary() = withFixture { fixture ->
        var reentrantConversion: ConversionErrorCode? = null
        var reentrantCleanup: ConversionErrorCode? = null
        val failed = ConversionRequest(fixture.options).run { progress ->
            if (progress.stage == ConversionStage.Finalizing) {
                reentrantConversion = ConversionRequest(fixture.options).run().errorCode
                reentrantCleanup = ConversionRequest.cleanupPartial(fixture.options.outputDir)
                error("Observer failed before publication")
            }
        }
        assertEquals(ConversionErrorCode.BUSY, reentrantConversion)
        assertEquals(ConversionErrorCode.BUSY, reentrantCleanup)
        assertEquals(ConversionStatus.Failed, failed.status)
        assertEquals(ConversionErrorCode.CALLBACK_FAILED, failed.errorCode)
        assertFalse(fixture.partial.exists())
        assertFalse(fixture.options.outputDir.exists())

        val request = ConversionRequest(fixture.options)
        val success = request.run { progress ->
            if (progress.stage == ConversionStage.Completed) {
                request.requestCancel()
                error("Observer failed after publication")
            }
        }
        assertEquals(ConversionStatus.Succeeded, success.status)
        assertEquals(ConversionErrorCode.NONE, success.errorCode)
        assertTrue(fixture.outputFile.isFile)
    }

    @Test
    fun mapsCleanupResultThroughJni() = withFixture { fixture ->
        assertEquals(ConversionErrorCode.NONE, ConversionRequest.cleanupPartial(fixture.options.outputDir))
        assertTrue(fixture.partial.mkdir())
        fixture.partial.resolve("keep.txt").writeText("caller file")
        assertEquals(ConversionErrorCode.CLEANUP_FAILED, ConversionRequest.cleanupPartial(fixture.options.outputDir))
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = createTempDirectory(context.cacheDir.toPath(), "converter-").toFile()
        try {
            test(Fixture(directory.resolve("모델-📦").apply { check(mkdir()) }))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class Fixture(root: File) {
        val options = ConversionOptions(root.resolve("weights.safetensors"), root, root.resolve("output"), chunkBytes = 4)
        val partial = root.resolve("output.partial")
        val outputFile = options.outputDir.resolve("model.mnn")

        init {
            root.resolve("graph.bin").writeBytes(byteArrayOf(0x4d, 0x4e, 0x4e))
            // The manifest describes the template recipe. The request supplies the runtime iteration count.
            val quantization = """{"algorithm":"hqq","bits":8,"iterations":20,"lp_norm":0.7,"beta":10,
                "group_elements":2,"group_count":1}""".trimIndent()
            root.resolve("manifest.json").writeText(
                """{"format":"sd15-mnn-template","version":2,"mnn_version":"3.6.1",
                "template":"graph.bin","template_size":3,"files":[{"name":"model.mnn","size":13,"segments":[
                {"kind":"literal","offset":0,"size":3,"template_offset":0},
                {"kind":"quantized","offset":3,"size":2,"key":"w","source_shape":[2],"shape":[2],
                 "dtype":"U8","field":"Weight","positive_zero":false,"quantization":$quantization},
                {"kind":"quantized","offset":5,"size":8,"key":"w","source_shape":[2],"shape":[1,2],
                 "dtype":"F32","field":"Alpha","positive_zero":false,"quantization":$quantization}]}]}""".trimIndent(),
            )
            val header = """{"w":{"dtype":"F32","shape":[2],"data_offsets":[0,8]}}""".encodeToByteArray()
            options.checkpoint.writeBytes(
                ByteBuffer.allocate(Long.SIZE_BYTES + header.size + 2 * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(header.size.toLong())
                    .put(header)
                    .putFloat(1f).putFloat(-1f)
                    .array(),
            )
        }
    }
}
