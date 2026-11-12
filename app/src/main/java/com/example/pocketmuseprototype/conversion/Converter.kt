package com.example.pocketmuseprototype.conversion

import androidx.annotation.Keep
import androidx.annotation.WorkerThread
import java.io.File
import java.nio.charset.CharacterCodingException

/**
 * Inputs use absolute filesystem paths. The template contains graph.bin and manifest.json.
 *
 * @property hqqIterations Runtime HQQ refinement passes. The app defaults to min/max quantization
 * with zero refinement passes. The template recipe stays fixed.
 * @property lcmLora The official SD1.5 LCM-LoRA safetensors file to merge into a compatible
 * HQQ B128 template. The Converter validates the file. Null selects standard conversion.
 */
data class ConversionOptions(
    val checkpoint: File,
    val templateDir: File,
    val outputDir: File,
    val chunkBytes: Int = 1024 * 1024,
    val hqqIterations: Int = 0,
    val threadCount: Int = 1,
    val lcmLora: File? = null,
)

enum class ConversionStatus { Succeeded, Cancelled, Failed }

enum class ConversionStage { Validating, Converting, Finalizing, Completed }

enum class ConversionErrorCode {
    NONE, INVALID_INPUT, INCOMPATIBLE_TEMPLATE, TENSOR_MISMATCH, OUTPUT_EXISTS, STALE_OUTPUT,
    BUSY, READ_FAILED, WRITE_FAILED, INSUFFICIENT_SPACE, NON_FINITE_WEIGHT, OUT_OF_MEMORY,
    CALLBACK_FAILED, CLEANUP_FAILED, INTERNAL_ERROR,
}

data class ConversionProgress(
    val stage: ConversionStage,
    val component: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val fraction: Double,
    val elapsedMillis: Long,
    val remainingMillis: Long?,
)

data class ConvertedFile(val name: String, val sizeBytes: Long)

@Keep
data class ConversionResult(
    val status: ConversionStatus,
    val errorCode: ConversionErrorCode,
    val files: List<ConvertedFile> = emptyList(),
    val elapsedMillis: Long = 0,
    val hqqIterations: Int? = null,
    val hqqComputeMillis: Double = 0.0,
)

/** Owns one conversion and its cancellation token. Make a new request for each retry. */
class ConversionRequest(private val options: ConversionOptions) {
    private val lock = Any()
    private var started = false
    private var cancellationRequested = false
    private var token = 0L

    /** Thread-safe and idempotent. [run] reports the terminal status after native cleanup. */
    fun requestCancel() {
        synchronized(lock) {
            cancellationRequested = true
            if (token != 0L) NativeConverter.requestCancel(token)
        }
    }

    /**
     * Blocks the calling worker thread until native work and cleanup finish. Progress is delivered
     * on that thread. A callback exception before publication produces CALLBACK_FAILED unless
     * cleanup fails. Completion callbacks run after publication and cannot change success.
     *
     * Keep the inputs unchanged until return. Own the output and its .partial sibling throughout
     * the call. The output parent must already exist. Use files only after Succeeded. Concurrent
     * conversion or cleanup returns BUSY. JNI linkage or JVM allocation failures can throw.
     * Coroutine cancellation must be forwarded explicitly via [requestCancel].
     */
    @WorkerThread
    fun run(onProgress: ((ConversionProgress) -> Unit)? = null): ConversionResult {
        synchronized(lock) {
            check(!started) { "A conversion request can run only once" }
            started = true
        }
        val checkpoint = options.checkpoint.toNativePath() ?: return failed(ConversionErrorCode.INVALID_INPUT)
        val templateDir = options.templateDir.toNativePath() ?: return failed(ConversionErrorCode.INVALID_INPUT)
        val outputDir = options.outputDir.toNativePath() ?: return failed(ConversionErrorCode.INVALID_INPUT)
        val lcmLora = options.lcmLora?.let {
            it.toNativePath() ?: return failed(ConversionErrorCode.INVALID_INPUT)
        }
        val handle = NativeConverter.createToken()
        if (handle == 0L) return failed(ConversionErrorCode.OUT_OF_MEMORY)
        try {
            synchronized(lock) {
                token = handle
                if (cancellationRequested) NativeConverter.requestCancel(handle)
            }
            return NativeConverter.convert(
                handle, checkpoint, templateDir, outputDir, lcmLora, options.chunkBytes,
                options.hqqIterations, options.threadCount, onProgress != null, NativeCallbacks(onProgress),
            )
        } finally {
            synchronized(lock) {
                token = 0
                NativeConverter.destroyToken(handle)
            }
        }
    }

    private fun failed(code: ConversionErrorCode) = ConversionResult(ConversionStatus.Failed, code)

    companion object {
        /**
         * Cleans Converter-owned staging for an exclusively managed output path. NONE also means
         * staging was absent. The final directory and unrecognized staging files are preserved.
         */
        @WorkerThread
        fun cleanupPartial(outputDir: File): ConversionErrorCode {
            val path = outputDir.toNativePath() ?: return ConversionErrorCode.INVALID_INPUT
            return ConversionErrorCode.valueOf(NativeConverter.cleanupPartial(path))
        }
    }
}

private fun File.toNativePath(): ByteArray? {
    if (!isAbsolute || path == "/" || '\u0000' in path) return null
    return try {
        path.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

@Keep
private object NativeConverter {
    init {
        System.loadLibrary("pocketmuse_converter")
    }

    external fun createToken(): Long
    external fun requestCancel(token: Long)
    external fun destroyToken(token: Long)
    external fun convert(
        token: Long,
        checkpoint: ByteArray,
        templateDir: ByteArray,
        outputDir: ByteArray,
        lcmLora: ByteArray?,
        chunkBytes: Int,
        hqqIterations: Int,
        threadCount: Int,
        observeProgress: Boolean,
        callbacks: NativeCallbacks,
    ): ConversionResult

    external fun cleanupPartial(outputDir: ByteArray): String
}

@Keep
private class NativeCallbacks(private val onProgress: ((ConversionProgress) -> Unit)?) {
    private val files = mutableListOf<ConvertedFile>()

    fun progress(
        stage: Int,
        component: String,
        completedBytes: Long,
        totalBytes: Long,
        fraction: Double,
        elapsedMillis: Long,
        remainingMillis: Long,
    ) {
        onProgress?.invoke(
            ConversionProgress(
                ConversionStage.entries[stage], component, completedBytes, totalBytes, fraction,
                elapsedMillis, remainingMillis.takeIf { it >= 0 },
            ),
        )
    }

    fun addFile(name: String, sizeBytes: Long) {
        files += ConvertedFile(name, sizeBytes)
    }

    fun finish(
        status: Int,
        errorCode: String,
        elapsedMillis: Long,
        hqqIterations: Int,
        hqqComputeMillis: Double,
    ) = ConversionResult(
        ConversionStatus.entries[status], ConversionErrorCode.valueOf(errorCode), files.toList(),
        elapsedMillis, hqqIterations.takeIf { it >= 0 }, hqqComputeMillis,
    )
}
