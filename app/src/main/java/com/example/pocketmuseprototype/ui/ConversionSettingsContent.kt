package com.example.pocketmuseprototype.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.conversion.HQQ_ITERATION_CHOICES
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversionSettingsContent(
    iterations: Int?,
    onRetryRead: (() -> Unit)?,
    onIterationsChange: suspend (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var draft by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<Int?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val current = draft ?: pending ?: iterations
    val label = stringResource(R.string.conversion_hqq_iterations)
    val currentLabel = current?.let { stringResource(R.string.conversion_hqq_count, it) }
    val choices = HQQ_ITERATION_CHOICES
    val thumbSize = 12.dp
    LaunchedEffect(iterations, saving) {
        if (!saving && iterations == pending) pending = null
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Text(stringResource(R.string.conversion_settings_title), style = typography.headlineSmall)
        Text(label, modifier = Modifier.padding(top = 32.dp), style = typography.titleSmall)
        Slider(
            value = (current ?: choices.first).toFloat(),
            onValueChange = { draft = it.roundToInt() },
            onValueChangeFinished = {
                val selected = draft
                draft = null
                if (selected != null && selected != (pending ?: iterations)) {
                    pending = selected
                    saving = true
                    saveFailed = false
                    saveJob?.cancel()
                    saveJob = scope.launch {
                        try {
                            onIterationsChange(selected)
                        } catch (_: IOException) {
                            pending = null
                            saveFailed = true
                        }
                        saving = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = label
                    currentLabel?.let { stateDescription = it }
                },
            enabled = iterations != null,
            valueRange = choices.first.toFloat()..choices.last.toFloat(),
            steps = choices.count() - 2,
            thumb = {
                Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(thumbSize)
                            .drawBehind {
                                if (focused) drawCircle(colors.primary, radius = 9.dp.toPx(), style = Stroke(1.dp.toPx()))
                            }
                            .background(if (iterations == null) colors.outline else colors.primary, CircleShape)
                    )
                }
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawLine(colors.outline, Offset(0f, center.y), Offset(size.width, center.y),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    choices.forEachIndexed { index, _ ->
                        val x = size.width * index / (choices.count() - 1)
                        drawCircle(colors.outline, radius = 2.dp.toPx(), center = Offset(x, center.y))
                    }
                }
            }
        )
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                choices.forEach { Text(it.toString(), color = colors.onSurfaceVariant, style = typography.labelSmall) }
            }
        ) { measurables, constraints ->
            val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            val width = constraints.maxWidth
            val thumbWidth = thumbSize.roundToPx()
            layout(width, labels.maxOf { it.height }) {
                labels.forEachIndexed { index, label ->
                    val center = thumbWidth / 2 + (width - thumbWidth) * index / labels.lastIndex
                    label.placeRelative(center - label.width / 2, 0)
                }
            }
        }
        Text(
            stringResource(R.string.conversion_hqq_description),
            modifier = Modifier.padding(top = 12.dp),
            style = typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        if (saveFailed) {
            Text(stringResource(R.string.conversion_hqq_save_error), color = colors.error, style = typography.bodySmall)
        }
        if (onRetryRead != null) {
            Text(stringResource(R.string.conversion_hqq_read_error), color = colors.error, style = typography.bodySmall)
            TextButton(onClick = onRetryRead) { Text(stringResource(R.string.conversion_hqq_read_retry)) }
        }
    }
}
