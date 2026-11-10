package com.example.pocketmuseprototype.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.pocketmuseprototype.R

@Composable
internal fun ImagineScreen(modifier: Modifier = Modifier) {
    val cardSpacing = Arrangement.spacedBy(8.dp)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 12.dp),
        horizontalArrangement = cardSpacing,
        verticalArrangement = cardSpacing
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            CivitaiAccess()
        }
        items(8) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large
                    )
            )
        }
    }
}

@Composable
private fun CivitaiAccess() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.imagine_civitai_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.imagine_civitai_description),
            modifier = Modifier.widthIn(max = 340.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                lineBreak = LineBreak.Paragraph.copy(strategy = LineBreak.Strategy.Balanced)
            ),
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = {},
            enabled = false,
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = colors.surfaceContainerHigh,
                disabledContentColor = colors.onSurfaceVariant
            )
        ) {
            Text(stringResource(R.string.imagine_civitai_connect))
        }
    }
}
