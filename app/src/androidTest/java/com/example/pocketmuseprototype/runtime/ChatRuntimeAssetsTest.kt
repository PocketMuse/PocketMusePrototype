package com.example.pocketmuseprototype.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.conversion.sha256
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
