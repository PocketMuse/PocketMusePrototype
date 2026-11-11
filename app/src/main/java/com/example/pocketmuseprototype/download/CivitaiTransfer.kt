package com.example.pocketmuseprototype.download

import android.system.ErrnoException
import android.system.OsConstants
import com.example.pocketmuseprototype.catalog.FileDto
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

internal data class TransferProgress(val bytes: Long, val total: Long?) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

internal data class VerifiedCheckpoint(val bytes: Long, val sha256: String?)

/** Streams into the supplied partial file. The caller owns cleanup and publication. */
internal class CivitaiTransfer(
    client: OkHttpClient = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun download(
        model: LibraryModel,
        token: String,
        partial: File,
        progress: suspend (TransferProgress) -> Unit,
        verifying: suspend (Long) -> Unit
    ): VerifiedCheckpoint = withContext(Dispatchers.IO) {
        val file = resolveFile(model, token)
        val url = file.downloadUrl?.toHttpUrlOrNull()?.takeIf {
            it.isCivitaiOrigin() && it.encodedPath == "/api/download/models/${model.modelVersionId}" &&
                it.username.isEmpty() && it.password.isEmpty()
        } ?: throw DownloadFailure(DownloadError.InvalidMetadata)
        val pinnedUrl = url.newBuilder().removeAllQueryParameters("token")
            .setQueryParameter("fileId", model.fileId.toString()).fragment(null).build()
        val latestHash = file.hashes?.sha256?.checkedHash()
        val originalHash = model.sha256?.checkedHash()
        if (latestHash != null && originalHash != null && latestHash != originalHash) {
            throw DownloadFailure(DownloadError.InvalidMetadata)
        }
        val expectedHash = latestHash ?: originalHash
        val digest = expectedHash?.let { MessageDigest.getInstance("SHA-256") }
        val bytes = response(pinnedUrl, token) { response ->
            val body = response.requireBody()
            val total = body.contentLength().takeIf { it >= 0 }
            val directory = checkNotNull(partial.parentFile)
            if (total == 0L) throw DownloadFailure(DownloadError.InvalidLength)
            if (directory.usableSpace < (total ?: 1)) throw DownloadFailure(DownloadError.NoSpace)
            progress(TransferProgress(0, total))
            var count = 0L
            var lastUpdate = nowMillis()
            val buffer = ByteArray(128 * 1024)
            val input = body.byteStream()
            val output = fileIo(directory) { FileOutputStream(partial) }
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = try {
                        input.read(buffer)
                    } catch (_: java.net.ProtocolException) {
                        throw DownloadFailure(DownloadError.InvalidLength)
                    }
                    if (read == -1) break
                    count += read
                    if (total != null && count > total) throw DownloadFailure(DownloadError.InvalidLength)
                    fileIo(directory, read.toLong()) { output.write(buffer, 0, read) }
                    digest?.update(buffer, 0, read)
                    val now = nowMillis()
                    if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                        progress(TransferProgress(count, total))
                        lastUpdate = now
                    }
                }
                if (count == 0L || (total != null && count != total)) {
                    throw DownloadFailure(DownloadError.InvalidLength)
                }
                fileIo(directory) { output.fd.sync() }
            } finally {
                fileIo(directory) { output.close() }
            }
            count
        }
        currentCoroutineContext().ensureActive()
        verifying(bytes)
        if (digest != null && !digest.digest().toHexString().equals(expectedHash, ignoreCase = true)) {
            throw DownloadFailure(DownloadError.InvalidHash)
        }
        if (!fileIo(checkNotNull(partial.parentFile)) { partial.length() == bytes && partial.hasCheckpointHeader() }) {
            throw DownloadFailure(DownloadError.InvalidCheckpoint)
        }
        VerifiedCheckpoint(bytes, expectedHash)
    }

    suspend fun thumbnail(url: String): ByteArray = withContext(Dispatchers.IO) {
        val source = url.toHttpUrlOrNull()?.takeIf { it.isSafeHttps() }
            ?: throw DownloadFailure(DownloadError.InvalidMetadata)
        response(source, timeoutSeconds = 15) { response -> response.requireBody().readLimited(MAX_IMAGE_BYTES) }
    }

    private suspend fun resolveFile(model: LibraryModel, token: String): FileDto =
        response("https://civitai.com/api/v1/model-versions/${model.modelVersionId}".toHttpUrl(), token, 30) {
            val version = try {
                json.decodeFromString<DownloadVersion>(it.requireBody().readLimited(MAX_METADATA_BYTES).decodeToString())
            } catch (_: SerializationException) {
                throw DownloadFailure(DownloadError.InvalidMetadata)
            }
            if (version.id != model.modelVersionId || version.modelId != model.modelId) {
                throw DownloadFailure(DownloadError.InvalidMetadata)
            }
            val file = version.files.singleOrNull { it.id == model.fileId }
                ?: throw DownloadFailure(DownloadError.NotFound)
            if (file.type != "Model" || file.metadata?.format != "SafeTensor" ||
                file.name?.endsWith(".safetensors", ignoreCase = true) != true
            ) throw DownloadFailure(DownloadError.InvalidMetadata)
            file
        }

    private suspend fun <T> response(
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

    @Serializable
    private data class DownloadVersion(val id: Long, val modelId: Long, val files: List<FileDto> = emptyList())

    private companion object {
        val redirectCodes = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5
        const val MAX_METADATA_BYTES = 2L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
        const val PROGRESS_INTERVAL_MS = 500L
    }
}

private fun HttpUrl.isCivitaiOrigin(): Boolean = isHttps && port == 443 && host == "civitai.com"
private fun HttpUrl.isSafeHttps(): Boolean = isHttps && port == 443 && username.isEmpty() && password.isEmpty()

internal fun String?.stableThumbnailUrl(): String? = this?.toHttpUrlOrNull()?.takeIf { it.isSafeHttps() }
    ?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()

private fun Response.requireBody(): ResponseBody {
    if (code != 200) throw DownloadFailure(when (code) {
        401 -> DownloadError.AuthRequired
        403 -> DownloadError.Forbidden
        404 -> DownloadError.NotFound
        429 -> DownloadError.RateLimited
        206 -> DownloadError.InvalidLength
        else -> DownloadError.Server
    })
    if (header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) {
        throw DownloadFailure(DownloadError.InvalidLength)
    }
    return body ?: throw DownloadFailure(DownloadError.InvalidLength)
}

private fun ResponseBody.readLimited(limit: Long): ByteArray {
    val source = source()
    if (contentLength() > limit || source.request(limit + 1)) throw DownloadFailure(DownloadError.InvalidMetadata)
    return source.readByteArray()
}

private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

private fun String.checkedHash(): String {
    if (!matches(sha256Pattern)) throw DownloadFailure(DownloadError.InvalidMetadata)
    return lowercase()
}

private inline fun <T> fileIo(directory: File, writeBytes: Long = 1, block: () -> T): T = try {
    block()
} catch (error: IOException) {
    val noSpace = (error.cause as? ErrnoException)?.errno == OsConstants.ENOSPC || directory.usableSpace < writeBytes
    throw DownloadFailure(if (noSpace) DownloadError.NoSpace else DownloadError.FileWrite)
}
