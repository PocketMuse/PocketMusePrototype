package com.example.pocketmuseprototype.runtime

import androidx.annotation.Keep
import androidx.annotation.WorkerThread
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

@Keep
internal data class GeneratedImage(
    val seed: Long,
    val width: Int,
    val height: Int,
    val rgb: ByteArray,
    val runtimeNanos: Long,
    val generateNanos: Long,
)

@Keep
internal class RuntimeInitializationException(message: String) : IllegalStateException(message)

@Keep
internal class RuntimeGenerationException(message: String) : IllegalStateException(message)

@Keep
internal fun interface GenerationProgress {
    /** Reports the upstream stage and step on the calling worker, with elapsed generate time. */
    fun update(stage: String, step: Int, total: Int, elapsedNanos: Long)
}

/** One static LCM request. The caller keeps the model and tokenizer paths valid until return. */
internal class StaticLcmRequest(
    private val modelDir: File,
    private val tokenizerDir: File,
    private val prompt: String,
) {
    private val lock = Any()
    private var started = false
    private var cancelled = false
    private var token = 0L

    /** Idempotent, including before execution and from a progress callback. */
    fun requestCancel() {
        synchronized(lock) {
            cancelled = true
            if (token != 0L) NativeRuntime.requestCancel(token)
        }
    }

    /** Runs on an I/O worker and waits for native cleanup before propagating coroutine cancellation. */
    suspend fun generate(onProgress: GenerationProgress? = null): GeneratedImage = supervisorScope {
        val native = async(Dispatchers.IO) { run(onProgress) }
        try {
            native.await()
        } finally {
            if (!native.isCompleted) {
                requestCancel()
                withContext(NonCancellable) { native.join() }
            }
        }
    }

    /**
     * Runs synchronously through native cleanup. Returns owned 512×512 HWC RGB8 bytes.
     * Throws CancellationException when native acknowledges cancellation, or propagates
     * initialization, generation, linkage, allocation and callback exceptions.
     */
    @WorkerThread
    private fun run(onProgress: GenerationProgress?): GeneratedImage {
        val model = modelDir.nativePath()
        val tokenizer = tokenizerDir.nativePath()
        val utf8Prompt = prompt.encodeToByteArray(throwOnInvalidSequence = true)
        val handle = synchronized(lock) {
            check(!started) { "A generation request can run only once" }
            started = true
            NativeRuntime.createToken().also {
                token = it
                if (cancelled) NativeRuntime.requestCancel(it)
            }
        }
        try {
            return NativeRuntime.generate(handle, model, tokenizer, utf8Prompt, onProgress)
        } finally {
            synchronized(lock) {
                token = 0L
                NativeRuntime.destroyToken(handle)
            }
        }
    }

    private fun File.nativePath(): String {
        require(isAbsolute && path.all { it in ' '..'~' }) { "Expected an absolute ASCII path" }
        return path
    }
}

@Keep
private object NativeRuntime {
    init {
        System.loadLibrary("pocketmuse_runtime")
    }

    external fun createToken(): Long
    external fun requestCancel(token: Long)
    external fun destroyToken(token: Long)
    external fun generate(
        token: Long,
        modelDir: String,
        tokenizerDir: String,
        prompt: ByteArray,
        onProgress: GenerationProgress?,
    ): GeneratedImage
}
