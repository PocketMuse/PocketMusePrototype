package com.example.pocketmuseprototype.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscreteSlider(
    value: Int,
    choices: IntProgression,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val thumbSize = 12.dp
    val intervals = choices.count() - 1
    var focused by remember { mutableStateOf(false) }
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused },
        enabled = enabled,
        valueRange = choices.first.toFloat()..choices.last.toFloat(),
        steps = intervals - 1,
        thumb = {
            Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(thumbSize)
                        .drawBehind {
                            if (focused) {
                                drawCircle(colors.primary, radius = 9.dp.toPx(), style = Stroke(1.dp.toPx()))
                            }
                        }
                        .background(if (enabled) colors.primary else colors.outline, CircleShape)
                )
            }
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                drawLine(
                    colors.outline, Offset(0f, center.y), Offset(size.width, center.y),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                )
                for (index in 0..intervals) {
                    val x = size.width * index / intervals
                    drawCircle(colors.outline, radius = 2.dp.toPx(), center = Offset(x, center.y))
                }
            }
        }
    )
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            choices.forEach {
                Text(it.toString(), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    ) { measurables, constraints ->
        val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val thumbWidth = thumbSize.roundToPx()
        layout(width, labels.maxOf { it.height }) {
            labels.forEachIndexed { index, label ->
                val center = thumbWidth / 2 + (width - thumbWidth) * index / intervals
                label.placeRelative(center - label.width / 2, 0)
            }
        }
    }
}
