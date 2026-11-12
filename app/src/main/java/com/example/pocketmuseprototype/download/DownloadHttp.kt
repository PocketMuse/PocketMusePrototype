package com.example.pocketmuseprototype.download

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class DownloadHttp(client: OkHttpClient) {
    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun <T> response(
        url: HttpUrl,
        token: String? = null,
        timeoutSeconds: Long = 0,
        consume: suspend (Response) -> T
    ): T {
        var destination = url
        var bearer = token
        repeat(MAX_REDIRECTS + 1) { hop ->
            val request = Request.Builder().url(destination).header("Accept-Encoding", "identity")
            if (bearer != null && destination.isCivitaiOrigin()) {
                request.header("Authorization", "Bearer $bearer")
            }
            val redirect = coroutineScope {
                val call = http.newCall(request.build())
                if (timeoutSeconds > 0) call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
                // Cancellation must interrupt a blocked read, and response.use must finish before returning.
                val closeCall = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response ->
                        if (response.code in redirectCodes) {
                            response.header("Location")?.let(destination::resolve)?.takeIf { it.isSafeHttps() }
                                ?: throw DownloadFailure(DownloadError.InvalidMetadata)
                        } else {
                            return@coroutineScope ResponseStep.Done(consume(response))
                        }.let { ResponseStep.Redirect(it) }
                    }
                } finally {
                    closeCall.cancel()
                }
            }
            when (redirect) {
                is ResponseStep.Done -> return redirect.value
                is ResponseStep.Redirect -> {
                    if (hop == MAX_REDIRECTS) throw DownloadFailure(DownloadError.Server)
                    if (redirect.url.host != destination.host) bearer = null
                    destination = redirect.url
                }
            }
        }
        error("Redirect limit exceeded")
    }

    private sealed interface ResponseStep<out T> {
        data class Done<T>(val value: T) : ResponseStep<T>
        data class Redirect(val url: HttpUrl) : ResponseStep<Nothing>
    }

    private companion object {
        val redirectCodes = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5
    }
}
