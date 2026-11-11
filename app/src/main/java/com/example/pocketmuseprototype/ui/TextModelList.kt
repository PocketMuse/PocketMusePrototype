package com.example.pocketmuseprototype.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.example.pocketmuseprototype.R

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
    val nameStyle = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Heading)
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        itemsIndexed(SampleTextModels, key = { _, model -> model.name }) { index, model ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = colors.surfaceContainerHigh
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = colors.surfaceContainer,
                    border = BorderStroke(1.dp, colors.surfaceContainerHigh)
                ) {}
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(text = model.name, style = nameStyle)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            tint = colors.onSurfaceVariant
                        )
                        Text(
                            text = model.size,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = BorderStroke(1.dp, colors.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        disabledContentColor = colors.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.market_download))
                }
            }
        }
    }
}
