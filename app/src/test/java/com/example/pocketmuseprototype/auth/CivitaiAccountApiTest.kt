package com.example.pocketmuseprototype.auth

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
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
    fun userInfoTriesNextAddressWhenFirstConnectionFails() = runBlocking {
        val unavailable = InetAddress.getByName("::1")
        val reachable = InetAddress.getByName("127.0.0.1")
        val attempts = mutableListOf<InetAddress>()
        ServerSocket(0, 1, reachable).use { server ->
            server.soTimeout = 5_000
            val executor = Executors.newSingleThreadExecutor()
            val response = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    assertEquals("GET /api/auth/oauth/userinfo HTTP/1.1", reader.readLine())
                    while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                    val body = """{"sub":"42","username":"creator"}"""
                    socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray()
                    )
                }
            }
            val client = civitaiAccountHttpClient().newBuilder()
                .proxy(Proxy.NO_PROXY)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = listOf(unavailable, reachable)
                })
                .eventListener(object : EventListener() {
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        attempts += inetSocketAddress.address
                    }
                })
                .addInterceptor { chain ->
                    val request = chain.request()
                    chain.proceed(request.newBuilder().url(
                        request.url.newBuilder().scheme("http").host("account.test").port(server.localPort).build()
                    ).build())
                }
                .build()
            try {
                assertEquals(CivitaiProfile("42", "creator"), CivitaiAccountApi(client).userInfo("fake-access"))
                response.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(unavailable, reachable), attempts)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
                executor.shutdownNow()
            }
        }
    }

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
