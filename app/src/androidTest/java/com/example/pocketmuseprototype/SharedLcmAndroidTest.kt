package com.example.pocketmuseprototype

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.pocketmuseprototype.conversion.sha256
import com.example.pocketmuseprototype.download.DownloadStatus
import com.example.pocketmuseprototype.download.DownloadError
import com.example.pocketmuseprototype.download.LibraryItem
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.Sd15LcmLora
import com.example.pocketmuseprototype.download.SharedLcmState
import com.example.pocketmuseprototype.download.WorkManagerSharedLcm
import com.example.pocketmuseprototype.ui.LibraryScreen
import com.example.pocketmuseprototype.ui.theme.PocketMusePrototypeTheme
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SharedLcmLibraryAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<PocketMuseApplication>()

    @Test fun failureRetriesAndVerifiedAssetMovesToLibrary() {
        val state = mutableStateOf<SharedLcmState>(SharedLcmState.Failed(DownloadError.InvalidHash))
        var cancelled = false
        var deleted = false
        compose.setContent {
            PocketMusePrototypeTheme {
                LibraryScreen(
                    emptyList(), {}, {}, {}, sharedLcm = state.value,
                    onRetryLcm = { state.value = SharedLcmState.Queued },
                    onCancelLcm = { cancelled = true },
                    onDeleteLcm = { deleted = true }
                )
            }
        }
        compose.onNodeWithText("LR").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_error_hash)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_downloads_empty)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_lcm_retry)).performClick()
        val cancelLabel = context.getString(R.string.library_cancel_download, context.getString(R.string.library_lcm_name))
        compose.onNode(hasContentDescription(cancelLabel) and SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate
        )).assertIsDisplayed()
        compose.onNodeWithText("대기 중").assertDoesNotExist()
        compose.runOnIdle { state.value = SharedLcmState.Downloading(Sd15LcmLora.SIZE / 2) }
        compose.onNodeWithText("다운로드 중").assertDoesNotExist()
        compose.onNode(hasContentDescription(cancelLabel) and SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f)
        )).performClick()
        compose.runOnIdle { assertFalse(cancelled) }
        compose.onNodeWithText(context.getString(R.string.library_dismiss_confirmation)).performClick()
        compose.onNodeWithContentDescription(cancelLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.library_cancel_download_question)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_confirm_cancel_download)).performClick()
        compose.runOnIdle {
            assertTrue(cancelled)
            state.value = SharedLcmState.Verifying
        }
        compose.onNodeWithText(context.getString(R.string.library_verifying)).assertIsDisplayed()
        compose.runOnIdle { state.value = SharedLcmState.Ready }
        compose.onNodeWithText(context.getString(R.string.library_downloads_empty)).assertIsDisplayed()
        compose.onNodeWithText(sharedLcmSize(context)).assertIsDisplayed()
        compose.onNodeWithText("사용 가능", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_models_empty)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_convert)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_delete)).performClick()
        compose.runOnIdle { assertFalse(deleted) }
        compose.onNodeWithText(context.getString(R.string.library_dismiss_confirmation)).performClick()
        compose.onNodeWithText(context.getString(R.string.library_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.library_confirm_delete)).performClick()
        compose.runOnIdle { assertTrue(deleted) }
    }

    @Test fun checkpointConversionActionRemainsAvailableAlongsideSharedAsset() {
        val model = LibraryModel(
            "2_3", 1, 2, 3, "Checkpoint fixture", "v1", "fixture.safetensors",
            4, null, null, "fixture", DownloadStatus.Downloaded, 4, 4
        )
        var converted: LibraryModel? = null
        compose.setContent {
            PocketMusePrototypeTheme {
                LibraryScreen(
                    listOf(LibraryItem(model, null)), {}, { converted = it }, {},
                    sharedLcm = SharedLcmState.Ready
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.library_convert)).performClick()
        compose.runOnIdle { assertEquals(model, converted) }
    }
}

class SharedLcmNetworkAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pinnedAssetDownloadsInBackgroundAndSurvivesActivityRecreation() {
        val app = compose.activity.application as PocketMuseApplication
        assumeTrue(InstrumentationRegistry.getArguments().getString("sharedLcmNetwork") == "true")
        assumeTrue(app.packageName.endsWith(".converterbenchmark"))
        val downloads = app.sharedLcm
        val manager = WorkManager.getInstance(app)
        val scheduler = WorkManagerSharedLcm(manager)
        val originalId = runBlocking { scheduler.work.first()?.id }
        val workId = runBlocking {
            val first = async { downloads.ensure() }
            val second = async { downloads.ensure() }
            assertEquals(setOf(true, false), setOf(first.await(), second.await()))
            manager.getWorkInfosForUniqueWorkFlow(Sd15LcmLora.WORK).first().single().id.also {
                assertTrue(it != originalId)
            }
        }
        runBlocking {
            withTimeout(60_000) {
                downloads.state.first {
                    it is SharedLcmState.Downloading || it == SharedLcmState.Ready || it is SharedLcmState.Failed
                }.also { assertFalse(it.toString(), it is SharedLcmState.Failed) }
            }
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        runBlocking {
            val finished = withTimeout(240_000) {
                scheduler.work.first { it?.id == workId && it.state.isFinished }
            }
            assertEquals(finished.toString(), WorkInfo.State.SUCCEEDED, finished?.state)
            assertEquals(
                134_621_556L to "8f90d840e075ff588a58e22c6586e2ae9a6f7922996ee6649a7f01072333afe4",
                downloads.withVerifiedFile { it.length() to it.sha256() }
            )
            assertFalse(downloads.ensure())
            assertEquals(workId, manager.getWorkInfosForUniqueWorkFlow(Sd15LcmLora.WORK).first().single().id)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Library").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("SD1.5 LCM LoRA").assertIsDisplayed()
        compose.onNodeWithText(sharedLcmSize(app)).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            app.getString(R.string.library_cancel_download, app.getString(R.string.library_lcm_name))
        ).assertDoesNotExist()
    }

    @Test fun libraryCancelsLiveTransferAndRetryPublishesVerifiedAsset() {
        val app = compose.activity.application as PocketMuseApplication
        assumeTrue(InstrumentationRegistry.getArguments().getString("sharedLcmCancelNetwork") == "true")
        assumeTrue(app.packageName.endsWith(".converterbenchmark"))
        val downloads = app.sharedLcm
        val manager = WorkManager.getInstance(app)
        val work = manager.getWorkInfosForUniqueWorkFlow(Sd15LcmLora.WORK)
        val asset = File(app.noBackupFilesDir, "shared-assets/${Sd15LcmLora.ID}/${Sd15LcmLora.FILE_NAME}")
        val partial = File(asset.parentFile, "${asset.name}.part")
        compose.onNodeWithText("Library").performClick()
        try {
            val first = runBlocking {
                assertTrue(downloads.ensure())
                val id = work.first().single().id
                val transfer = withTimeout(60_000) {
                    downloads.state.first {
                        it is SharedLcmState.Downloading && it.bytes > 0 ||
                            it is SharedLcmState.Failed || it == SharedLcmState.Ready
                    }
                }
                assertTrue(transfer.toString(), transfer is SharedLcmState.Downloading)
                id
            }
            val cancelLabel = app.getString(R.string.library_cancel_download, app.getString(R.string.library_lcm_name))
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(cancelLabel).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(cancelLabel).performClick()
            compose.onNodeWithText(app.getString(R.string.library_confirm_cancel_download)).performClick()
            runBlocking {
                val cancelled = withTimeout(15_000) { work.first { it.single().state.isFinished }.single() }
                assertEquals(first, cancelled.id)
                assertEquals(WorkInfo.State.CANCELLED, cancelled.state)
                downloads.reconcile()
                assertFalse(partial.exists())
                assertFalse(asset.exists())
                assertEquals(SharedLcmState.Missing, downloads.state.first())

                assertTrue(downloads.ensure())
                val retry = withTimeout(240_000) { work.first { it.single().state.isFinished }.single() }
                assertTrue(first != retry.id)
                assertEquals(WorkInfo.State.SUCCEEDED, retry.state)
                assertEquals(
                    134_621_556L to "8f90d840e075ff588a58e22c6586e2ae9a6f7922996ee6649a7f01072333afe4",
                    downloads.withVerifiedFile { it.length() to it.sha256() }
                )
            }
            val readySize = sharedLcmSize(app)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(readySize).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription(cancelLabel).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText(readySize).assertIsDisplayed()
        } finally {
            runBlocking {
                if (downloads.state.first() == SharedLcmState.Ready) downloads.delete() else downloads.cancel()
            }
        }
    }
}

private fun sharedLcmSize(context: Context): String = Formatter.formatShortFileSize(context, Sd15LcmLora.SIZE)
