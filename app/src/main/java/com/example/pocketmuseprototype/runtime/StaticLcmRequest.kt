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

@Keep
internal fun interface GenerationPreview {
    /** Delivers owned 512×512 HWC RGB8 bytes on the generation worker, in step order. */
    fun update(step: Int, total: Int, width: Int, height: Int, rgb: ByteArray, elapsedNanos: Long)
}

/**
 * One static LCM request. Keep the model, tokenizer, and optional cache and TAESD paths
 * valid until return. Uses 2, 4, 6 or 8 denoising steps. A seed of -1 lets Runtime choose.
 * Runtime manages disk cache access. A null or invalid cache path allows uncached execution.
 */
internal class StaticLcmRequest(
    private val modelDir: File,
    private val tokenizerDir: File,
    private val prompt: String,
    private val cacheDir: File?,
    private val steps: Int = DEFAULT_STATIC_LCM_STEPS,
    private val previewDecoder: File? = null,
    private val seed: Long = -1L,
) {
    init {
        require(steps in StaticLcmSteps) { "Unsupported static LCM step count: $steps" }
    }

    private val lock = Any()
    private var started = false
    private var cancelled = false
    private var token = 0L

    /** Idempotent, including before execution and from either callback. */
    fun requestCancel() {
        synchronized(lock) {
            cancelled = true
            if (token != 0L) NativeRuntime.requestCancel(token)
        }
    }

    /**
     * Runs on an I/O worker and waits for native cleanup before propagating coroutine cancellation.
     * Pass [onPreview] with a TAESD file to receive one preview per step.
     * Exceptions from either callback propagate after native cleanup.
     */
    suspend fun generate(
        onPreview: GenerationPreview? = null,
        onProgress: GenerationProgress? = null,
    ): GeneratedImage = supervisorScope {
        val native = async(Dispatchers.IO) { run(onPreview, onProgress) }
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
    private fun run(onPreview: GenerationPreview?, onProgress: GenerationProgress?): GeneratedImage {
        val model = modelDir.nativePath()
        val tokenizer = tokenizerDir.nativePath()
        val cache = cacheDir?.takeIf { it.isNativePath() }?.path.orEmpty()
        require((onPreview == null) == (previewDecoder == null)) { "TAESD file and callback must be paired" }
        val preview = previewDecoder?.nativePath().orEmpty()
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
            return NativeRuntime.generate(handle, model, tokenizer, cache, preview, steps, seed, utf8Prompt, onProgress, onPreview)
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
        previewDecoder: String,
        steps: Int,
        seed: Long,
        prompt: ByteArray,
        onProgress: GenerationProgress?,
        onPreview: GenerationPreview?,
    ): GeneratedImage
}
