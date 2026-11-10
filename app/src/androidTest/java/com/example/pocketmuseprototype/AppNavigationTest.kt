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
    fun imagineShowsCivitaiSetupOnlyOnItsTab() {
        compose.onNodeWithText("Imagine").performClick().assertIsSelected()
        compose.onNodeWithText("Civitai 연결").assertIsNotEnabled()

        compose.onNodeWithText("Models").performClick()
        compose.onNodeWithText("Civitai 연결").assertDoesNotExist()
    }

    @Test
    fun imagineGalleryScrollsWhileTabsStayVisible() {
        val imagine = compose.onNodeWithText("Imagine")
        val intro = compose.onNodeWithText("Civitai 액세스")
        imagine.performClick()
        intro.assertIsDisplayed()

        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }

        intro.assertIsNotDisplayed()
        imagine.assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun switchingTabsKeepsTheChatDraft() {
        compose.onNodeWithText("Chat").assertIsSelected()
        compose.onNode(hasSetTextAction()).performTextInput("A moonlit garden")

        compose.onNodeWithText("Imagine").performClick().assertIsSelected()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Models").performClick().assertIsSelected()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()

        compose.onNodeWithText("Chat").performClick().assertIsSelected()
        compose.onNodeWithText("A moonlit garden").assertExists()
    }
}
