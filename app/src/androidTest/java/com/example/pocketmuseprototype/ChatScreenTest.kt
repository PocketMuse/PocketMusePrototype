package com.example.pocketmuseprototype

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.auth.AuthState
import com.example.pocketmuseprototype.catalog.CatalogRepository
import com.example.pocketmuseprototype.catalog.MarketViewModel
import com.example.pocketmuseprototype.download.DownloadStatus
import com.example.pocketmuseprototype.download.LEGACY_HQQ_RECIPE
import com.example.pocketmuseprototype.download.LibraryItem
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.ModelConversionStatus
import com.example.pocketmuseprototype.download.STATIC_LCM_RECIPE
import com.example.pocketmuseprototype.ui.ChatGeneration
import com.example.pocketmuseprototype.ui.ChatImageRequest
import com.example.pocketmuseprototype.ui.ChatScreen
import com.example.pocketmuseprototype.ui.PocketMuseApp
import com.example.pocketmuseprototype.ui.theme.PocketMusePrototypeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectorShowsOnlyConvertedStaticLcmModelsAndTracksLibraryChanges() {
        val first = model(1, "ToonYou")
        val second = model(2, "Another checkpoint")
        val library = mutableStateOf(
            listOf(
                first, second,
                model(3, "Legacy").copy(conversionRecipeId = LEGACY_HQQ_RECIPE),
                model(4, "Unconverted").copy(conversionStatus = ModelConversionStatus.Converting),
                model(5, "Incomplete").copy(downloadStatus = DownloadStatus.Downloading)
            ).map { LibraryItem(it, null) }
        )
        launchApp(library = { library.value })

        compose.onNodeWithText(string(R.string.chat_select_model)).performClick()
        compose.onNodeWithText(first.modelName).assertIsDisplayed()
        compose.onNodeWithText("Legacy").assertDoesNotExist()
        compose.onNodeWithText("Unconverted").assertDoesNotExist()
        compose.onNodeWithText("Incomplete").assertDoesNotExist()
        compose.onNodeWithText(second.modelName).performClick()
        compose.onNodeWithText(string(R.string.chat_models_title)).assertDoesNotExist()

        compose.onNodeWithText(second.modelName).performClick()
        compose.onNodeWithText(second.versionName).assertIsSelected()
        compose.onNodeWithText(string(R.string.chat_close_models)).performClick()
        compose.onNodeWithText(second.modelName).assertIsDisplayed()

        compose.runOnIdle { library.value = listOf(LibraryItem(first, null)) }
        compose.onNodeWithText(first.modelName).assertIsDisplayed()
        compose.runOnIdle { library.value = emptyList() }
        compose.onNodeWithText(string(R.string.chat_select_model)).performClick()
        compose.onNodeWithText(string(R.string.chat_models_empty)).assertIsDisplayed()
    }

    @Test
    fun stepPopupUpdatesTheComposerAndPreservesTheMultilineDraftAcrossTabs() {
        launchApp()
        val step = compose.onNodeWithContentDescription(string(R.string.chat_steps))
        step.performClick()
        sliderAt(4).assertIsDisplayed()
        for (value in listOf(2, 6, 8, 4, 6)) {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
            sliderAt(value).assertIsDisplayed()
            compose.onNode(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, string(R.string.chat_step_count, value)
            ) and hasContentDescription(string(R.string.chat_steps)))
                .assertIsDisplayed()
        }
        tapOnScreen(compose.onNodeWithText(string(R.string.chat_generation_unavailable)))
        sliderAt(6).assertDoesNotExist()
        step.performClick()
        tapOnScreen(step)
        sliderAt(6).assertDoesNotExist()

        compose.onNode(hasSetTextAction()).performTextInput("Moonlit garden\nWatercolor")
        compose.onNodeWithContentDescription(string(R.string.chat_send)).assertIsNotEnabled()
        compose.onNodeWithText("Library").performClick().assertIsSelected()
        compose.onNodeWithText("Chat").performClick().assertIsSelected()
        compose.onNodeWithText("Moonlit garden\nWatercolor").assertIsDisplayed()
        step.performClick()
        sliderAt(6).assertIsDisplayed()
    }

    @Test
    fun generationCallbackReceivesOneRequestAndRunningStateAllowsCancellation() {
        configureEdgeToEdge()
        val model = model(1, "ToonYou")
        val generation = mutableStateOf<ChatGeneration?>(null)
        val requests = mutableListOf<ChatImageRequest>()
        val prompt = "Moonlit garden\nWatercolor"
        compose.setContent {
            PocketMusePrototypeTheme {
                ChatScreen(
                    library = listOf(LibraryItem(model, null)),
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    generation = generation.value,
                    onGenerate = {
                        requests += it
                        generation.value = ChatGeneration(it.prompt, ChatGeneration.Status.Running())
                    },
                    onCancelGeneration = {
                        generation.value = ChatGeneration(prompt, ChatGeneration.Status.Cancelled)
                    }
                )
            }
        }
        val send = compose.onNodeWithContentDescription(string(R.string.chat_send))
        val step = compose.onNodeWithContentDescription(string(R.string.chat_steps))
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("  ")
        send.assertIsNotEnabled()
        field.performTextClearance()
        field.performTextInput(prompt)
        step.performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        tapOnScreen(step)
        send.assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !keyboardVisible() }
        compose.runOnIdle { assertEquals(listOf(ChatImageRequest(model.storageKey, 8, prompt)), requests) }
        compose.onNodeWithText(model.modelName).assertIsNotEnabled()
        step.assertIsNotEnabled()
        send.assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.chat_generating)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.chat_cancel_generation)).performClick()
        compose.onNodeWithText(string(R.string.chat_generation_cancelled)).assertIsDisplayed()
    }

    @Test
    fun popupAndComposerStayUsableAt320DpWithKeyboardOpen() {
        configureEdgeToEdge()
        compose.setContent {
            PocketMusePrototypeTheme {
                Box(Modifier.width(320.dp).safeDrawingPadding()) {
                    ChatScreen(library = emptyList(), modifier = Modifier.fillMaxSize())
                }
            }
        }
        val field = compose.onNode(hasSetTextAction())
        tapOnScreen(field)
        compose.waitUntil(timeoutMillis = 5_000) { keyboardVisible() }
        field.performTextInput("Moonlit garden\nWatercolor\nEvening\nSoft light")
        val step = compose.onNodeWithContentDescription(string(R.string.chat_steps))
        val send = compose.onNodeWithContentDescription(string(R.string.chat_send))
        val composerBefore = field.fetchSemanticsNode().boundsInWindow
        tapOnScreen(step)
        sliderAt(4).assertIsDisplayed()
        val stepBounds = step.fetchSemanticsNode().touchBoundsInRoot
        val sendBounds = send.fetchSemanticsNode().touchBoundsInRoot
        val slider = sliderAt(4).fetchSemanticsNode()
        val fieldNode = field.fetchSemanticsNode()
        with(compose.density) {
            for (bounds in listOf(stepBounds, sendBounds)) {
                assertTrue(bounds.width.toDp() >= 47.5.dp && bounds.height.toDp() >= 47.5.dp)
            }
        }
        assertTrue(slider.positionOnScreen.y + slider.size.height < fieldNode.positionOnScreen.y)
        assertEquals(composerBefore, fieldNode.boundsInWindow)
        assertTrue(keyboardVisible())
        tapOnScreen(step)
        sliderAt(4).assertDoesNotExist()
        assertTrue(keyboardVisible())
    }

    @Suppress("DEPRECATION") // Matches MainActivity's adjustResize manifest setting for API 29.
    private fun configureEdgeToEdge() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun keyboardVisible() = compose.runOnUiThread {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun tapOnScreen(node: SemanticsNodeInteraction) {
        val target = node.fetchSemanticsNode()
        val x = target.positionOnScreen.x + target.size.width / 2f
        val y = target.positionOnScreen.y + target.size.height / 2f
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun sliderAt(value: Int) = compose.onNode(SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(value.toFloat(), 2f..8f, 2)
    ))

    private fun model(id: Long, name: String) = LibraryModel(
        storageKey = "${id}_$id",
        modelId = id,
        modelVersionId = id,
        fileId = id,
        modelName = name,
        versionName = "Version $id",
        fileName = "checkpoint.safetensors",
        fileSize = 1,
        sha256 = null,
        thumbnailUrl = null,
        workId = "fixture",
        downloadStatus = DownloadStatus.Downloaded,
        conversionStatus = ModelConversionStatus.Converted,
        conversionRecipeId = STATIC_LCM_RECIPE
    )

    private fun launchApp(library: () -> List<LibraryItem> = { emptyList() }) {
        val market = MarketViewModel(CatalogRepository { error("Market was not opened") })
        compose.setContent {
            PocketMusePrototypeTheme {
                PocketMuseApp(
                    authState = AuthState.Disconnected(),
                    onConnect = {},
                    onRetryAuth = {},
                    onDisconnect = {},
                    marketViewModel = market,
                    library = library()
                )
            }
        }
    }

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
}
