package com.example.pocketmuseprototype.runtime

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.IOException
import androidx.annotation.MainThread
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pocketmuseprototype.PocketMuseApplication
import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.download.StaticLcmOutputUnavailableException
import com.example.pocketmuseprototype.ui.ChatGeneration
import com.example.pocketmuseprototype.ui.ChatGeneration.Status
import com.example.pocketmuseprototype.ui.ChatImageRequest
import com.example.pocketmuseprototype.ui.ChatPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_PROMPT_UTF8_BYTES = 16 * 1024

internal class ImageGenerationViewModel(
    private val saveImage: suspend (Bitmap, ChatImageRequest) -> File,
    private val runGeneration: suspend (ChatImageRequest, GenerationProgress, GenerationPreview) -> GeneratedImage,
) : ViewModel() {
    private val mutableState = MutableStateFlow<List<ChatGeneration>>(emptyList())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var nextId = 0L

    @MainThread
    fun generate(request: ChatImageRequest) {
        if (job?.isCompleted == false || request.steps !in StaticLcmSteps || request.prompt.isBlank()) return
        val id = nextId++
        if (request.prompt.length > MAX_PROMPT_UTF8_BYTES ||
            request.prompt.encodeToByteArray().size > MAX_PROMPT_UTF8_BYTES
        ) {
            append(ChatGeneration(id, request.prompt, Status.Failed(R.string.chat_prompt_too_long)))
            return
        }
        append(ChatGeneration(id, request.prompt, Status.Running()))
        job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var result: Status = Status.Cancelled
            try {
                ensureActive()
                val progress = GenerationProgress { stage, step, total, _ ->
                    updateProgress(id, stage, step, total)
                }
                val preview = GenerationPreview { step, total, width, height, rgb, _ ->
                    updatePreview(id, step, total, width, height, rgb)
                }
                val image = runGeneration(request, progress, preview)
                val bitmap = withContext(Dispatchers.Default) { image.toBitmap() }
                ensureActive()
                updateLatest(id) { if (it is Status.Running) Status.Saving(it.preview) else it }
                result = try {
                    Status.Complete(saveImage(bitmap, request))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Status.SaveFailed(bitmap.asImageBitmap())
                } catch (_: OutOfMemoryError) {
                    Status.SaveFailed(bitmap.asImageBitmap())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaticLcmOutputUnavailableException) {
                result = Status.Failed(R.string.chat_model_unavailable)
            } catch (_: LinkageError) {
                result = Status.Failed(R.string.chat_generation_unavailable)
            } catch (_: OutOfMemoryError) {
                result = Status.Failed(R.string.chat_generation_memory_error)
            } catch (_: Exception) {
                result = Status.Failed(R.string.chat_generation_failed)
            } finally {
                // The model lease and JNI call have both returned before this state is published.
                updateLatest(id) { if (it == Status.Cancelling) Status.Cancelled else result }
            }
        }
    }

    @MainThread
    fun cancel() {
        val current = mutableState.value.lastOrNull() ?: return
        if (current.status !is Status.Running) return
        updateLatest(current.id) { Status.Cancelling }
        job?.cancel()
    }

    @MainThread
    fun imageDeleted(id: String) {
        mutableState.update { messages ->
            messages.map { message ->
                val status = message.status
                if (status is Status.Complete && status.file.nameWithoutExtension == id) {
                    message.copy(status = Status.Failed(R.string.gallery_image_unavailable))
                } else message
            }
        }
    }

    private fun updateProgress(id: Long, stage: String, step: Int, total: Int) {
        val progress = when (stage) {
            "text_encoder" -> Status.Running(R.string.chat_encoding_prompt)
            "unet" -> Status.Running(R.string.chat_generating)
            "denoising" -> Status.Running(R.string.chat_denoising, step, total)
            "vae_decoder" -> Status.Running(R.string.chat_finishing_image)
            else -> return
        }
        updateLatest(id) { if (it is Status.Running) progress.copy(preview = it.preview) else it }
    }

    private fun updatePreview(id: Long, step: Int, total: Int, width: Int, height: Int, rgb: ByteArray) {
        val message = mutableState.value.lastOrNull() ?: return
        val running = message.status as? Status.Running ?: return
        if (message.id != id || step <= (running.preview?.step ?: 0)) return
        val preview = ChatPreview(step, total, rgb.toBitmap(width, height).asImageBitmap())
        updateLatest(id) {
            if (it is Status.Running && step > (it.preview?.step ?: 0)) it.copy(preview = preview) else it
        }
    }

    private fun append(message: ChatGeneration) {
        mutableState.update { messages ->
            messages.map { previous ->
                if (previous.status is Status.SaveFailed && previous.status.image != null) {
                    previous.copy(status = Status.SaveFailed())
                } else previous
            } + message
        }
    }

    private fun updateLatest(id: Long, update: (Status) -> Status) {
        mutableState.update { messages ->
            val latest = messages.lastOrNull() ?: return@update messages
            if (latest.id != id) return@update messages
            val status = update(latest.status)
            if (status == latest.status) messages else messages.dropLast(1) + latest.copy(status = status)
        }
    }

    companion object {
        fun factory(app: PocketMuseApplication) = viewModelFactory {
            initializer {
                ImageGenerationViewModel(saveImage = { bitmap, request ->
                    app.gallery.save(bitmap, request.modelName, request.prompt, request.steps).file
                }) { request, progress, preview ->
                    val tokenizer = BundledClipTokenizer.prepare(app)
                    app.downloads.withStaticLcmOutput(request.modelKey) { _, model, cache ->
                        val decoder = try {
                            BundledTaesd.prepare(app)
                        } catch (error: IOException) {
                            Log.w("ImageGeneration", "TAESD preparation failed", error)
                            null
                        }
                        StaticLcmRequest(model, tokenizer, request.prompt, cache, request.steps, decoder)
                            .generate(onPreview = preview.takeIf { decoder != null }, onProgress = progress)
                    }
                }
            }
        }
    }
}

/** Converts owned HWC RGB8 bytes into an opaque Bitmap. Call on a worker thread. */
internal fun GeneratedImage.toBitmap(): Bitmap = rgb.toBitmap(width, height)

private fun ByteArray.toBitmap(width: Int, height: Int): Bitmap {
    require(width == 512 && height == 512 && size == width * height * 3)
    val pixels = IntArray(width * height) { index ->
        val offset = index * 3
        val red = this[offset].toInt() and 0xff
        val green = this[offset + 1].toInt() and 0xff
        val blue = this[offset + 2].toInt() and 0xff
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
