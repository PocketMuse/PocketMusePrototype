package com.example.pocketmuseprototype.runtime

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.MainActivity
import com.example.pocketmuseprototype.R
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
            awaitText(string(R.string.chat_denoising, 1, 2))
            cancel.performClick()
            awaitText(string(R.string.chat_generation_cancelled))
        } finally {
            if (compose.onAllNodes(hasContentDescription(string(R.string.chat_cancel_generation)))
                    .fetchSemanticsNodes().isNotEmpty()) cancel.performClick()
        }

        chooseSteps(4)
        promptField.performTextClearance()
        promptField.performTextInput("masterpiece, best quality, a cute cat")
        val start = SystemClock.elapsedRealtime()
        send.performClick()
        compose.onNodeWithText(string(R.string.tab_models)).performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(string(R.string.tab_chat)).performClick()
        try {
            val description = string(R.string.chat_generated_image)
            compose.waitUntil(300_000) {
                compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
            println("Chat displayed a four-step image in ${SystemClock.elapsedRealtime() - start} ms")
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

    private fun SemanticsNodeInteractionsProvider.hasTextNode(text: String): Boolean =
        onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
