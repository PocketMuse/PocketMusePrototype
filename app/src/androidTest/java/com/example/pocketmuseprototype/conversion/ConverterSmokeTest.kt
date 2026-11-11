package com.example.pocketmuseprototype.conversion

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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

            assertEquals(result.errorCode.name, ConversionStatus.Succeeded, result.status)
            assertEquals(ConversionErrorCode.NONE, result.errorCode)
            assertEquals(ConversionStage.Completed, lastStage)
            assertEquals(20, result.hqqIterations)
            assertEquals(expectedFiles, result.files)
            assertEquals(expectedFiles.map { it.name }.toSet(), checkNotNull(output.list()).toSet())
            expectedFiles.forEach { assertEquals(it.sizeBytes, File(output, it.name).length()) }
            assertFalse(workingDir.resolve("output.partial").exists())
            assertTrue(result.hqqComputeMillis > 0)
            Log.i("ConverterSmokeTest", "${result.files.size} files in ${result.elapsedMillis} ms, HQQ ${result.hqqComputeMillis} ms")
        } finally {
            workingDir.deleteRecursively()
        }
    }
}
