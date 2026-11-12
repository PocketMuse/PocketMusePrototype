package com.example.pocketmuseprototype

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
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
        compose.onNodeWithText(context.getString(R.string.library_queued)).assertIsDisplayed()
        compose.runOnIdle { state.value = SharedLcmState.Downloading(4096) }
        compose.onNodeWithText(context.getString(R.string.library_downloading)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_cancel)).performClick()
        compose.runOnIdle {
            assertTrue(cancelled)
            state.value = SharedLcmState.Verifying
        }
        compose.onNodeWithText(context.getString(R.string.library_verifying)).assertIsDisplayed()
        compose.runOnIdle { state.value = SharedLcmState.Ready }
        compose.onNodeWithText(context.getString(R.string.library_downloads_empty)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_lcm_ready), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_models_empty)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_convert)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_delete)).performClick()
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
        compose.onNodeWithText(app.getString(R.string.library_lcm_ready), substring = true).assertIsDisplayed()
    }
}
