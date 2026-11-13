package com.example.pocketmuseprototype.runtime

import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.download.StaticLcmOutputUnavailableException
import com.example.pocketmuseprototype.ui.ChatGeneration.Status
import com.example.pocketmuseprototype.ui.ChatImageRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ImageGenerationViewModelTest {
    @Test
    fun rejectsUnsupportedStepsBeforeLoadingNativeRuntime() {
        val unused = File("unused")
        for (steps in listOf(0, 3, 5, 7, 10)) {
            assertThrows(IllegalArgumentException::class.java) {
                StaticLcmRequest(unused, unused, "prompt", cacheDir = null, steps = steps)
            }
        }
        for (steps in listOf(2, 4, 6, 8)) {
            StaticLcmRequest(unused, unused, "prompt", cacheDir = null, steps = steps)
        }
    }

    @Test
    fun keepsTheRequestBusyUntilCancellationCleanupReturns() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val requests = mutableListOf<ChatImageRequest>()
            val cleanup = CompletableDeferred<Unit>()
            lateinit var callback: GenerationProgress
            val viewModel = ImageGenerationViewModel { request, progress ->
                requests += request
                callback = progress
                if (requests.size == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { cleanup.await() }
                    }
                }
                throw StaticLcmOutputUnavailableException("internal model path")
            }
            val first = ChatImageRequest("1_2", 6, "정원")
            val second = ChatImageRequest("3_4", 2, "Sky")
            viewModel.generate(first.copy(steps = 3))
            assertNull(viewModel.state.value)
            viewModel.generate(first.copy(prompt = "정".repeat(6_000)))
            assertEquals(Status.Failed(R.string.chat_prompt_too_long), viewModel.state.value?.status)
            viewModel.generate(first)
            viewModel.generate(second)
            assertEquals(listOf(first), requests)
            assertEquals(Status.Running(), viewModel.state.value?.status)
            callback.update("denoising", 2, 6, 0)
            assertEquals(Status.Running(R.string.chat_denoising, 2, 6), viewModel.state.value?.status)

            viewModel.cancel()
            runCurrent()
            callback.update("vae_decoder", 0, 6, 0)
            viewModel.generate(second)
            assertEquals(listOf(first), requests)
            assertEquals(Status.Cancelling, viewModel.state.value?.status)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(Status.Cancelled, viewModel.state.value?.status)

            viewModel.generate(second)
            runCurrent()
            assertEquals(listOf(first, second), requests)
            assertEquals(second.prompt, viewModel.state.value?.prompt)
            assertEquals(Status.Failed(R.string.chat_model_unavailable), viewModel.state.value?.status)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
