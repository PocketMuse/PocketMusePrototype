package com.example.pocketmuseprototype.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.auth.AuthState
import com.example.pocketmuseprototype.catalog.CatalogCheckpoint
import com.example.pocketmuseprototype.catalog.MarketViewModel
import com.example.pocketmuseprototype.download.LibraryItem
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.SharedLcmState
import com.example.pocketmuseprototype.gallery.GalleryImage
import com.example.pocketmuseprototype.ui.theme.BrandFontFamily
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private enum class AppTab(@get:StringRes val label: Int) {
    Chat(R.string.tab_chat),
    Market(R.string.tab_market),
    Library(R.string.tab_models)
}
private val ContentMaxWidth = 560.dp

@Composable
internal fun PocketMuseApp(
    authState: AuthState,
    onConnect: () -> Unit,
    onRetryAuth: () -> Unit,
    onDisconnect: () -> Unit,
    marketViewModel: MarketViewModel = viewModel(factory = MarketViewModel.Factory),
    library: List<LibraryItem> = emptyList(),
    sharedLcm: SharedLcmState = SharedLcmState.Missing,
    onRetryLcm: () -> Unit = {},
    onCancelLcm: () -> Unit = {},
    onDeleteLcm: () -> Unit = {},
    onDownloadRequest: ((CatalogCheckpoint) -> Unit)? = null,
    onRemoveModel: (LibraryModel) -> Unit = {},
    onStartConversion: (LibraryModel) -> Unit = {},
    onCancelConversion: (LibraryModel) -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    libraryRequest: Int = 0,
    hqqIterations: Int? = null,
    onRetryHqqSettings: (() -> Unit)? = null,
    onHqqIterationsChange: suspend (Int) -> Unit = {},
    chatMessages: List<ChatGeneration> = emptyList(),
    onGenerateImage: ((ChatImageRequest) -> Unit)? = null,
    onCancelGeneration: (() -> Unit)? = null,
    gallery: Flow<List<GalleryImage>>? = null,
    onDeleteImage: suspend (String) -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Chat) }
    val tabStateHolder = rememberSaveableStateHolder()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(libraryRequest) {
        if (libraryRequest > 0) {
            focusManager.clearFocus()
            selectedTab = AppTab.Library
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.8f).fillMaxHeight(),
                drawerContainerColor = colors.surfaceContainer
            ) {
                val drawerSemantics = if (drawerState.isClosed && !drawerState.isAnimationRunning) {
                    Modifier.clearAndSetSemantics {}
                } else Modifier
                GalleryPanel(
                    gallery = gallery.takeIf { drawerState.isOpen || drawerState.isAnimationRunning },
                    onDelete = onDeleteImage,
                    modifier = drawerSemantics.fillMaxSize(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.conversion_settings_title),
                            fontFamily = BrandFontFamily,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        HorizontalDivider()
                        ConversionSettingsContent(hqqIterations, onRetryHqqSettings, onHqqIterationsChange)
                    }
                }
            }
        }
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppHeader(
                        selectedTab = selectedTab,
                        onTabSelected = {
                            if (it != selectedTab) {
                                focusManager.clearFocus()
                                selectedTab = it
                            }
                        },
                        onOpenMenu = {
                            focusManager.clearFocus()
                            scope.launch { drawerState.open() }
                        },
                        modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()
                    )
                    when (selectedTab) {
                        AppTab.Chat -> tabStateHolder.SaveableStateProvider(AppTab.Chat.name) {
                            ChatScreen(
                                library = library,
                                modifier = Modifier.weight(1f).widthIn(max = ContentMaxWidth).fillMaxWidth(),
                                messages = chatMessages,
                                onGenerate = onGenerateImage,
                                onCancelGeneration = onCancelGeneration,
                            )
                        }
                        AppTab.Market -> tabStateHolder.SaveableStateProvider(AppTab.Market.name) {
                            MarketScreen(
                                viewModel = marketViewModel,
                                authState = authState,
                                onConnect = onConnect,
                                onRetryAuth = onRetryAuth,
                                onDisconnect = onDisconnect,
                                onDownloadRequest = onDownloadRequest.takeIf { authState is AuthState.Connected },
                                modifier = Modifier.weight(1f).widthIn(max = ContentMaxWidth)
                                    .fillMaxWidth()
                            )
                        }
                        AppTab.Library -> tabStateHolder.SaveableStateProvider(AppTab.Library.name) {
                            LibraryScreen(
                                library, onRemoveModel, onStartConversion, onCancelConversion,
                                Modifier.weight(1f).widthIn(max = ContentMaxWidth).fillMaxWidth(),
                                sharedLcm, onRetryLcm, onCancelLcm, onDeleteLcm
                            )
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = ContentMaxWidth)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppHeader(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(
            onClick = onOpenMenu,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.surfaceContainer,
                contentColor = colors.onSurface
            )
        ) {
            Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.open_menu))
        }
        PrimaryTabRow(
            selectedTabIndex = selectedTab.ordinal,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            containerColor = Color.Transparent,
            divider = {}
        ) {
            AppTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.heightIn(min = 56.dp),
                    selectedContentColor = colors.onSurface,
                    unselectedContentColor = colors.onSurfaceVariant
                ) {
                    Text(
                        text = stringResource(tab.label),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
