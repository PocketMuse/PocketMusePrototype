package com.example.pocketmuseprototype.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.conversion.HQQ_ITERATION_CHOICES
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    val current = draft ?: pending ?: iterations
    val label = stringResource(R.string.conversion_hqq_iterations)
    val currentLabel = current?.let { stringResource(R.string.conversion_hqq_count, it) }
    val choices = HQQ_ITERATION_CHOICES
    LaunchedEffect(iterations, saving) {
        if (!saving && iterations == pending) pending = null
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Text(stringResource(R.string.conversion_settings_title), style = typography.headlineSmall)
        Text(label, modifier = Modifier.padding(top = 32.dp), style = typography.titleSmall)
        DiscreteSlider(
            value = current ?: choices.first,
            choices = choices,
            onValueChange = { draft = it },
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
            modifier = Modifier.semantics {
                contentDescription = label
                currentLabel?.let { stateDescription = it }
            },
            enabled = iterations != null
        )
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
