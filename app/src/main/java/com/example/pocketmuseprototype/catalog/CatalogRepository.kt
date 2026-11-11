package com.example.pocketmuseprototype.catalog

import java.io.IOException

internal data class CatalogBatch(val models: List<CatalogCheckpoint>, val hasMore: Boolean)

/** Owns the cursor and buffered candidates for one Market ViewModel, which serializes loads. */
internal class CatalogRepository(private val fetchPage: suspend (String?) -> ModelsResponse) {
    private val buffer = ArrayDeque<CatalogCheckpoint>()
    private val seenIds = mutableSetOf<Long>()
    private val seenCursors = mutableSetOf<String>()
    private var cursor: String? = null
    private var exhausted = false

    /** A failed page leaves its cursor and all candidates from earlier pages available for retry. */
    suspend fun loadBatch(): CatalogBatch {
        repeat(MAX_PAGES_PER_LOAD) {
            if (exhausted || buffer.size >= CATALOG_BATCH_SIZE) return takeBatch()

            val page = fetchPage(cursor)
            val nextCursor = page.metadata.nextCursor
            if (nextCursor != null && (nextCursor.isBlank() || !seenCursors.add(nextCursor))) {
                throw IOException("Invalid Civitai pagination cursor")
            }
            page.items.forEach { model ->
                val candidate = model.selectCheckpoint()
                if (candidate != null && seenIds.add(candidate.modelId)) buffer.addLast(candidate)
            }
            cursor = nextCursor
            exhausted = nextCursor == null
        }
        return takeBatch()
    }

    private fun takeBatch(): CatalogBatch {
        val models = List(minOf(buffer.size, CATALOG_BATCH_SIZE)) { buffer.removeFirst() }
        return CatalogBatch(models, hasMore = buffer.isNotEmpty() || !exhausted)
    }

    private companion object {
        const val MAX_PAGES_PER_LOAD = 5
    }
}
