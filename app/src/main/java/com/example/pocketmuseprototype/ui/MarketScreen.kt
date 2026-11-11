package com.example.pocketmuseprototype.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.catalog.CatalogCheckpoint
import com.example.pocketmuseprototype.catalog.LoadStatus
import com.example.pocketmuseprototype.catalog.MarketState
import com.example.pocketmuseprototype.catalog.MarketViewModel
import kotlinx.coroutines.flow.drop

private enum class MarketCategory(@get:StringRes val label: Int) {
    Text(R.string.market_text),
    Image(R.string.market_image)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketScreen(
    viewModel: MarketViewModel,
    modifier: Modifier = Modifier,
    onDownloadRequest: ((CatalogCheckpoint) -> Unit)? = null
) {
    var category by rememberSaveable { mutableStateOf(MarketCategory.Image) }
    val galleryState = rememberLazyGridState()
    val textListState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme

    Column(modifier) {
        SecondaryTabRow(
            selectedTabIndex = category.ordinal,
            modifier = Modifier.padding(top = 8.dp),
            containerColor = Color.Transparent
        ) {
            MarketCategory.entries.forEach { item ->
                Tab(
                    selected = item == category,
                    onClick = { category = item },
                    text = { Text(stringResource(item.label)) },
                    selectedContentColor = colors.onSurface,
                    unselectedContentColor = colors.onSurfaceVariant
                )
            }
        }
        when (category) {
            MarketCategory.Text -> TextModelList(state = textListState, modifier = Modifier.weight(1f))
            MarketCategory.Image -> {
                val catalog by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(viewModel) { viewModel.start() }
                Text(
                    text = stringResource(
                        if (onDownloadRequest == null) R.string.market_catalog_preview else R.string.market_catalog
                    ),
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                ImageGallery(
                    catalog = catalog,
                    gridState = galleryState,
                    onPrefetch = viewModel::prefetch,
                    onLoadMore = viewModel::loadMore,
                    onDownloadRequest = onDownloadRequest,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ImageGallery(
    catalog: MarketState,
    gridState: LazyGridState,
    onPrefetch: () -> Unit,
    onLoadMore: () -> Unit,
    onDownloadRequest: ((CatalogCheckpoint) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val currentCatalog by rememberUpdatedState(catalog)
    val prefetch by rememberUpdatedState(onPrefetch)
    LaunchedEffect(gridState) {
        // React to scroll position changes. Loading or appending a batch alone should not queue a load.
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                val lastCard = gridState.layoutInfo.visibleItemsInfo.lastOrNull { item -> item.key is Long }
                if (currentCatalog.status == LoadStatus.Ready && lastCard != null &&
                    lastCard.index >= currentCatalog.models.size - PREFETCH_CARD_COUNT
                ) {
                    prefetch()
                }
            }
    }

    val cardSpacing = Arrangement.spacedBy(8.dp)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 12.dp),
        horizontalArrangement = cardSpacing,
        verticalArrangement = cardSpacing
    ) {
        items(catalog.models, key = { it.modelId }) { checkpoint ->
            CheckpointCard(checkpoint, onDownloadRequest)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            CatalogFooter(catalog, onLoadMore)
        }
    }
}

@Composable
private fun CatalogFooter(catalog: MarketState, onLoadMore: () -> Unit) {
    val empty = catalog.models.isEmpty()
    val loading = catalog.status == LoadStatus.Idle || catalog.status == LoadStatus.Loading
    val message = when (catalog.status) {
        LoadStatus.Idle, LoadStatus.Loading ->
            if (catalog.loadedInitialBatch) R.string.market_loading_more else R.string.market_loading
        LoadStatus.Error ->
            if (catalog.loadedInitialBatch) R.string.market_load_more_error else R.string.market_load_error
        LoadStatus.End -> if (empty) R.string.market_empty else R.string.market_end
        LoadStatus.MoreAvailable -> if (empty) R.string.market_empty_so_far else null
        LoadStatus.Ready -> null
    }
    val action = when (catalog.status) {
        LoadStatus.Error -> R.string.market_retry
        LoadStatus.Ready, LoadStatus.MoreAvailable -> R.string.market_load_more
        else -> null
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = if (empty) 24.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        if (message != null) {
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = if (catalog.status == LoadStatus.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            TextButton(onClick = onLoadMore) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun CheckpointCard(
    checkpoint: CatalogCheckpoint,
    onDownloadRequest: ((CatalogCheckpoint) -> Unit)?
) {
    val size = Formatter.formatShortFileSize(LocalContext.current, (checkpoint.sizeKB * 1024).toLong())
    val scrim = Color.Black.copy(alpha = 0.76f)
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 4f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_image),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).size(32.dp)
        )
        AsyncImage(
            model = checkpoint.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Column(Modifier.align(Alignment.BottomCenter)) {
            Spacer(
                Modifier.fillMaxWidth().height(32.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, scrim)))
            )
            Row(
                Modifier.fillMaxWidth().background(scrim).padding(start = 10.dp, end = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        checkpoint.modelName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        checkpoint.versionName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(size, color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                FilledIconButton(
                    onClick = { onDownloadRequest?.invoke(checkpoint) },
                    enabled = onDownloadRequest != null,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        disabledContainerColor = Color.White.copy(alpha = 0.12f),
                        disabledContentColor = Color.White.copy(alpha = 0.64f)
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = stringResource(
                            if (onDownloadRequest == null) R.string.market_download_unavailable else R.string.market_download
                        )
                    )
                }
            }
        }
    }
}

private const val PREFETCH_CARD_COUNT = 4
