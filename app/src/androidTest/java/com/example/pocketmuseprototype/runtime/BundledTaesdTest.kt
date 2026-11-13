package com.example.pocketmuseprototype.runtime

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.conversion.sha256
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledTaesdTest {
    @Test
    fun installsReusesAndRepairsTheBundledDecoder() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.noBackupFilesDir.toPath(), "taesd-test").toFile()
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val expected = directory.resolve("taesd/taesd.mnn")
        val partial = directory.resolve("taesd/taesd.mnn.partial")
        try {
            val installed = List(4) { async { BundledTaesd.prepare(context) } }.awaitAll()
            assertEquals(List(4) { expected }, installed)
            assertVerified(expected)

            assertTrue(expected.setLastModified(1_000_000L))
            val timestamp = expected.lastModified()
            partial.writeText("interrupted copy")
            assertEquals(expected, BundledTaesd.prepare(context))
            assertEquals(timestamp, expected.lastModified())
            assertFalse(partial.exists())

            RandomAccessFile(expected, "rw").use {
                val firstByte = it.readUnsignedByte()
                it.seek(0)
                it.write(firstByte xor 1)
            }
            assertEquals(expected, BundledTaesd.prepare(context))
            assertVerified(expected)

            assertTrue(expected.delete())
            partial.writeText("interrupted copy")
            assertEquals(expected, BundledTaesd.prepare(context))
            assertVerified(expected)
            assertFalse(partial.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertVerified(file: File) {
        assertTrue(file.isAbsolute && file.path.all { it in ' '..'~' })
        assertTrue(file.isFile && file.canRead())
        assertEquals(4_901_708L, file.length())
        assertEquals(EXPECTED_SHA256, file.sha256())
    }

    private companion object {
        const val EXPECTED_SHA256 = "7f2f997fd230d9d752e741c71e79f6595384df7add2eaa8697ef11376cd0d808"
    }
}
