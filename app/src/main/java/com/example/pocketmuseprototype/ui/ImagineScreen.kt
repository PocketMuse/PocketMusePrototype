package com.example.pocketmuseprototype.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            CivitaiAccess()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) {
                            Box(
                                Modifier.weight(1f).aspectRatio(3f / 4f)
                                    .background(colors.surfaceContainer, MaterialTheme.shapes.large)
                            )
                        }
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            shape = CircleShape,
            color = colors.surfaceContainerLow,
            contentColor = colors.onSurfaceVariant,
            border = BorderStroke(1.dp, colors.outline)
        ) {
            Text(
                text = stringResource(R.string.imagine_prompt),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyLarge
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
