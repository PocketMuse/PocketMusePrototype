package com.example.pocketmuseprototype.runtime

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.conversion.sha256
import com.example.pocketmuseprototype.ui.ChatGeneration.Status
import com.example.pocketmuseprototype.ui.ChatImageRequest
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRuntimeAssetsTest {
    @Test
    fun installsReusesAndRepairsVerifiedTokenizerFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = Files.createTempDirectory(context.cacheDir.toPath(), "tokenizer-test").toFile()
        try {
            assertEquals(target, BundledClipTokenizer.prepare(context, target))
            val vocab = target.resolve("vocab.json")
            val merges = target.resolve("merges.txt")
            assertEquals("e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349", vocab.sha256())
            assertEquals("9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a", merges.sha256())
            assertTrue(vocab.setLastModified(1_000_000L))
            val timestamp = vocab.lastModified()
            merges.writeText("corrupt")
            val partial = target.resolve("merges.txt.partial").apply { writeText("interrupted copy") }

            BundledClipTokenizer.prepare(context, target)
            assertEquals(timestamp, vocab.lastModified())
            assertEquals("9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a", merges.sha256())
            assertFalse(partial.exists())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun mapsUnsignedRgbBytesInRowOrderToOpaqueBitmapPixels() {
        val rgb = ByteArray(512 * 512 * 3)
        byteArrayOf(0xff.toByte(), 0x80.toByte(), 0x11).copyInto(rgb)
        byteArrayOf(0x22, 0xcc.toByte(), 0x93.toByte()).copyInto(rgb, 512 * 3)
        val bitmap = GeneratedImage(0L, 512, 512, rgb, 0L, 0L).toBitmap()
        try {
            assertEquals(0xffff8011.toInt(), bitmap.getPixel(0, 0))
            assertEquals(0xff000000.toInt(), bitmap.getPixel(1, 0))
            assertEquals(0xff22cc93.toInt(), bitmap.getPixel(0, 1))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun keepsOnlyTheNewestPreviewThroughFinishingAndSaving() = runBlocking {
        val finish = CompletableDeferred<Unit>()
        val save = CompletableDeferred<Unit>()
        val callbacks = CompletableDeferred<Pair<GenerationProgress, GenerationPreview>>()
        val request = ChatImageRequest("1_2", "Model", 4, "Prompt")
        val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("final.png")
        val rgb = ByteArray(786_432)
        val final = rgb.copyOf().apply { this[0] = 0x42 }
        val viewModel = ImageGenerationViewModel(
            saveImage = { bitmap, _ ->
                assertEquals(0xff420000.toInt(), bitmap.getPixel(0, 0))
                save.await()
                file
            },
        ) { _, progress, preview ->
            callbacks.complete(progress to preview)
            finish.await()
            GeneratedImage(42, 512, 512, final, 0, 0)
        }
        try {
            withContext(Dispatchers.Main) { viewModel.generate(request) }
            val (progress, preview) = withTimeout(5_000) { callbacks.await() }
            preview.update(1, 4, 512, 512, rgb, 0)
            val first = checkNotNull((viewModel.state.value.single().status as Status.Running).preview)
            assertEquals(1, first.step)
            rgb[0] = 0x12
            preview.update(2, 4, 512, 512, rgb, 0)
            val second = checkNotNull((viewModel.state.value.single().status as Status.Running).preview)
            assertEquals(2, second.step)
            assertEquals(0xff120000.toInt(), second.image.asAndroidBitmap().getPixel(0, 0))
            assertEquals(0xff000000.toInt(), first.image.asAndroidBitmap().getPixel(0, 0))
            preview.update(1, 4, 512, 512, rgb, 0)
            progress.update("vae_decoder", 0, 4, 0)
            assertSame(second, (viewModel.state.value.single().status as Status.Running).preview)
            finish.complete(Unit)
            val saving = withTimeout(5_000) { viewModel.state.first { it.single().status is Status.Saving } }
            assertSame(second, (saving.single().status as Status.Saving).preview)
            save.complete(Unit)
            val completed = withTimeout(5_000) { viewModel.state.first { it.single().status is Status.Complete } }
            assertEquals(Status.Complete(file), completed.single().status)
            preview.update(4, 4, 512, 512, rgb, 0)
            assertEquals(Status.Complete(file), viewModel.state.value.single().status)
        } finally {
            finish.complete(Unit)
            save.complete(Unit)
        }
    }

    @Test
    fun clearsPreviewOnGenerationFailureAndUsesFinalPixelsOnSaveFailure() = runBlocking {
        val rgb = ByteArray(786_432)
        val final = rgb.copyOf().apply { this[0] = 0x42 }
        for (generationFails in listOf(true, false)) {
            val callbacks = CompletableDeferred<GenerationPreview>()
            val finish = CompletableDeferred<Unit>()
            val viewModel = ImageGenerationViewModel(saveImage = { _, _ -> throw IOException("Save failed") }) { _, _, preview ->
                callbacks.complete(preview)
                finish.await()
                if (generationFails) throw RuntimeGenerationException("TAESD failed")
                GeneratedImage(42, 512, 512, final, 0, 0)
            }
            try {
                withContext(Dispatchers.Main) { viewModel.generate(ChatImageRequest("1_2", "Model", 4, "Prompt")) }
                val preview = withTimeout(5_000) { callbacks.await() }
                preview.update(1, 4, 512, 512, rgb, 0)
                finish.complete(Unit)
                val result = withTimeout(5_000) {
                    viewModel.state.first { it.single().status is Status.Failed || it.single().status is Status.SaveFailed }
                }.single().status
                if (generationFails) {
                    assertTrue(result is Status.Failed)
                } else {
                    val bitmap = checkNotNull((result as Status.SaveFailed).image).asAndroidBitmap()
                    assertEquals(0xff420000.toInt(), bitmap.getPixel(0, 0))
                }
                preview.update(2, 4, 512, 512, rgb, 0)
                assertSame(result, viewModel.state.value.single().status)
            } finally {
                finish.complete(Unit)
            }
        }
    }
}
