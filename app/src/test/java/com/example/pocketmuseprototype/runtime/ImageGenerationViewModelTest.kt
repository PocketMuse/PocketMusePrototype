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
import org.junit.Assert.assertTrue
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
            val viewModel = ImageGenerationViewModel(saveImage = { _, _ -> error("No result to save") }) { request, progress, _ ->
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
            val first = ChatImageRequest("1_2", "Garden model", 6, "정원")
            val second = ChatImageRequest("3_4", "Sky model", 2, "Sky")
            viewModel.generate(first.copy(steps = 3))
            assertTrue(viewModel.state.value.isEmpty())
            val tooLong = first.copy(prompt = "정".repeat(6_000))
            viewModel.generate(tooLong)
            assertEquals(Status.Failed(R.string.chat_prompt_too_long), viewModel.state.value.last().status)
            viewModel.generate(first)
            viewModel.generate(second)
            assertEquals(listOf(first), requests)
            assertEquals(Status.Running(), viewModel.state.value.last().status)
            callback.update("denoising", 2, 6, 0)
            assertEquals(Status.Running(R.string.chat_denoising, 2, 6), viewModel.state.value.last().status)

            viewModel.cancel()
            runCurrent()
            callback.update("vae_decoder", 0, 6, 0)
            viewModel.generate(second)
            assertEquals(listOf(first), requests)
            assertEquals(Status.Cancelling, viewModel.state.value.last().status)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(Status.Cancelled, viewModel.state.value.last().status)

            viewModel.generate(second)
            runCurrent()
            assertEquals(listOf(first, second), requests)
            assertEquals(listOf(tooLong.prompt, first.prompt, second.prompt), viewModel.state.value.map { it.prompt })
            assertEquals(Status.Cancelled, viewModel.state.value[1].status)
            assertEquals(Status.Failed(R.string.chat_model_unavailable), viewModel.state.value.last().status)
            val freshSession = ImageGenerationViewModel(saveImage = { _, _ -> error("No result to save") }) { _, _, _ ->
                error("No generation requested")
            }
            assertTrue(freshSession.state.value.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
