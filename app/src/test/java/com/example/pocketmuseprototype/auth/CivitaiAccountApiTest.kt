package com.example.pocketmuseprototype.auth

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.FormBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CivitaiAccountApiTest {
    @Test
    fun userInfoUsesBearerAndParsesSubjectAndUsername() = runTest {
        val api = api("""{"sub":"42","id":99,"username":"creator","email_verified":false}""") { request ->
            assertEquals("GET", request.method)
            assertEquals("/api/auth/oauth/userinfo", request.url.encodedPath)
            assertEquals("Bearer generated-access", request.header("Authorization"))
        }
        assertEquals(CivitaiProfile("42", "creator"), api.userInfo("generated-access"))
    }

    @Test
    fun userInfoAcceptsNumericIdAndPreferredUsernameAndRejectsMissingUsername() = runTest {
        val api = api("""{"id":42,"username":" ","preferred_username":"creator"}""")
        assertEquals(CivitaiProfile("42", "creator"), api.userInfo("generated-access"))
        val failure = runCatching { api("""{"sub":"42"}""").userInfo("generated-access") }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun unauthorizedUserInfoIsDifferentFromATemporaryHttpFailure() = runTest {
        val rejected = runCatching { api("{}", 401).userInfo("generated-access") }.exceptionOrNull()
        val temporary = runCatching { api("{}", 503).userInfo("generated-access") }.exceptionOrNull()
        assertTrue(rejected is SessionRejected)
        assertTrue(temporary is IOException && temporary !is SessionRejected)
    }

    @Test
    fun revokeSendsRefreshTokenAndPublicClientIdInForm() = runTest {
        val api = api("{}") { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/auth/oauth/revoke", request.url.encodedPath)
            val body = request.body as FormBody
            assertEquals(
                mapOf("token" to "generated-refresh", "token_type_hint" to "refresh_token", "client_id" to CIVITAI_CLIENT_ID),
                (0 until body.size).associate { body.name(it) to body.value(it) }
            )
        }
        api.revoke("generated-refresh")
    }

    private fun api(body: String, status: Int = 200, inspect: (Request) -> Unit = {}): CivitaiAccountApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Synthetic response")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CivitaiAccountApi(client)
    }
}
