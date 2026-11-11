package com.example.pocketmuseprototype

import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import com.example.pocketmuseprototype.catalog.CatalogRepository
import com.example.pocketmuseprototype.catalog.FileDto
import com.example.pocketmuseprototype.catalog.FileMetadata
import com.example.pocketmuseprototype.catalog.MarketViewModel
import com.example.pocketmuseprototype.catalog.ModelDto
import com.example.pocketmuseprototype.catalog.ModelsResponse
import com.example.pocketmuseprototype.catalog.PageMetadata
import com.example.pocketmuseprototype.catalog.VersionDto
import com.example.pocketmuseprototype.ui.PocketMuseApp
import com.example.pocketmuseprototype.ui.theme.PocketMusePrototypeTheme
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun marketShowsContentForTheSelectedCategory() {
        launchApp { page(1..8) }
        val market = compose.onNodeWithText("Market")
        val text = compose.onNodeWithText("Text")
        val image = compose.onNodeWithText("Image")
        val checkpoint = compose.onNodeWithText("Checkpoint 1")
        val textModel = compose.onNodeWithText("gemma-4-E2B-it-MNN")

        market.performClick().assertIsSelected()
        image.assertIsSelected()
        checkpoint.assertIsDisplayed()
        compose.onNodeWithText("Version 1").assertIsDisplayed()
        val expectedSize = Formatter.formatShortFileSize(compose.activity, 2_000_000_000L)
        compose.onAllNodesWithText(expectedSize)[0].assertIsDisplayed()
        compose.onAllNodesWithContentDescription(string(R.string.market_download_unavailable))[0].assertIsNotEnabled()
        textModel.assertDoesNotExist()

        text.performClick().assertIsSelected()
        checkpoint.assertDoesNotExist()
        textModel.assertIsDisplayed()
        compose.onNodeWithText("3.48 GB").assertIsDisplayed()
        compose.onAllNodesWithText("Download")[0].assertIsNotEnabled()

        compose.onNodeWithText("Library").performClick()
        text.assertDoesNotExist()
        image.assertDoesNotExist()

        market.performClick()
        text.assertIsSelected()
        image.performClick().assertIsSelected()
        checkpoint.assertIsDisplayed()
        textModel.assertDoesNotExist()
    }

    @Test
    fun scrollingPrefetchesAndRetryKeepsTheListAndScrollPositionAcrossTabs() {
        val response = CompletableDeferred<ModelsResponse>()
        val requests = mutableListOf<String?>()
        launchApp { cursor ->
            requests += cursor
            when {
                cursor == null -> page(1..8, "next")
                cursor == "next" && requests.size == 2 -> response.await()
                cursor == "next" -> page(9..16)
                else -> error("Unexpected cursor")
            }
        }
        val market = compose.onNodeWithText("Market")
        val grid = compose.onNode(hasScrollAction())
        market.performClick()
        compose.onNodeWithText("Checkpoint 1").assertIsDisplayed()

        grid.performScrollToIndex(8)
        compose.onNodeWithText(string(R.string.market_loading_more)).assertIsDisplayed()
        response.completeExceptionally(IOException("Page unavailable"))
        compose.onNodeWithText(string(R.string.market_load_more_error)).assertIsDisplayed()
        compose.onNodeWithText("Checkpoint 8").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.market_retry)).performClick()

        grid.performScrollToIndex(10)
        val scrolledCard = compose.onNodeWithText("Checkpoint 11")
        scrolledCard.assertIsDisplayed()
        market.assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Image").assertIsDisplayed().assertIsSelected()

        compose.onNodeWithText("Text").performClick()
        compose.onNodeWithText("Image").performClick()
        scrolledCard.assertIsDisplayed()
        compose.onNodeWithText("Library").performClick()
        market.performClick()
        scrolledCard.assertIsDisplayed()
        compose.onNodeWithText("Checkpoint 1").assertIsNotDisplayed()
        grid.performScrollToIndex(16)
        compose.onNodeWithText(string(R.string.market_end)).assertIsDisplayed()
        compose.runOnIdle { check(requests == listOf(null, "next", "next")) }
    }

    @Test
    fun initialRequestShowsLoadingFailureAndRetry() {
        val response = CompletableDeferred<ModelsResponse>()
        var firstRequest = true
        launchApp {
            if (firstRequest) {
                firstRequest = false
                response.await()
            } else {
                page(1..1)
            }
        }
        compose.onNodeWithText("Market").performClick()
        compose.onNodeWithText(string(R.string.market_loading)).assertIsDisplayed()
        response.completeExceptionally(IOException("Service unavailable"))
        compose.onNodeWithText(string(R.string.market_load_error)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.market_retry)).performClick()
        compose.onNodeWithText("Checkpoint 1").assertIsDisplayed()
    }

    @Test
    fun emptyShortBatchOffersMoreUntilTheCursorEnds() {
        var requests = 0
        val lastPage = CompletableDeferred<ModelsResponse>()
        launchApp {
            requests++
            if (requests < 6) page(IntRange.EMPTY, requests.toString()) else lastPage.await()
        }
        compose.onNodeWithText("Market").performClick()
        compose.onNodeWithText(string(R.string.market_empty_so_far)).assertIsDisplayed()
        compose.runOnIdle { check(requests == 5) }
        compose.onNodeWithText(string(R.string.market_load_more)).performClick()
        compose.onNodeWithText(string(R.string.market_loading_more)).assertIsDisplayed()
        lastPage.complete(page(IntRange.EMPTY))
        compose.onNodeWithText(string(R.string.market_empty)).assertIsDisplayed()
        compose.runOnIdle { check(requests == 6) }
    }

    @Test
    fun switchingTabsKeepsTheChatDraft() {
        launchApp { page(1..1) }
        compose.onNodeWithText("Chat").assertIsSelected()
        compose.onNode(hasSetTextAction()).performTextInput("A moonlit garden")

        compose.onNodeWithText("Market").performClick().assertIsSelected()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Library").performClick().assertIsSelected()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()

        compose.onNodeWithText("Chat").performClick().assertIsSelected()
        compose.onNodeWithText("A moonlit garden").assertExists()
    }

    private fun launchApp(fetchPage: suspend (String?) -> ModelsResponse) {
        val viewModel = MarketViewModel(CatalogRepository(fetchPage))
        compose.setContent {
            PocketMusePrototypeTheme {
                PocketMuseApp(marketViewModel = viewModel)
            }
        }
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun page(ids: IntRange, nextCursor: String? = null): ModelsResponse {
        val models = ids.map { index ->
            val id = index.toLong()
            val file = FileDto(
                id, "checkpoint$id.safetensors", "Model", 1_953_125.0,
                "https://example.test/models/$id", metadata = FileMetadata("SafeTensor", "pruned", "fp16")
            )
            val version = VersionDto(
                id = id,
                name = "Version $id",
                baseModel = "SD 1.5",
                baseModelType = "Standard",
                publishedAt = "2024-01-01T00:00:00Z",
                files = listOf(file)
            )
            ModelDto(id, "Checkpoint $id", "Checkpoint", "Public", modelVersions = listOf(version))
        }
        return ModelsResponse(models, PageMetadata(nextCursor))
    }
}
