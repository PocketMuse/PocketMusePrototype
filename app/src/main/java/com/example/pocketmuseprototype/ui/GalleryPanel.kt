package com.example.pocketmuseprototype.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.gallery.GalleryImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
internal fun GalleryPanel(
    gallery: Flow<List<GalleryImage>>?,
    onDelete: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    var loadFailed by remember(gallery) { mutableStateOf(false) }
    val observed = remember(gallery) { gallery?.catch { loadFailed = true } }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = observed?.collectAsStateWithLifecycle(null)?.value
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "settings", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (gallery != null) {
            item(key = "galleryTitle", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.gallery_title),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (loadFailed || rows.isNullOrEmpty()) {
                item(key = "galleryStatus", span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        loadFailed -> Text(stringResource(R.string.gallery_load_failed), color = MaterialTheme.colorScheme.error)
                        rows == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        else -> Text(stringResource(R.string.gallery_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(rows, key = { it.record.id }) { image ->
                    GalleryPicture(
                        image,
                        Modifier.aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(role = Role.Button) { selectedId = image.record.id }
                    )
                }
            }
        }
    }
    rows?.find { it.record.id == selectedId }?.let { image ->
        GalleryDetail(image, onDismiss = { selectedId = null }, onDelete = onDelete)
    }
}

@Composable
private fun GalleryDetail(image: GalleryImage, onDismiss: () -> Unit, onDelete: suspend (String) -> Unit) {
    val record = image.record
    val scope = rememberCoroutineScope()
    var confirmDelete by rememberSaveable(record.id) { mutableStateOf(false) }
    var deleting by remember(record.id) { mutableStateOf(false) }
    var deleteFailed by remember(record.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(R.string.gallery_image_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GalleryPicture(image, Modifier.fillMaxWidth().aspectRatio(1f), original = true)
                Text(stringResource(R.string.gallery_model, record.modelName))
                Text(stringResource(R.string.gallery_prompt, record.prompt))
                Text(stringResource(R.string.gallery_steps, record.steps))
                if (deleteFailed) {
                    Text(stringResource(R.string.gallery_delete_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirmDelete = true }, enabled = !deleting) {
                Text(stringResource(R.string.gallery_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text(stringResource(R.string.gallery_close)) }
        }
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.gallery_confirm_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    deleteFailed = false
                    scope.launch {
                        try {
                            onDelete(record.id)
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            deleteFailed = true
                        } finally {
                            deleting = false
                        }
                    }
                }) { Text(stringResource(R.string.gallery_delete_image), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.gallery_cancel)) }
            }
        )
    }
}

@Composable
private fun GalleryPicture(image: GalleryImage, modifier: Modifier, original: Boolean = false) {
    val context = LocalContext.current
    val request = remember(context, image.file, original) {
        ImageRequest.Builder(context).data(image.file).apply {
            if (original) {
                size(Size.ORIGINAL)
                memoryCachePolicy(CachePolicy.DISABLED)
            }
        }.build()
    }
    var unavailable by remember(request) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (unavailable) {
            Text(stringResource(R.string.gallery_image_unavailable), style = MaterialTheme.typography.bodySmall)
        } else {
            AsyncImage(
                model = request,
                contentDescription = stringResource(R.string.gallery_thumbnail, image.record.prompt),
                modifier = Modifier.matchParentSize(),
                contentScale = if (original) ContentScale.Fit else ContentScale.Crop,
                onError = { unavailable = true }
            )
        }
    }
}
