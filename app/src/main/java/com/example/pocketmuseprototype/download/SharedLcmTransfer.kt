package com.example.pocketmuseprototype.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ProtocolException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

internal object Sd15LcmLora {
    const val ID = "sd15-lcm-lora-v1"
    const val WORK = "shared-$ID"
    const val FILE_NAME = "pytorch_lora_weights.safetensors"
    const val SIZE = 134_621_556L
    const val SHA256 = "8f90d840e075ff588a58e22c6586e2ae9a6f7922996ee6649a7f01072333afe4"
    private const val REVISION = "cf2fced511dbe7e26c8d1d397e728fbab875db4b"
    val url = "https://huggingface.co/latent-consistency/lcm-lora-sdv1-5/resolve/$REVISION/$FILE_NAME".toHttpUrl()
}

internal class SharedLcmTransfer(
    client: OkHttpClient = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
    private val size: Long = Sd15LcmLora.SIZE,
    private val sha256: String = Sd15LcmLora.SHA256,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val http = DownloadHttp(client)

    /** Called on an I/O dispatcher with exclusive ownership of [partial]. */
    suspend fun download(partial: File, progress: suspend (SharedLcmState) -> Unit) {
        val directory = checkNotNull(partial.parentFile)
        if (directory.usableSpace < size) throw DownloadFailure(DownloadError.NoSpace)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            http.response(Sd15LcmLora.url) { response ->
                val body = response.requireBody()
                val length = body.contentLength()
                if (length >= 0 && length != size) throw DownloadFailure(DownloadError.InvalidLength)
                val input = body.byteStream()
                val output = fileIo(directory) { FileOutputStream(partial) }
                try {
                    val buffer = ByteArray(128 * 1024)
                    var bytes = 0L
                    var lastUpdate = nowMillis()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        bytes += read
                        if (bytes > size) throw DownloadFailure(DownloadError.InvalidLength)
                        fileIo(directory, read.toLong()) { output.write(buffer, 0, read) }
                        digest.update(buffer, 0, read)
                        val now = nowMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            progress(SharedLcmState.Downloading(bytes))
                            lastUpdate = now
                        }
                    }
                    if (bytes != size) throw DownloadFailure(DownloadError.InvalidLength)
                    fileIo(directory) { output.fd.sync() }
                } finally {
                    fileIo(directory) { output.close() }
                }
            }
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (_: ProtocolException) {
            throw DownloadFailure(DownloadError.InvalidLength)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw DownloadFailure(DownloadError.Network)
        }
        progress(SharedLcmState.Verifying)
        currentCoroutineContext().ensureActive()
        if (digest.digest().toHexString() != sha256) throw DownloadFailure(DownloadError.InvalidHash)
    }
}
