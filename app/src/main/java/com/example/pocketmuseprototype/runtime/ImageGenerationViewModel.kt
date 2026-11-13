package com.example.pocketmuseprototype.runtime

import android.graphics.Bitmap
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
    private val runGeneration: suspend (ChatImageRequest, GenerationProgress) -> Status.Complete,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ChatGeneration?>(null)
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    @MainThread
    fun generate(request: ChatImageRequest) {
        if (job?.isCompleted == false || request.steps !in StaticLcmSteps || request.prompt.isBlank()) return
        if (request.prompt.length > MAX_PROMPT_UTF8_BYTES ||
            request.prompt.encodeToByteArray().size > MAX_PROMPT_UTF8_BYTES
        ) {
            mutableState.value = ChatGeneration(request.prompt, Status.Failed(R.string.chat_prompt_too_long))
            return
        }
        mutableState.value = ChatGeneration(request.prompt, Status.Running())
        job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var result: Status = Status.Cancelled
            try {
                ensureActive()
                val complete = runGeneration(request, ::updateProgress)
                ensureActive()
                result = complete
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
                mutableState.value = ChatGeneration(
                    request.prompt,
                    if (mutableState.value?.status == Status.Cancelling) Status.Cancelled else result,
                )
            }
        }
    }

    @MainThread
    fun cancel() {
        mutableState.update { current ->
            if (current?.status is Status.Running) current.copy(status = Status.Cancelling) else current
        }
        if (mutableState.value?.status == Status.Cancelling) job?.cancel()
    }

    private fun updateProgress(stage: String, step: Int, total: Int, elapsedNanos: Long) {
        val progress = when (stage) {
            "text_encoder" -> Status.Running(R.string.chat_encoding_prompt)
            "unet" -> Status.Running(R.string.chat_generating)
            "denoising" -> Status.Running(R.string.chat_denoising, step, total)
            "vae_decoder" -> Status.Running(R.string.chat_finishing_image)
            else -> return
        }
        mutableState.update { current ->
            if (current?.status is Status.Running) current.copy(status = progress) else current
        }
    }

    companion object {
        fun factory(app: PocketMuseApplication) = viewModelFactory {
            initializer {
                ImageGenerationViewModel { request, progress ->
                    val tokenizer = BundledClipTokenizer.prepare(app)
                    val image = app.downloads.withStaticLcmOutput(request.modelKey) { _, model, cache ->
                        StaticLcmRequest(model, tokenizer, request.prompt, cache, request.steps).generate(progress)
                    }
                    withContext(Dispatchers.Default) {
                        Status.Complete(image.toBitmap().asImageBitmap(), image.seed)
                    }
                }
            }
        }
    }
}

/** Converts owned HWC RGB8 bytes into an opaque Bitmap. Call on a worker thread. */
internal fun GeneratedImage.toBitmap(): Bitmap {
    require(width == 512 && height == 512 && rgb.size == width * height * 3)
    val pixels = IntArray(width * height) { index ->
        val offset = index * 3
        val red = rgb[offset].toInt() and 0xff
        val green = rgb[offset + 1].toInt() and 0xff
        val blue = rgb[offset + 2].toInt() and 0xff
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
