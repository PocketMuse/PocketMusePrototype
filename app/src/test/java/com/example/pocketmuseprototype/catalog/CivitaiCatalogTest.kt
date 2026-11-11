package com.example.pocketmuseprototype.catalog

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CivitaiCatalogTest {
    @Test
    fun choosesFp16BeforePrimaryFp32AndBreaksTiesByPrimaryThenId() {
        val model = checkpoint(1, files = listOf(
            file(1, fp = "fp32", primary = true),
            file(2),
            file(4, primary = true),
            file(3, primary = true)
        ))
        assertEquals(3L, model.selectCheckpoint()?.fileId)
        assertEquals(1L, checkpoint(1, files = listOf(file(1, fp = "fp32"))).selectCheckpoint()?.fileId)
    }

    @Test
    fun rejectsUnsupportedFilesAndMissingMetadata() {
        val valid = file(1)
        listOf(
            valid.copy(metadata = null),
            valid.copy(metadata = valid.metadata?.copy(fp = null)),
            valid.copy(metadata = valid.metadata?.copy(size = "full")),
            valid.copy(name = "model.ckpt"),
            valid.copy(metadata = valid.metadata?.copy(format = "PickleTensor")),
            valid.copy(type = "VAE"),
            valid.copy(sizeKB = 0.0),
            valid.copy(downloadUrl = null)
        ).forEach { assertNull(checkpoint(1, files = listOf(it)).selectCheckpoint()) }
    }

    @Test
    fun usesLatestDatedSd15VersionWithoutFallingBack() {
        val older = version(1, "2024-01-01T00:00:00Z")
        val latest = version(2, "2024-01-01T00:00:00.123Z")
        val sdxl = version(3, "2025-01-01T00:00:00Z").copy(baseModel = "SDXL 1.0")
        val model = checkpoint(1).copy(modelVersions = listOf(older, sdxl, latest))
        assertEquals(2L, model.selectCheckpoint()?.modelVersionId)
        assertNull(model.copy(modelVersions = listOf(sdxl)).selectCheckpoint())
        assertNull(model.copy(modelVersions = listOf(older, latest.copy(files = emptyList()))).selectCheckpoint())
        assertNull(model.copy(modelVersions = listOf(older, latest.copy(publishedAt = null))).selectCheckpoint())
    }

    @Test
    fun rejectsModeratedNonPublicAndNonCheckpointModels() {
        val model = checkpoint(1)
        assertNull(model.copy(mode = "Archived").selectCheckpoint())
        assertNull(model.copy(mode = "TakenDown").selectCheckpoint())
        assertNull(model.copy(availability = "Private").selectCheckpoint())
        assertNull(model.copy(availability = null).selectCheckpoint())
        assertNull(model.copy(type = "LORA").selectCheckpoint())
    }

    @Test
    fun fillsEightAcrossPagesDeduplicatesAndDrainsBufferAfterCursorEnds() = runTest {
        val requests = mutableListOf<String?>()
        val repository = CatalogRepository { cursor ->
            requests += cursor
            when (cursor) {
                null -> page("second", 1..5, checkpoint(20).copy(type = "LORA"))
                "second" -> page(null, 5..12)
                else -> error("Unexpected cursor")
            }
        }

        val first = repository.loadBatch()
        assertEquals((1L..8L).toList(), first.models.map { it.modelId })
        assertTrue(first.hasMore)
        val second = repository.loadBatch()
        assertEquals((9L..12L).toList(), second.models.map { it.modelId })
        assertFalse(second.hasMore)
        assertFalse(repository.loadBatch().hasMore)
        assertEquals(listOf(null, "second"), requests)
    }

    @Test
    fun capsEachLoadAtFivePagesAndRetainsTheNextCursor() = runTest {
        val requests = mutableListOf<String?>()
        val repository = CatalogRepository { cursor ->
            requests += cursor
            val id = cursor?.toInt() ?: 1
            page(if (id < 6) (id + 1).toString() else null, id..id)
        }
        val first = repository.loadBatch()
        assertEquals((1L..5L).toList(), first.models.map { it.modelId })
        assertTrue(first.hasMore)
        assertEquals(listOf(null, "2", "3", "4", "5"), requests)
        val second = repository.loadBatch()
        assertEquals(listOf(6L), second.models.map { it.modelId })
        assertFalse(second.hasMore)
        assertEquals("6", requests.last())
    }

    @Test
    fun failedAndCyclicCursorPagesRetryWithoutDiscardingAcceptedCandidates() = runTest {
        val requests = mutableListOf<String?>()
        val repository = CatalogRepository { cursor ->
            requests += cursor
            when (cursor) {
                null -> page("a", 1..3)
                "a" -> page("b", 4..6)
                "b" -> when (requests.count { it == "b" }) {
                    1 -> throw IOException("HTTP 503")
                    2 -> page("a", 9..9)
                    3 -> page(" ", 10..10)
                    else -> page(null, 7..8)
                }
                else -> error("Unexpected cursor")
            }
        }
        repeat(3) {
            try {
                repository.loadBatch()
                throw AssertionError("Expected a page error")
            } catch (_: IOException) {
            }
        }
        val result = repository.loadBatch()
        assertEquals((1L..8L).toList(), result.models.map { it.modelId })
        assertFalse(result.hasMore)
        assertEquals(listOf(null, "a", "b", "b", "b", "b"), requests)
    }

    @Test
    fun ignoresConcurrentLoadsAndAutomaticLoadsAfterShortBatch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val firstPage = CompletableDeferred<ModelsResponse>()
            val requests = mutableListOf<String?>()
            val viewModel = MarketViewModel(CatalogRepository { cursor ->
                requests += cursor
                when (cursor) {
                    null -> firstPage.await()
                    "2", "3", "4", "5" -> page((cursor.toInt() + 1).toString(), IntRange.EMPTY)
                    "6" -> page(null, 2..2)
                    else -> error("Unexpected cursor")
                }
            })

            viewModel.start()
            runCurrent()
            viewModel.start()
            viewModel.loadMore()
            viewModel.prefetch()
            runCurrent()
            assertEquals(listOf<String?>(null), requests)
            firstPage.complete(page("2", 1..1))
            advanceUntilIdle()

            assertEquals(LoadStatus.MoreAvailable, viewModel.state.value.status)
            viewModel.prefetch()
            viewModel.start()
            advanceUntilIdle()
            assertEquals(5, requests.size)
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(1L, 2L), viewModel.state.value.models.map { it.modelId })
            assertEquals(LoadStatus.End, viewModel.state.value.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun page(next: String?, ids: IntRange, vararg extra: ModelDto) =
        ModelsResponse(ids.map { checkpoint(it.toLong()) } + extra, PageMetadata(next))

    private fun checkpoint(id: Long, files: List<FileDto> = listOf(file(id))) =
        ModelDto(id, "Checkpoint $id", "Checkpoint", "Public", modelVersions = listOf(
            version(id, "2024-01-01T00:00:00Z").copy(files = files)
        ))

    private fun version(id: Long, date: String) =
        VersionDto(id, "v$id", "SD 1.5", date, listOf(file(id)))

    private fun file(id: Long, fp: String = "fp16", primary: Boolean = false) =
        FileDto(
            id, "model$id.safetensors", "Model", 2_000_000.0,
            "https://example.test/download/$id", primary, FileMetadata("SafeTensor", "pruned", fp)
        )
}
