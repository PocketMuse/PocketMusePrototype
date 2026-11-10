package com.example.pocketmuseprototype

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun marketShowsContentForTheSelectedCategory() {
        val market = compose.onNodeWithText("Market")
        val text = compose.onNodeWithText("Text")
        val image = compose.onNodeWithText("Image")
        val connect = compose.onNodeWithText("Civitai 연결")
        val model = compose.onNodeWithText("gemma-4-E2B-it-MNN")

        market.performClick().assertIsSelected()
        image.assertIsSelected()
        connect.assertIsNotEnabled()
        model.assertDoesNotExist()

        text.performClick().assertIsSelected()
        connect.assertDoesNotExist()
        model.assertIsDisplayed()
        compose.onNodeWithText("3.48 GB").assertIsDisplayed()
        compose.onAllNodesWithText("Download")[0].assertIsNotEnabled()

        compose.onNodeWithText("Models").performClick()
        text.assertDoesNotExist()
        image.assertDoesNotExist()

        market.performClick()
        text.assertIsSelected()
        image.performClick().assertIsSelected()
        connect.assertIsNotEnabled()
        model.assertDoesNotExist()
    }

    @Test
    fun marketImageGalleryScrollsWhileTabsStayVisible() {
        val market = compose.onNodeWithText("Market")
        val intro = compose.onNodeWithText("Civitai 액세스")
        market.performClick()
        intro.assertIsDisplayed()

        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }

        intro.assertIsNotDisplayed()
        market.assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Image").assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun switchingTabsKeepsTheChatDraft() {
        compose.onNodeWithText("Chat").assertIsSelected()
        compose.onNode(hasSetTextAction()).performTextInput("A moonlit garden")

        compose.onNodeWithText("Market").performClick().assertIsSelected()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Models").performClick().assertIsSelected()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()

        compose.onNodeWithText("Chat").performClick().assertIsSelected()
        compose.onNodeWithText("A moonlit garden").assertExists()
    }
}
