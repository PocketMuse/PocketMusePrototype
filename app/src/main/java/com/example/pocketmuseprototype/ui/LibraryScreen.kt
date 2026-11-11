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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
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
import com.example.pocketmuseprototype.download.TransferProgress

@Composable
internal fun LibraryScreen(
    library: List<LibraryItem>,
    onRemove: (LibraryModel) -> Unit,
    onStartConversion: (LibraryModel) -> Unit,
    onCancelConversion: (LibraryModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val (transfers, downloaded) = library.partition {
        val model = it.model
        model.downloadStatus.pending || model.downloadStatus == DownloadStatus.Cancelling ||
            model.downloadStatus == DownloadStatus.Deleting ||
            (model.downloadStatus == DownloadStatus.Downloaded &&
                (model.conversionStatus.pending || model.conversionStatus == ModelConversionStatus.Cancelling))
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SectionTitle(R.string.library_downloads) }
        if (transfers.isEmpty()) item { EmptySection(R.string.library_downloads_empty) }
        items(transfers, key = { it.model.storageKey }) { ModelCard(it, onRemove, onStartConversion, onCancelConversion) }

        item { SectionTitle(R.string.library_models, Modifier.padding(top = 16.dp)) }
        if (downloaded.isEmpty()) item { EmptySection(R.string.library_models_empty) }
        items(downloaded, key = { it.model.storageKey }) { ModelCard(it, onRemove, onStartConversion, onCancelConversion) }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(title),
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge
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
private fun ModelCard(
    item: LibraryItem,
    onRemove: (LibraryModel) -> Unit,
    onStartConversion: (LibraryModel) -> Unit,
    onCancelConversion: (LibraryModel) -> Unit
) {
    val model = item.model
    val status = model.downloadStatus
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(64.dp).clip(MaterialTheme.shapes.medium)
                        .background(colors.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_image), null, tint = colors.onSurfaceVariant)
                    if (item.thumbnail != null) {
                        AsyncImage(item.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        model.modelName, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        model.versionName, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val statusLabel = stringResource(status.label)
            val message = when (status) {
                DownloadStatus.Downloaded -> stringResource(
                    R.string.library_downloaded_size, statusLabel, Formatter.formatShortFileSize(context, model.fileSize)
                )
                else -> statusLabel
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        message, style = MaterialTheme.typography.labelLarge,
                        color = if (status == DownloadStatus.Failed) colors.error else colors.onSurfaceVariant
                    )
                    if (model.errorCode != null) {
                        Text(
                            stringResource(model.errorCode.label), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                if (status.pending || status == DownloadStatus.Failed ||
                    (status == DownloadStatus.Deleting && model.errorCode != null)
                ) {
                    TextButton(onClick = { onRemove(model) }) {
                        Text(stringResource(if (status.pending) R.string.library_cancel else R.string.library_delete))
                    }
                }
            }
            if (status == DownloadStatus.Downloading || status == DownloadStatus.Verifying) {
                DownloadProgress(TransferProgress(model.downloadedBytes, model.totalBytes))
            }
            if (status == DownloadStatus.Downloaded) {
                ConversionDetails(model, onStartConversion, onCancelConversion, onRemove)
            }
        }
    }
}

@Composable
private fun ConversionDetails(
    model: LibraryModel,
    onStart: (LibraryModel) -> Unit,
    onCancel: (LibraryModel) -> Unit,
    onRemove: (LibraryModel) -> Unit
) {
    val status = model.conversionStatus
    val colors = MaterialTheme.colorScheme
    val percent = model.conversionProgress
    val label = if (status == ModelConversionStatus.Converting && percent != null) {
        stringResource(R.string.library_conversion_percent, percent)
    } else stringResource(status.label)
    Text(label, style = MaterialTheme.typography.labelLarge,
        color = if (status == ModelConversionStatus.Failed) colors.error else colors.onSurfaceVariant)
    if (status == ModelConversionStatus.Failed) {
        Text(stringResource(model.conversionErrorCode.conversionErrorLabel), style = MaterialTheme.typography.bodySmall)
    }
    if (status.pending) {
        if (percent == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
    }
    if (status != ModelConversionStatus.Cancelling) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (status.canStart) {
                TextButton(onClick = { onStart(model) }) {
                    Text(stringResource(if (status == ModelConversionStatus.NotStarted) {
                        R.string.library_convert
                    } else R.string.library_convert_retry))
                }
            } else if (status.pending) {
                TextButton(onClick = { onCancel(model) }) { Text(stringResource(R.string.library_cancel)) }
            }
            TextButton(onClick = { onRemove(model) }) { Text(stringResource(R.string.library_delete)) }
        }
    }
}

private val ModelConversionStatus.label: Int
    @StringRes get() = when (this) {
        ModelConversionStatus.NotStarted -> R.string.library_conversion_not_started
        ModelConversionStatus.Queued -> R.string.library_conversion_queued
        ModelConversionStatus.Validating -> R.string.library_conversion_validating
        ModelConversionStatus.Converting -> R.string.library_converting
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
private fun DownloadProgress(progress: TransferProgress) {
    val context = LocalContext.current
    val fraction = progress.fraction
    val transferred = Formatter.formatShortFileSize(context, progress.bytes)
    if (fraction != null && progress.total != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.library_progress, transferred, Formatter.formatShortFileSize(context, progress.total)),
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.library_bytes, transferred), style = MaterialTheme.typography.bodySmall)
    }
}

private val DownloadStatus.label: Int
    @StringRes get() = when (this) {
        DownloadStatus.Queued -> R.string.library_queued
        DownloadStatus.Downloading -> R.string.library_downloading
        DownloadStatus.Verifying -> R.string.library_verifying
        DownloadStatus.Downloaded -> R.string.library_downloaded
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
