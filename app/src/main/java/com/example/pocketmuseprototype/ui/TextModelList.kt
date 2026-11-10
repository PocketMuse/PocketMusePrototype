package com.example.pocketmuseprototype.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private data class TextModelSample(val name: String, val size: String)

private val SampleTextModels = listOf(
    TextModelSample("gemma-4-E2B-it-MNN", "3.48 GB"),
    TextModelSample("gemma-4-E4B-it-MNN", "5.16 GB"),
    TextModelSample("gemma-4-26B-A4B-it-MNN", "15.41 GB"),
    TextModelSample("gemma-4-31B-it-MNN", "18.53 GB"),
    TextModelSample("LFM2-350M-MNN", "213.17 MB"),
    TextModelSample("LFM2-700M-MNN", "444.76 MB"),
    TextModelSample("LFM2-1.2B-MNN", "700.10 MB")
)

@Composable
internal fun TextModelList(state: LazyListState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        itemsIndexed(SampleTextModels, key = { _, model -> model.name }) { index, model ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = colors.surfaceContainerHigh
                )
            }
            ListItem(
                headlineContent = {
                    Text(text = model.name, style = MaterialTheme.typography.titleMedium)
                },
                supportingContent = { Text(model.size) },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    supportingColor = colors.onSurfaceVariant
                )
            )
        }
    }
}
