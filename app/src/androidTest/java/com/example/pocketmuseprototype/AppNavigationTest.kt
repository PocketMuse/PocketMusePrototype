package com.example.pocketmuseprototype

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    fun marketShowsCivitaiOnlyInTheImageCategory() {
        val market = compose.onNodeWithText("Market")
        val text = compose.onNodeWithText("Text")
        val image = compose.onNodeWithText("Image")
        val connect = compose.onNodeWithText("Civitai 연결")

        market.performClick().assertIsSelected()
        image.assertIsSelected()
        connect.assertIsNotEnabled()

        text.performClick().assertIsSelected()
        connect.assertDoesNotExist()

        compose.onNodeWithText("Models").performClick()
        text.assertDoesNotExist()
        image.assertDoesNotExist()

        market.performClick()
        text.assertIsSelected()
        image.performClick().assertIsSelected()
        connect.assertIsNotEnabled()
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
