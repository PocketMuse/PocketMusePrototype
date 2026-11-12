package com.example.pocketmuseprototype.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.download.DownloadError
import com.example.pocketmuseprototype.download.DownloadStatus
import com.example.pocketmuseprototype.download.LEGACY_HQQ_RECIPE
import com.example.pocketmuseprototype.download.LibraryItem
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.ModelConversionStatus
import com.example.pocketmuseprototype.download.Sd15LcmLora
import com.example.pocketmuseprototype.download.SharedLcmState
import com.example.pocketmuseprototype.download.TransferProgress

@Composable
internal fun LibraryScreen(
    library: List<LibraryItem>,
    onRemove: (LibraryModel) -> Unit,
    onStartConversion: (LibraryModel) -> Unit,
    onCancelConversion: (LibraryModel) -> Unit,
    modifier: Modifier = Modifier,
    sharedLcm: SharedLcmState = SharedLcmState.Missing,
    onRetryLcm: () -> Unit = {},
    onCancelLcm: () -> Unit = {},
    onDeleteLcm: () -> Unit = {}
) {
    val (transfers, downloaded) = library.partition {
        val model = it.model
        model.downloadStatus.pending || model.downloadStatus == DownloadStatus.Cancelling ||
            model.downloadStatus == DownloadStatus.Deleting ||
            (model.downloadStatus == DownloadStatus.Downloaded &&
                (model.conversionStatus.canCancel || model.conversionStatus == ModelConversionStatus.Cancelling))
    }
    val lcmReady = sharedLcm == SharedLcmState.Ready
    val lcmInDownloads = sharedLcm != SharedLcmState.Missing && !lcmReady
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SectionTitle(R.string.library_downloads) }
        if (transfers.isEmpty() && !lcmInDownloads) item { EmptySection(R.string.library_downloads_empty) }
        if (sharedLcm == SharedLcmState.Missing &&
            transfers.any { it.model.conversionStatus == ModelConversionStatus.WaitingForLcm }
        ) {
            item { TextButton(onClick = onRetryLcm) { Text(stringResource(R.string.library_lcm_download)) } }
        }
        if (lcmInDownloads) item(key = Sd15LcmLora.ID) { SharedLcmCard(sharedLcm, onRetryLcm, onCancelLcm, onDeleteLcm) }
        items(transfers, key = { it.model.storageKey }) { ModelCard(it, onRemove, onStartConversion, onCancelConversion) }

        item { SectionTitle(R.string.library_models, Modifier.padding(top = 16.dp)) }
        if (downloaded.isEmpty() && !lcmReady) item { EmptySection(R.string.library_models_empty) }
        if (lcmReady) item(key = Sd15LcmLora.ID) { SharedLcmCard(sharedLcm, onRetryLcm, onCancelLcm, onDeleteLcm) }
        items(downloaded, key = { it.model.storageKey }) { ModelCard(it, onRemove, onStartConversion, onCancelConversion) }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(title),
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptySection(@StringRes message: Int) {
    Text(
        stringResource(message),
        modifier = Modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LibraryCard(
    title: String,
    description: String,
    size: String?,
    thumbnail: @Composable () -> Unit,
    action: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                thumbnail()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                SizeLabel(size ?: stringResource(R.string.library_size_unknown))
                content()
            }
            action()
        }
    }
}

@Composable
private fun SharedLcmCard(
    state: SharedLcmState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val title = stringResource(R.string.library_lcm_name)
    val description = stringResource(R.string.library_lcm_description)
    val transfer = (state as? SharedLcmState.Downloading)?.let { TransferProgress(it.bytes, Sd15LcmLora.SIZE) }
    val label = when (state) {
        SharedLcmState.Missing -> return
        SharedLcmState.Queued, is SharedLcmState.Downloading, SharedLcmState.Ready -> null
        SharedLcmState.Verifying -> R.string.library_verifying
        is SharedLcmState.Failed -> R.string.library_failed
    }
    LibraryCard(
        title = title,
        description = description,
        size = transfer?.let { transferSize(it) }
            ?: Formatter.formatShortFileSize(LocalContext.current, Sd15LcmLora.SIZE),
        thumbnail = { Text("LR", color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium) },
        action = {
            when (state) {
                SharedLcmState.Ready -> DeleteButton(title, onDelete)
                is SharedLcmState.Failed -> TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.library_lcm_retry))
                }
                else -> CancelProgressButton(
                    transfer?.fraction,
                    stringResource(R.string.library_cancel_download, title),
                    onCancel
                )
            }
        }
    ) {
        label?.let { LibraryStatus(stringResource(it), isError = state is SharedLcmState.Failed) }
        if (state is SharedLcmState.Failed) {
            val error = when (state.error) {
                DownloadError.AuthRequired, DownloadError.AuthUnavailable, DownloadError.Forbidden,
                DownloadError.NotFound, DownloadError.RateLimited, DownloadError.Server -> R.string.library_lcm_source_error
                else -> state.error.label
            }
            Text(stringResource(error), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelCard(
    item: LibraryItem,
    onRemove: (LibraryModel) -> Unit,
    onStartConversion: (LibraryModel) -> Unit,
    onCancelConversion: (LibraryModel) -> Unit
) {
    val model = item.model
    val status = model.downloadStatus
    val conversion = model.conversionStatus
    val downloaded = status == DownloadStatus.Downloaded
    val transfer = if (status == DownloadStatus.Downloading || status == DownloadStatus.Verifying) {
        TransferProgress(model.downloadedBytes, model.totalBytes)
    } else null
    val colors = MaterialTheme.colorScheme
    LibraryCard(
        title = model.modelName,
        description = model.versionName,
        size = transfer?.let { transferSize(it) } ?: model.fileSize.takeIf { it > 0 }?.let {
            Formatter.formatShortFileSize(LocalContext.current, it)
        },
        thumbnail = {
            Icon(painterResource(R.drawable.ic_image), null, tint = colors.onSurfaceVariant)
            if (item.thumbnail != null) {
                AsyncImage(item.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        },
        action = {
            when {
                status.pending -> CancelProgressButton(
                    when (status) {
                        DownloadStatus.Downloading -> transfer?.fraction
                        else -> null
                    },
                    stringResource(R.string.library_cancel_download, model.modelName),
                    onCancel = { onRemove(model) }
                )
                downloaded && conversion.canCancel -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CancelProgressButton(
                        when (conversion) {
                            ModelConversionStatus.WaitingForLcm -> 0f
                            ModelConversionStatus.Converting -> model.conversionProgress?.div(100f)?.coerceIn(0f, 1f)
                            else -> null
                        },
                        stringResource(R.string.library_cancel_conversion, model.modelName),
                        onCancel = { onCancelConversion(model) },
                        message = R.string.library_cancel_conversion_question,
                        confirmLabel = R.string.library_confirm_cancel_conversion
                    )
                    DeleteButton(model.modelName) { onRemove(model) }
                }
                (downloaded && conversion != ModelConversionStatus.Cancelling) ||
                    status == DownloadStatus.Failed || (status == DownloadStatus.Deleting && model.errorCode != null) ->
                    DeleteButton(model.modelName) { onRemove(model) }
            }
        }
    ) {
        if (downloaded) {
            ConversionDetails(model, onStartConversion)
        } else {
            status.label?.let { LibraryStatus(stringResource(it), isError = status == DownloadStatus.Failed) }
        }
        model.errorCode?.let {
            Text(stringResource(it.label), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConversionDetails(
    model: LibraryModel,
    onStart: (LibraryModel) -> Unit
) {
    val status = model.conversionStatus
    val percent = model.conversionProgress
    val label = when {
        status == ModelConversionStatus.Converting && percent != null ->
            stringResource(R.string.library_conversion_percent, percent)
        status == ModelConversionStatus.Converted && model.conversionRecipeId == LEGACY_HQQ_RECIPE ->
            stringResource(R.string.library_converted_legacy)
        else -> status.label?.let { stringResource(it) }
    }
    label?.let { LibraryStatus(it, isError = status == ModelConversionStatus.Failed) }
    if (status == ModelConversionStatus.Failed) {
        Text(stringResource(model.conversionErrorCode.conversionErrorLabel), style = MaterialTheme.typography.bodySmall)
    }
    if (model.legacyOutput != null && status != ModelConversionStatus.Converted) {
        Text(stringResource(R.string.library_previous_output), style = MaterialTheme.typography.bodySmall)
    }
    if (model.canStartConversion) {
        TextButton(onClick = { onStart(model) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(stringResource(when (status) {
                ModelConversionStatus.NotStarted -> R.string.library_convert
                ModelConversionStatus.Converted -> R.string.library_convert_static_lcm
                else -> R.string.library_convert_retry
            }))
        }
    }
}

@Composable
private fun LibraryStatus(text: String, isError: Boolean) {
    Text(
        text, style = MaterialTheme.typography.labelLarge,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DeleteButton(name: String, onClick: () -> Unit) {
    ConfirmAction(
        stringResource(R.string.library_delete_title, name),
        R.string.library_delete_question, R.string.library_confirm_delete, onClick
    ) { requestConfirmation ->
        TextButton(
            onClick = requestConfirmation,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        ) {
            Text(stringResource(R.string.library_delete))
        }
    }
}

@Composable
private fun CancelProgressButton(
    fraction: Float?,
    label: String,
    onCancel: () -> Unit,
    @StringRes message: Int = R.string.library_cancel_download_question,
    @StringRes confirmLabel: Int = R.string.library_confirm_cancel_download
) {
    val colors = MaterialTheme.colorScheme
    val strokeWidth = 3.dp
    ConfirmAction(label, message, confirmLabel, onCancel) { requestConfirmation ->
        IconButton(
            onClick = requestConfirmation,
            modifier = Modifier.size(width = ButtonDefaults.MinWidth, height = 48.dp).semantics {
                progressBarRangeInfo = if (fraction == null) ProgressBarRangeInfo.Indeterminate
                else ProgressBarRangeInfo(fraction, 0f..1f)
            }
        ) {
            val indicator = Modifier.size(36.dp).clearAndSetSemantics {}
            if (fraction == null) {
                CircularProgressIndicator(
                    modifier = indicator, color = colors.onSurfaceVariant, trackColor = colors.outline, strokeWidth = strokeWidth
                )
            } else {
                CircularProgressIndicator(
                    progress = { fraction }, modifier = indicator,
                    color = colors.onSurfaceVariant, trackColor = colors.outline, strokeWidth = strokeWidth
                )
            }
            Icon(painterResource(R.drawable.ic_stop), label, tint = colors.onSurface)
        }
    }
}

@Composable
private fun ConfirmAction(
    title: String,
    @StringRes message: Int,
    @StringRes confirmLabel: Int,
    onConfirm: () -> Unit,
    button: @Composable (() -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    button { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = {
                    if (open) {
                        open = false
                        onConfirm()
                    }
                }) { Text(stringResource(confirmLabel)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.library_dismiss_confirmation)) }
            }
        )
    }
}

private val ModelConversionStatus.label: Int?
    @StringRes get() = when (this) {
        ModelConversionStatus.NotStarted, ModelConversionStatus.Queued -> null
        ModelConversionStatus.WaitingForLcm -> R.string.library_conversion_waiting_lcm
        ModelConversionStatus.Validating -> R.string.library_conversion_validating
        ModelConversionStatus.Converting -> R.string.library_conversion_stage
        ModelConversionStatus.Finalizing -> R.string.library_conversion_finalizing
        ModelConversionStatus.Converted -> R.string.library_converted
        ModelConversionStatus.Failed -> R.string.library_conversion_failed
        ModelConversionStatus.Cancelling -> R.string.library_conversion_cancelling
        ModelConversionStatus.Cancelled -> R.string.library_conversion_cancelled
    }

private val String?.conversionErrorLabel: Int
    @StringRes get() = when (this) {
        "INSUFFICIENT_SPACE" -> R.string.library_error_space
        "SOURCE_INVALID" -> R.string.library_error_checkpoint
        "TENSOR_MISMATCH", "INVALID_INPUT", "NON_FINITE_WEIGHT" -> R.string.library_conversion_error_checkpoint
        "INCOMPATIBLE_TEMPLATE", "TEMPLATE_INVALID" -> R.string.library_conversion_error_template
        "INTERRUPTED", "SCHEDULING" -> R.string.library_conversion_error_interrupted
        "OUTPUT_INVALID" -> R.string.library_conversion_error_output
        "CLEANUP_FAILED", "STALE_OUTPUT" -> R.string.library_conversion_error_cleanup
        else -> R.string.library_conversion_error_generic
    }

@Composable
private fun SizeLabel(text: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun transferSize(progress: TransferProgress): String {
    val context = LocalContext.current
    val transferred = Formatter.formatShortFileSize(context, progress.bytes)
    val total = progress.total?.takeIf { it > 0 }
    return if (total == null) stringResource(R.string.library_bytes, transferred)
    else stringResource(R.string.library_progress, transferred, Formatter.formatShortFileSize(context, total))
}

private val DownloadStatus.label: Int?
    @StringRes get() = when (this) {
        DownloadStatus.Queued, DownloadStatus.Downloading, DownloadStatus.Downloaded -> null
        DownloadStatus.Verifying -> R.string.library_verifying
        DownloadStatus.Failed -> R.string.library_failed
        DownloadStatus.Cancelling -> R.string.library_cancelling
        DownloadStatus.Deleting -> R.string.library_deleting
    }

private val DownloadError.label: Int
    @StringRes get() = when (this) {
        DownloadError.AuthRequired -> R.string.library_error_auth
        DownloadError.AuthUnavailable -> R.string.library_error_auth_unavailable
        DownloadError.Forbidden -> R.string.library_error_forbidden
        DownloadError.NotFound -> R.string.library_error_not_found
        DownloadError.RateLimited -> R.string.library_error_rate_limit
        DownloadError.Server -> R.string.library_error_server
        DownloadError.Network -> R.string.library_error_network
        DownloadError.InvalidMetadata -> R.string.library_error_metadata
        DownloadError.InvalidLength -> R.string.library_error_length
        DownloadError.InvalidHash -> R.string.library_error_hash
        DownloadError.InvalidCheckpoint -> R.string.library_error_checkpoint
        DownloadError.StorageUnavailable -> R.string.library_error_storage
        DownloadError.NoSpace -> R.string.library_error_space
        DownloadError.FileWrite -> R.string.library_error_write
        DownloadError.MissingFile -> R.string.library_error_missing
        DownloadError.Cleanup -> R.string.library_error_cleanup
        DownloadError.Scheduling -> R.string.library_error_schedule
        DownloadError.Interrupted -> R.string.library_error_interrupted
    }
