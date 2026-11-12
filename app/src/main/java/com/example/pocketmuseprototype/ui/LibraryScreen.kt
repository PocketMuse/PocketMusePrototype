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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
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
import com.example.pocketmuseprototype.download.LibraryItem
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.ModelConversionStatus
import com.example.pocketmuseprototype.download.Sd15LcmLora
import com.example.pocketmuseprototype.download.SharedLcmState
import com.example.pocketmuseprototype.download.TransferProgress

private val LibraryThumbnailSize = 44.dp
private val LibraryColumnSpacing = 10.dp

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
    var optimizationActionsFor by remember { mutableStateOf<LibraryModel?>(null) }
    val openOptimizationActions: (LibraryModel) -> Unit = { optimizationActionsFor = it }
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
        items(transfers, key = { it.model.storageKey }) {
            ModelCard(it, onRemove, onStartConversion, openOptimizationActions)
        }

        item { SectionTitle(R.string.library_models, Modifier.padding(top = 16.dp)) }
        if (downloaded.isEmpty() && !lcmReady) item { EmptySection(R.string.library_models_empty) }
        if (lcmReady) item(key = Sd15LcmLora.ID) { SharedLcmCard(sharedLcm, onRetryLcm, onCancelLcm, onDeleteLcm) }
        items(downloaded, key = { it.model.storageKey }) {
            ModelCard(it, onRemove, onStartConversion, openOptimizationActions)
        }
    }
    optimizationActionsFor?.let { openedFor ->
        val model = library.firstOrNull {
            it.model.storageKey == openedFor.storageKey && it.model.workId == openedFor.workId &&
                it.model.conversionWorkId == openedFor.conversionWorkId
        }?.model
        if (model != null && model.downloadStatus == DownloadStatus.Downloaded &&
            (model.conversionStatus.canCancel || model.conversionStatus == ModelConversionStatus.Converted)
        ) {
            fun submit(action: (LibraryModel) -> Unit) {
                if (optimizationActionsFor == null) return
                optimizationActionsFor = null
                action(model)
            }
            OptimizationActionsDialog(
                name = model.modelName,
                canCancel = model.conversionStatus.canCancel,
                onDismiss = { optimizationActionsFor = null },
                onCancel = { submit(onCancelConversion) },
                onDelete = { submit(onRemove) }
            )
        }
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
    status: String? = null,
    error: String? = null,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LibraryColumnSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(LibraryThumbnailSize).clip(MaterialTheme.shapes.medium)
                        .background(colors.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    thumbnail()
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    SizeLabel(size ?: stringResource(R.string.library_size_unknown), status)
                    content()
                }
                Box(Modifier.widthIn(min = ButtonDefaults.MinWidth), contentAlignment = Alignment.Center) {
                    action()
                }
            }
            if (error != null) {
                Text(error, Modifier.padding(top = 8.dp), color = colors.error, style = MaterialTheme.typography.bodySmall)
            }
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
                else -> CancelDownloadButton(transfer?.fraction, title, onCancel)
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
    onOpenOptimizationActions: (LibraryModel) -> Unit
) {
    val model = item.model
    val status = model.downloadStatus
    val conversion = model.conversionStatus
    val downloaded = status == DownloadStatus.Downloaded
    val transfer = if (status == DownloadStatus.Downloading) {
        TransferProgress(model.downloadedBytes, model.totalBytes)
    } else null
    val bytes = if (status == DownloadStatus.Verifying) model.downloadedBytes else model.fileSize
    val colors = MaterialTheme.colorScheme
    val statusText = when {
        !downloaded -> status.label?.let { stringResource(it) }
        conversion == ModelConversionStatus.Converting && model.conversionProgress != null ->
            stringResource(R.string.library_conversion_percent, model.conversionProgress)
        model.canStartConversion && conversion != ModelConversionStatus.Failed ->
            stringResource(R.string.library_optimization_needed)
        else -> conversion.label?.let { stringResource(it) }
    }
    LibraryCard(
        title = model.modelName,
        description = model.versionName,
        size = transfer?.let { transferSize(it) } ?: bytes.takeIf { it > 0 }?.let {
            Formatter.formatShortFileSize(LocalContext.current, it)
        },
        status = statusText,
        error = if (downloaded && conversion == ModelConversionStatus.Failed) {
            stringResource(model.conversionErrorCode.conversionErrorLabel)
        } else null,
        thumbnail = {
            Icon(painterResource(R.drawable.ic_image), null, tint = colors.onSurfaceVariant)
            if (item.thumbnail != null) {
                AsyncImage(item.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        },
        action = {
            when {
                status.pending -> CancelDownloadButton(
                    transfer?.fraction,
                    model.modelName
                ) { onRemove(model) }
                downloaded && conversion.canCancel -> {
                    val fraction = when (conversion) {
                        ModelConversionStatus.WaitingForLcm -> 0f
                        ModelConversionStatus.Converting -> model.conversionProgress?.div(100f)?.coerceIn(0f, 1f)
                        else -> null
                    }
                    ProgressActionButton(
                        fraction,
                        stringResource(R.string.library_optimization_actions, model.modelName)
                    ) { onOpenOptimizationActions(model) }
                }
                (downloaded && conversion != ModelConversionStatus.Cancelling) ||
                    status == DownloadStatus.Failed || (status == DownloadStatus.Deleting && model.errorCode != null) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (downloaded && model.canStartConversion) {
                            val label = when (conversion) {
                                ModelConversionStatus.Failed, ModelConversionStatus.Cancelled -> R.string.library_convert_retry
                                else -> R.string.library_convert
                            }
                            LibraryActionButton(label, colors.primary) { onStartConversion(model) }
                        }
                        DeleteButton(model.modelName) { onRemove(model) }
                    }
            }
        }
    ) {
        when {
            downloaded && model.legacyOutput != null && conversion != ModelConversionStatus.Converted ->
                Text(stringResource(R.string.library_previous_output), style = MaterialTheme.typography.bodySmall)
            model.errorCode != null ->
                Text(
                    stringResource(model.errorCode.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
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
        R.string.library_delete_question, R.string.library_confirm_delete, onClick, destructive = true
    ) { requestConfirmation ->
        LibraryActionButton(R.string.library_delete, MaterialTheme.colorScheme.error, onClick = requestConfirmation)
    }
}

@Composable
private fun LibraryActionButton(
    @StringRes label: Int,
    color: Color,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.widthIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CancelDownloadButton(fraction: Float?, name: String, onCancel: () -> Unit) {
    val label = stringResource(R.string.library_cancel_download, name)
    ConfirmAction(
        label, R.string.library_cancel_download_question, R.string.library_confirm_cancel_download, onCancel
    ) { openConfirmation ->
        ProgressActionButton(fraction, label, openConfirmation)
    }
}

@Composable
private fun ProgressActionButton(fraction: Float?, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val strokeWidth = 3.dp
    IconButton(
        onClick = onClick,
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

@Composable
private fun OptimizationActionsDialog(
    name: String,
    canCancel: Boolean,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Text(stringResource(
                if (canCancel) R.string.library_optimization_actions_description
                else R.string.library_optimization_delete_description
            ))
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canCancel) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.library_confirm_cancel_conversion))
                    }
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error)
                ) {
                    Text(stringResource(R.string.library_delete_model))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.library_dismiss_confirmation))
                }
            }
        }
    )
}

@Composable
private fun ConfirmAction(
    title: String,
    @StringRes message: Int,
    @StringRes confirmLabel: Int,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
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
                TextButton(
                    onClick = {
                        if (open) {
                            open = false
                            onConfirm()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) { Text(stringResource(confirmLabel)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.library_dismiss_confirmation)) }
            }
        )
    }
}

private val ModelConversionStatus.label: Int?
    @StringRes get() = when (this) {
        ModelConversionStatus.NotStarted, ModelConversionStatus.Cancelled, ModelConversionStatus.Converted -> null
        ModelConversionStatus.Queued -> R.string.library_optimization_queued
        ModelConversionStatus.WaitingForLcm -> R.string.library_conversion_waiting_lcm
        ModelConversionStatus.Validating -> R.string.library_conversion_validating
        ModelConversionStatus.Converting -> R.string.library_converting
        ModelConversionStatus.Finalizing -> R.string.library_conversion_finalizing
        ModelConversionStatus.Failed -> R.string.library_conversion_failed
        ModelConversionStatus.Cancelling -> R.string.library_conversion_cancelling
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
private fun SizeLabel(size: String, status: String?) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val text = if (status == null) size else stringResource(R.string.library_size_and_status, size, status)
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
