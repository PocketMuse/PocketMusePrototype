package com.example.pocketmuseprototype.catalog

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CivitaiApiTest {
    @Test
    fun sendsPublicFiltersAndParsesVersionFileAndImageMetadata() = runTest {
        val api = api { chain ->
            val request = chain.request()
            assertEquals("/api/v1/models", request.url.encodedPath)
            assertEquals("Checkpoint", request.url.queryParameter("types"))
            assertEquals("SD 1.5", request.url.queryParameter("baseModels"))
            assertEquals("8", request.url.queryParameter("limit"))
            assertEquals("Most Downloaded", request.url.queryParameter("sort"))
            assertEquals("false", request.url.queryParameter("nsfw"))
            assertEquals("false", request.url.queryParameter("primaryFileOnly"))
            assertEquals("123|a+b&c", request.url.queryParameter("cursor"))
            assertNull(request.header("Authorization"))
            chain.response(200, MODEL_PAGE)
        }
        val page = api.models("123|a+b&c")
        assertEquals("789|111", page.metadata.nextCursor)
        val model = page.items.single()
        assertEquals("Standard", model.modelVersions?.single()?.baseModelType)
        assertEquals(
            CatalogCheckpoint(
                11, "Sample", 22, "v1", 33, "sample.safetensors", 1_953_125.25,
                "https://example.test/download/22", "TESTHASH", "https://example.test/cover.jpeg"
            ),
            model.selectCheckpoint()
        )
    }

    @Test
    fun acceptsAnAbsentOrNullNextCursorAsEnd() = runTest {
        for (metadata in listOf("{}", """{"nextCursor":null}""")) {
            val api = api { it.response(200, """{"items":[],"metadata":$metadata}""") }
            assertNull(api.models(null).metadata.nextCursor)
        }
    }

    @Test
    fun surfacesHttpTimeoutAndMalformedResponsesAsLoadErrors() = runTest {
        val badResponses = listOf(
            api { it.response(503, "{}") },
            api { throw SocketTimeoutException() },
            api { it.response(200, "<html>") },
            api { it.response(200, """{"metadata":{}}""") },
            api { it.response(200, """{"items":[],"metadata":{"nextCursor":{}}}""") },
            api { it.response(204, "") }
        )
        for (api in badResponses) {
            try {
                api.models(null)
                throw AssertionError("Expected a load error")
            } catch (_: IOException) {
            }
        }
    }

    private fun api(interceptor: Interceptor) =
        CivitaiApi(client = OkHttpClient.Builder().addInterceptor(interceptor).build())

    private fun Interceptor.Chain.response(status: Int, body: String): Response =
        Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(status)
            .message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()

    private companion object {
        const val MODEL_PAGE = """
            {
              "items": [{
                "id": 11, "name": "Sample", "type": "Checkpoint", "availability": "Public",
                "description": "Ignored extra field",
                "modelVersions": [{
                  "id": 22, "name": "v1", "baseModel": "SD 1.5", "baseModelType": "Standard",
                  "publishedAt": "2024-01-01T00:00:00.123Z",
                  "files": [{
                    "id": 33, "name": "sample.safetensors", "type": "Model", "sizeKB": 1953125.25,
                    "downloadUrl": "https://example.test/download/22",
                    "hashes": {"SHA256": "TESTHASH"},
                    "metadata": {"format": "SafeTensor", "size": "pruned", "fp": "fp16"}
                  }],
                  "images": [
                    {"type": "video", "url": "https://example.test/sample.mp4"},
                    {"type": "image", "url": "https://example.test/cover.jpeg"}
                  ]
                }]
              }],
              "metadata": {"nextCursor": "789|111"}
            }
        """
    }
}
