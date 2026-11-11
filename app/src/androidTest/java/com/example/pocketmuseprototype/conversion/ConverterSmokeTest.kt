package com.example.pocketmuseprototype.conversion

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConverterSmokeTest {
    @Test
    fun convertsPreparedHqqModel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("converterSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputs = checkNotNull(context.getExternalFilesDir(null)).resolve("converter-smoke")
        val template = inputs.resolve("template")
        val files = JSONObject(template.resolve("manifest.json").readText()).getJSONArray("files")
        val expectedFiles = List(files.length()) { index ->
            val file = files.getJSONObject(index)
            ConvertedFile(file.getString("name"), file.getLong("size"))
        }
        val workingDir = createTempDirectory(inputs.toPath(), "run-").toFile()
        val output = workingDir.resolve("output")
        try {
            var lastStage: ConversionStage? = null
            val result = ConversionRequest(
                ConversionOptions(inputs.resolve("model.safetensors"), template, output, threadCount = 4),
            ).run { lastStage = it.stage }
            val otherMillis = result.elapsedMillis - result.hqqComputeMillis
            Log.i(
                TAG,
                "status=${result.status} error=${result.errorCode} elapsedMs=${result.elapsedMillis} " +
                    "hqqMs=${result.hqqComputeMillis} otherMs=$otherMillis " +
                    "files=${result.files.joinToString { "${it.name}:${it.sizeBytes}" }}",
            )

            assertEquals(result.errorCode.name, ConversionStatus.Succeeded, result.status)
            assertEquals(ConversionErrorCode.NONE, result.errorCode)
            assertEquals(ConversionStage.Completed, lastStage)
            assertEquals(20, result.hqqIterations)
            assertEquals(expectedFiles, result.files)
            assertEquals(expectedFiles.map { it.name }.toSet(), checkNotNull(output.list()).toSet())
            expectedFiles.forEach { assertEquals(it.sizeBytes, File(output, it.name).length()) }
            assertFalse(workingDir.resolve("output.partial").exists())
            assertTrue(result.hqqComputeMillis > 0)
            val reference = inputs.resolve("reference-sha256.txt")
            if (reference.isFile) {
                val expectedHashes = reference.readLines().associate { line ->
                    val (hash, name) = line.trim().split(Regex("\\s+"), limit = 2)
                    name to hash
                }
                val actualHashes = expectedFiles.associate { it.name to output.resolve(it.name).sha256() }
                assertEquals(expectedHashes, actualHashes)
                Log.i(TAG, "SHA-256 matched ${actualHashes.size} CLI outputs")
            }
        } finally {
            workingDir.deleteRecursively()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        forEachBlock(64 * 1024) { buffer, size -> digest.update(buffer, 0, size) }
        return digest.digest().toHexString()
    }

    private companion object {
        const val TAG = "ConverterSmokeTest"
    }
}
