package com.example.pocketmuseprototype.runtime

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.MainActivity
import com.example.pocketmuseprototype.PocketMuseApplication
import com.example.pocketmuseprototype.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ChatGenerationAndroidTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun cancelsTwoStepGenerationAndDisplaysTheNextFourStepImage() {
        val modelName = InstrumentationRegistry.getArguments().getString("chatModelName")
        assumeTrue(!modelName.isNullOrBlank())
        val name = checkNotNull(modelName)
        compose.waitUntil(10_000) {
            compose.hasTextNode(name) || compose.hasTextNode(string(R.string.chat_select_model)) &&
                !compose.hasTextNode(string(R.string.chat_models_empty))
        }
        val modelButton = if (compose.hasTextNode(name)) name else string(R.string.chat_select_model)
        compose.onNodeWithText(modelButton).performClick()
        compose.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()

        val promptField = compose.onNode(hasSetTextAction())
        val send = compose.onNodeWithContentDescription(string(R.string.chat_send))
        val cancel = compose.onNodeWithContentDescription(string(R.string.chat_cancel_generation))
        chooseSteps(2)
        promptField.performTextInput("a small fox in a moonlit garden")
        send.performClick()
        try {
            awaitPreview(1, 2)
            cancel.performClick()
            awaitText(string(R.string.chat_generation_cancelled))
            compose.onNodeWithText(string(R.string.chat_image_preview, 1, 2)).assertDoesNotExist()
        } finally {
            if (compose.onAllNodes(hasContentDescription(string(R.string.chat_cancel_generation)))
                    .fetchSemanticsNodes().isNotEmpty()) cancel.performClick()
        }

        chooseSteps(4)
        promptField.performTextClearance()
        val prompt = "masterpiece, best quality, a cute cat"
        val app = compose.activity.application as PocketMuseApplication
        val existingIds = runBlocking { app.gallery.gallery.first().map { it.record.id }.toSet() }
        promptField.performTextInput(prompt)
        val start = SystemClock.elapsedRealtime()
        send.performClick()
        try {
            awaitPreview(1, 4)
            println("Chat displayed its first preview in ${SystemClock.elapsedRealtime() - start} ms")
            compose.onNodeWithText(string(R.string.tab_models)).performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(string(R.string.tab_chat)).performClick()
            awaitPreview(4, 4)
            compose.waitUntil(300_000) {
                compose.onAllNodes(SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate
                )).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(string(R.string.chat_finishing_image)).assertDoesNotExist()
            compose.onNodeWithText(string(R.string.chat_image_preview, 4, 4)).assertIsDisplayed()
            val description = string(R.string.chat_generated_image)
            compose.waitUntil(300_000) {
                compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.chat_image_preview, 4, 4)).assertDoesNotExist()
            val saved = runBlocking(Dispatchers.IO) {
                app.gallery.gallery.first().single { it.record.id !in existingIds }
            }
            assertEquals(prompt, saved.record.prompt)
            assertEquals(4, saved.record.steps)
            assertEquals(name, saved.record.modelName)
            val decoded = checkNotNull(BitmapFactory.decodeFile(saved.file.path))
            assertEquals(512, decoded.width)
            assertEquals(512, decoded.height)
            decoded.recycle()
            println("Chat completed and Gallery saved a four-step PNG in ${SystemClock.elapsedRealtime() - start} ms")
            compose.onNodeWithContentDescription(string(R.string.open_menu)).performClick()
            val thumbnail = hasContentDescription(string(R.string.gallery_thumbnail, prompt))
            compose.waitUntil(10_000) { compose.onAllNodes(thumbnail).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(thumbnail)[0].performClick()
            compose.onNodeWithText(string(R.string.gallery_model, name)).assertIsDisplayed()
        } finally {
            if (compose.onAllNodes(hasContentDescription(string(R.string.chat_cancel_generation)))
                    .fetchSemanticsNodes().isNotEmpty()) cancel.performClick()
        }
    }

    private fun chooseSteps(steps: Int) {
        compose.onNodeWithContentDescription(string(R.string.chat_steps)).performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(steps.toFloat()) }
        val close = string(R.string.chat_close_steps)
        compose.onNode(SemanticsMatcher("click action labeled $close") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == close
        }).performClick()
    }

    private fun awaitText(text: String) = compose.waitUntil(300_000) { compose.hasTextNode(text) }

    private fun awaitPreview(step: Int, total: Int) {
        val label = string(R.string.chat_image_preview, step, total)
        awaitText(label)
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun SemanticsNodeInteractionsProvider.hasTextNode(text: String): Boolean =
        onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
