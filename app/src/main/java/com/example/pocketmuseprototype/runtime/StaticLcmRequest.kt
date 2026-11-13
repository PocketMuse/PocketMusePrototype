package com.example.pocketmuseprototype.runtime

import androidx.annotation.Keep
import androidx.annotation.WorkerThread
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

internal val StaticLcmSteps = 2..8 step 2
internal const val DEFAULT_STATIC_LCM_STEPS = 4

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

/**
 * One static LCM request. Keep the model, tokenizer and optional cache paths valid until return.
 * Uses 2, 4, 6 or 8 denoising steps and lets the Runtime choose the seed.
 * Runtime manages disk cache access. A null or invalid cache path allows uncached execution.
 */
internal class StaticLcmRequest(
    private val modelDir: File,
    private val tokenizerDir: File,
    private val prompt: String,
    private val cacheDir: File?,
    private val steps: Int = DEFAULT_STATIC_LCM_STEPS,
) {
    init {
        require(steps in StaticLcmSteps) { "Unsupported static LCM step count: $steps" }
    }

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
        val cache = cacheDir?.takeIf { it.isNativePath() }?.path.orEmpty()
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
            return NativeRuntime.generate(handle, model, tokenizer, cache, steps, utf8Prompt, onProgress)
        } finally {
            synchronized(lock) {
                token = 0L
                NativeRuntime.destroyToken(handle)
            }
        }
    }

    private fun File.nativePath(): String {
        require(isNativePath()) { "Expected an absolute ASCII path" }
        return path
    }

    private fun File.isNativePath(): Boolean = isAbsolute && path.all { it in ' '..'~' }
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
        cacheDir: String,
        steps: Int,
        prompt: ByteArray,
        onProgress: GenerationProgress?,
    ): GeneratedImage
}
