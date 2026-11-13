package com.example.pocketmuseprototype

import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import com.example.pocketmuseprototype.auth.AuthState
import com.example.pocketmuseprototype.auth.CivitaiProfile
import com.example.pocketmuseprototype.catalog.CatalogCheckpoint
import com.example.pocketmuseprototype.catalog.CatalogRepository
import com.example.pocketmuseprototype.catalog.FileDto
import com.example.pocketmuseprototype.catalog.FileMetadata
import com.example.pocketmuseprototype.catalog.MarketViewModel
import com.example.pocketmuseprototype.catalog.ModelDto
import com.example.pocketmuseprototype.catalog.ModelsResponse
import com.example.pocketmuseprototype.catalog.PageMetadata
import com.example.pocketmuseprototype.catalog.VersionDto
import com.example.pocketmuseprototype.download.DownloadStatus
import com.example.pocketmuseprototype.download.LEGACY_HQQ_RECIPE
import com.example.pocketmuseprototype.download.LibraryItem
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.ModelConversionStatus
import com.example.pocketmuseprototype.download.STATIC_LCM_RECIPE
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
    fun connectedMarketEnqueuesInPlaceAndLibraryDisplaysProgressCancelAndDelete() {
        val row = LibraryModel("1_1", 1, 1, 1, "Checkpoint 1", "Version 1", "fixture.safetensors",
            100_000_000L, null, null, "fixture")
        val entries = mutableStateOf<List<LibraryItem>>(emptyList())
        val removed = mutableListOf<DownloadStatus>()
        launchApp(
            authState = { AuthState.Connected(CivitaiProfile("fixture", "creator")) },
            library = { entries.value },
            onDownload = {
                check(it.fileId == row.fileId)
                entries.value = listOf(LibraryItem(row, null))
            },
            onRemove = {
                removed.add(it.downloadStatus)
                entries.value = emptyList()
            }
        ) { page(1..1) }

        val market = compose.onNodeWithText("Market")
        val library = compose.onNodeWithText("Library")
        market.performClick()
        compose.onAllNodesWithContentDescription(string(R.string.market_download))[0].performClick()
        market.assertIsSelected()
        library.performClick()
        val cancelDownload = string(R.string.library_cancel_download, row.modelName)
        compose.onNode(hasContentDescription(cancelDownload) and SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate
        )).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("대기 중").assertDoesNotExist()
        val downloading = row.copy(downloadStatus = DownloadStatus.Downloading,
            downloadedBytes = 25_000_000L, totalBytes = 100_000_000L)
        compose.runOnIdle { entries.value = listOf(LibraryItem(downloading, null)) }
        compose.onNode(hasContentDescription(cancelDownload) and SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f)
        )).assertIsDisplayed().assertIsEnabled()
        market.performClick()
        library.performClick()
        compose.onNodeWithText("다운로드 중").assertDoesNotExist()
        compose.runOnIdle { entries.value = listOf(LibraryItem(downloading.copy(totalBytes = null), null)) }
        compose.onNode(hasContentDescription(cancelDownload) and SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate
        )).performClick()
        compose.runOnIdle { check(removed.isEmpty()) }
        compose.onNodeWithText(string(R.string.library_dismiss_confirmation)).performClick()
        compose.runOnIdle { check(removed.isEmpty()) }
        compose.onNodeWithContentDescription(cancelDownload).performClick()
        compose.onNodeWithText(string(R.string.library_cancel_download_question)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_confirm_cancel_download)).performClick()
        compose.onNodeWithText(string(R.string.library_downloads_empty)).assertIsDisplayed()

        val completed = row.copy(downloadStatus = DownloadStatus.Downloaded)
        compose.runOnIdle { entries.value = listOf(LibraryItem(completed, null)) }
        val size = Formatter.formatShortFileSize(compose.activity, completed.fileSize)
        compose.onNodeWithText(size, substring = true).assertIsDisplayed()
        compose.onNodeWithText("다운로드 완료", substring = true).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.library_delete)).performClick()
        compose.runOnIdle { check(removed == listOf(DownloadStatus.Downloading)) }
        compose.onNodeWithText(string(R.string.library_dismiss_confirmation)).performClick()
        compose.onNodeWithText(string(R.string.library_delete)).performClick()
        compose.onNodeWithText(string(R.string.library_confirm_delete)).performClick()
        compose.onNodeWithText(string(R.string.library_models_empty)).assertIsDisplayed()
        compose.runOnIdle { check(removed == listOf(DownloadStatus.Downloading, DownloadStatus.Downloaded)) }
    }

    @Test
    fun libraryStartsCancelsAndRetriesConversionWithSeparateProgress() {
        val downloaded = LibraryModel("1_1", 1, 1, 1, "Checkpoint 1", "Version 1", "fixture.safetensors",
            100_000_000L, null, null, "fixture", DownloadStatus.Downloaded)
        val model = mutableStateOf(downloaded)
        launchApp(
            library = { listOf(LibraryItem(model.value, null)) },
            onConvert = {
                model.value = if (it.conversionStatus == ModelConversionStatus.NotStarted) {
                    it.copy(conversionStatus = ModelConversionStatus.Queued)
                } else it.copy(conversionStatus = ModelConversionStatus.Converted, conversionProgress = 100,
                    conversionRecipeId = STATIC_LCM_RECIPE)
            },
            onCancelConversion = {
                model.value = it.copy(conversionStatus = ModelConversionStatus.Cancelled, conversionProgress = null)
            }
        ) { page(1..1) }

        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("최적화 필요", substring = true).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_convert)).performClick()
        compose.onNodeWithText(string(R.string.library_delete)).assertDoesNotExist()
        val optimizationActions = string(R.string.library_optimization_actions, downloaded.modelName)
        compose.onNode(hasContentDescription(optimizationActions) and SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate
        )).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("최적화 대기 중", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            model.value = model.value.copy(conversionStatus = ModelConversionStatus.Converting, conversionProgress = 37)
        }
        compose.onNodeWithText("최적화 37%", substring = true).assertIsDisplayed()
        compose.onNode(hasContentDescription(optimizationActions) and SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.37f, 0f..1f)
        )).performClick()
        compose.runOnIdle { check(model.value.conversionStatus == ModelConversionStatus.Converting) }
        compose.onNodeWithText(string(R.string.library_dismiss_confirmation)).performClick()
        compose.onNodeWithContentDescription(optimizationActions).performClick()
        compose.onNodeWithText(string(R.string.library_delete_model)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_confirm_cancel_conversion)).performClick()
        compose.onNodeWithText("최적화 필요", substring = true).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_delete)).assertIsEnabled()
        compose.onNodeWithText(string(R.string.library_convert_retry)).performClick()
        compose.onNodeWithText("최적화 필요", substring = true).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.library_convert)).assertDoesNotExist()
        compose.onNodeWithText("최적화 완료", substring = true).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.library_delete)).assertIsEnabled()
    }

    @Test
    fun libraryDeletesAnOptimizingModelThroughTheActionDialog() {
        val active = LibraryModel("1_1", 1, 1, 1, "Checkpoint 1", "Version 1", "fixture.safetensors",
            100_000_000L, null, null, "fixture", DownloadStatus.Downloaded,
            conversionStatus = ModelConversionStatus.Converting, conversionProgress = 37)
        val model = mutableStateOf<LibraryModel?>(active)
        launchApp(
            library = { listOfNotNull(model.value?.let { LibraryItem(it, null) }) },
            onRemove = {
                check(it == active)
                model.value = null
            },
            onCancelConversion = { error("Delete must remove the model") }
        ) { page(1..1) }

        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithContentDescription(string(R.string.library_optimization_actions, active.modelName)).performClick()
        compose.onNode(hasText(string(R.string.library_delete_model)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(active.modelName).assertDoesNotExist()
    }

    @Test
    fun openOptimizationDialogUsesTheLatestModelStateAfterCompletion() {
        val active = LibraryModel("1_1", 1, 1, 1, "Checkpoint 1", "Version 1", "fixture.safetensors",
            100_000_000L, null, null, "fixture", DownloadStatus.Downloaded,
            conversionStatus = ModelConversionStatus.Converting, conversionWorkId = "optimization")
        val model = mutableStateOf<LibraryModel?>(active)
        launchApp(
            library = { listOfNotNull(model.value?.let { LibraryItem(it, null) }) },
            onRemove = {
                check(it.conversionStatus == ModelConversionStatus.Converted)
                model.value = null
            },
            onCancelConversion = { error("A completed optimization cannot be cancelled") }
        ) { page(1..1) }

        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithContentDescription(string(R.string.library_optimization_actions, active.modelName)).performClick()
        compose.onNodeWithText(string(R.string.library_confirm_cancel_conversion)).assertIsDisplayed()
        compose.runOnIdle {
            model.value = active.copy(conversionStatus = ModelConversionStatus.Converted, conversionRecipeId = STATIC_LCM_RECIPE)
        }
        compose.onNodeWithText(string(R.string.library_confirm_cancel_conversion)).assertDoesNotExist()
        compose.onNode(hasText(string(R.string.library_delete_model)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(active.modelName).assertDoesNotExist()
    }

    @Test
    fun libraryShowsLoraWaitAndLetsUserReplaceLegacyOutput() {
        val legacy = LibraryModel("1_1", 1, 1, 1, "Checkpoint 1", "Version 1", "fixture.safetensors",
            100_000_000L, null, null, "fixture", DownloadStatus.Downloaded,
            conversionStatus = ModelConversionStatus.Converted, conversionRecipeId = LEGACY_HQQ_RECIPE)
        val model = mutableStateOf(legacy)
        launchApp(
            library = { listOf(LibraryItem(model.value, null)) },
            onConvert = {
                model.value = it.copy(conversionStatus = ModelConversionStatus.WaitingForLcm,
                    conversionRecipeId = STATIC_LCM_RECIPE)
            },
            onCancelConversion = { model.value = it.copy(conversionStatus = ModelConversionStatus.Cancelled) }
        ) { page(1..1) }

        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("최적화 필요", substring = true).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_convert)).performClick()
        compose.onNodeWithText(string(R.string.library_conversion_waiting_lcm), substring = true).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_lcm_download)).assertIsEnabled()
        compose.onNodeWithContentDescription(string(R.string.library_optimization_actions, legacy.modelName)).performClick()
        compose.onNodeWithText(string(R.string.library_confirm_cancel_conversion)).performClick()
        compose.onNodeWithText("최적화 필요", substring = true).assertIsDisplayed()
    }

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
        compose.onAllNodesWithContentDescription(string(R.string.market_download_connect))[0].assertIsNotEnabled()
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

        grid.performScrollToIndex(9)
        compose.onNodeWithText(string(R.string.market_loading_more)).assertIsDisplayed()
        response.completeExceptionally(IOException("Page unavailable"))
        compose.onNodeWithText(string(R.string.market_load_more_error)).assertIsDisplayed()
        compose.onNodeWithText("Checkpoint 8").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.market_retry)).performClick()

        grid.performScrollToIndex(11)
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
        grid.performScrollToIndex(17)
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

    @Test
    fun marketAccountChangesWithoutReloadingThePublicCatalog() {
        val account = mutableStateOf<AuthState>(AuthState.Disconnected())
        var requests = 0
        launchApp(
            authState = { account.value },
            onConnect = { account.value = AuthState.Connecting },
            onDisconnect = { account.value = AuthState.Disconnected() }
        ) {
            requests++
            page(1..1)
        }
        val market = compose.onNodeWithText("Market")
        val checkpoint = compose.onNodeWithText("Checkpoint 1")
        val connect = compose.onNodeWithText(string(R.string.market_civitai_connect))
        market.performClick()
        checkpoint.assertIsDisplayed()
        connect.assertIsEnabled().performClick()
        compose.onNodeWithText(string(R.string.market_civitai_connecting)).assertIsDisplayed()
        connect.assertDoesNotExist()
        checkpoint.assertIsDisplayed()

        compose.runOnIdle { account.value = AuthState.Connected(CivitaiProfile("42", "creator")) }
        compose.onNodeWithText("Library").performClick()
        market.performClick()
        compose.onNodeWithText("SD 1.5 Checkpoints").assertIsDisplayed()
        checkpoint.assertIsDisplayed()
        compose.onAllNodesWithContentDescription(string(R.string.market_download_connect))[0].assertIsNotEnabled()
        compose.onNodeWithText("@creator").performClick()
        compose.onNodeWithText(string(R.string.market_civitai_account)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.market_civitai_disconnect)).performClick()
        connect.assertIsEnabled()
        checkpoint.assertIsDisplayed()
        compose.runOnIdle { check(requests == 1) }
    }

    private fun launchApp(
        authState: () -> AuthState = { AuthState.Disconnected() },
        onConnect: () -> Unit = {},
        onDisconnect: () -> Unit = {},
        library: () -> List<LibraryItem> = { emptyList() },
        onDownload: ((CatalogCheckpoint) -> Unit)? = null,
        onRemove: (LibraryModel) -> Unit = {},
        onConvert: (LibraryModel) -> Unit = {},
        onCancelConversion: (LibraryModel) -> Unit = {},
        fetchPage: suspend (String?) -> ModelsResponse
    ) {
        val viewModel = MarketViewModel(CatalogRepository(fetchPage))
        compose.setContent {
            PocketMusePrototypeTheme {
                PocketMuseApp(
                    authState = authState(),
                    onConnect = onConnect,
                    onRetryAuth = {},
                    onDisconnect = onDisconnect,
                    marketViewModel = viewModel,
                    library = library(),
                    onDownloadRequest = onDownload,
                    onRemoveModel = onRemove,
                    onStartConversion = onConvert,
                    onCancelConversion = onCancelConversion
                )
            }
        }
    }

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

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
