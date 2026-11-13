package com.example.pocketmuseprototype.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.download.DownloadStatus
import com.example.pocketmuseprototype.download.LibraryItem
import com.example.pocketmuseprototype.download.LibraryModel
import com.example.pocketmuseprototype.download.ModelConversionStatus
import com.example.pocketmuseprototype.download.STATIC_LCM_RECIPE
import com.example.pocketmuseprototype.runtime.DEFAULT_STATIC_LCM_STEPS
import com.example.pocketmuseprototype.runtime.StaticLcmSteps
import java.io.File

/** One independent prompt, with the selected Library storage key and denoising step count. */
internal data class ChatImageRequest(val modelKey: String, val modelName: String, val steps: Int, val prompt: String)

internal data class ChatPreview(val step: Int, val total: Int, val image: ImageBitmap)

internal data class ChatGeneration(val id: Long, val prompt: String, val status: Status) {
    sealed interface Status {
        /** Step counts describe denoising only. Other stages are indeterminate. */
        data class Running(
            @param:StringRes val message: Int = R.string.chat_preparing,
            val step: Int = 0,
            val total: Int = 0,
            val preview: ChatPreview? = null,
        ) : Status
        data object Cancelling : Status
        data class Saving(val preview: ChatPreview?) : Status
        data class Complete(val file: File) : Status
        data class SaveFailed(val image: ImageBitmap? = null) : Status
        data class Failed(@param:StringRes val message: Int) : Status
        data object Cancelled : Status
    }
}

/**
 * The caller owns generation and supplies its session state. A null [onGenerate] disables sending.
 * Both event callbacks run on the UI thread. The caller schedules generation and handles
 * [onCancelGeneration] for the active request.
 */
@Composable
internal fun ChatScreen(
    library: List<LibraryItem>,
    modifier: Modifier = Modifier,
    messages: List<ChatGeneration> = emptyList(),
    onGenerate: ((ChatImageRequest) -> Unit)? = null,
    onCancelGeneration: (() -> Unit)? = null
) {
    val models = remember(library) {
        library.map { it.model }.filter {
            it.downloadStatus == DownloadStatus.Downloaded &&
                it.conversionStatus == ModelConversionStatus.Converted &&
                it.conversionRecipeId == STATIC_LCM_RECIPE
        }
    }
    var modelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var savedSteps by rememberSaveable { mutableIntStateOf(DEFAULT_STATIC_LCM_STEPS) }
    val steps = savedSteps.takeIf { it in StaticLcmSteps } ?: DEFAULT_STATIC_LCM_STEPS
    var draft by rememberSaveable { mutableStateOf("") }
    val model = models.find { it.storageKey == modelKey } ?: models.singleOrNull()
    LaunchedEffect(model?.storageKey) {
        if (model != null) modelKey = model.storageKey
    }
    val status = messages.lastOrNull()?.status
    val isGenerating = status is ChatGeneration.Status.Running ||
        status == ChatGeneration.Status.Cancelling || status is ChatGeneration.Status.Saving
    val request = model?.takeIf { draft.isNotBlank() && !isGenerating }?.let {
        ChatImageRequest(it.storageKey, it.modelName, steps, draft)
    }
    val focusManager = LocalFocusManager.current
    var showModels by remember(isGenerating) { mutableStateOf(false) }

    Column(modifier.padding(bottom = 12.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (messages.isNotEmpty()) {
                ChatContent(messages)
            } else if (onGenerate == null || models.isEmpty()) {
                Text(
                    stringResource(if (onGenerate == null) R.string.chat_generation_unavailable else R.string.chat_models_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = { showModels = true }, enabled = !isGenerating) {
            Text(
                model?.modelName ?: stringResource(R.string.chat_select_model),
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null)
        }
        ChatComposer(
            draft = draft,
            onDraftChange = { draft = it },
            steps = steps,
            onStepsChange = { savedSteps = it },
            isGenerating = isGenerating,
            canSend = onGenerate != null && request != null,
            isCancelling = status == ChatGeneration.Status.Cancelling,
            isSaving = status is ChatGeneration.Status.Saving,
            onCancel = onCancelGeneration,
            onSend = {
                if (request != null && onGenerate != null) {
                    focusManager.clearFocus()
                    onGenerate(request)
                    draft = ""
                }
            }
        )
    }
    if (showModels) {
        ChatModelDialog(
            models = models,
            selected = model?.storageKey,
            onDismiss = { showModels = false },
            onSelect = {
                modelKey = it
                showModels = false
            }
        )
    }
}

@Composable
private fun ChatModelDialog(
    models: List<LibraryModel>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_models_title)) },
        text = {
            if (models.isEmpty()) {
                Text(stringResource(R.string.chat_models_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp).selectableGroup()) {
                    items(models, key = { it.storageKey }) { model ->
                        val isSelected = model.storageKey == selected
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(isSelected, role = Role.RadioButton) { onSelect(model.storageKey) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Column {
                                Text(model.modelName, style = MaterialTheme.typography.bodyLarge)
                                Text(model.versionName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close_models)) }
        }
    )
}

@Composable
private fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    isGenerating: Boolean,
    canSend: Boolean,
    isCancelling: Boolean,
    isSaving: Boolean,
    onCancel: (() -> Unit)?,
    onSend: () -> Unit
) {
    var showSteps by remember(isGenerating) { mutableStateOf(false) }
    var composerHeight by remember { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val inputLabel = stringResource(R.string.chat_input)
    val stepLabel = stringResource(R.string.chat_steps)
    val stepCount = stringResource(R.string.chat_step_count, steps)
    Box(Modifier.onSizeChanged { composerHeight = it.height }) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = inputLabel },
            placeholder = { Text(stringResource(R.string.chat_placeholder)) },
            trailingIcon = {
                Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { showSteps = !showSteps },
                        enabled = !isGenerating,
                        modifier = Modifier.semantics {
                            contentDescription = stepLabel
                            stateDescription = stepCount
                        }
                    ) {
                        Text(steps.toString())
                    }
                    val actionEnabled = if (isGenerating) !isCancelling && !isSaving && onCancel != null else canSend
                    OutlinedIconButton(
                        onClick = { if (isGenerating) onCancel?.invoke() else onSend() },
                        enabled = actionEnabled,
                        border = BorderStroke(1.dp, if (actionEnabled) colors.primary else colors.outline),
                        colors = IconButtonDefaults.outlinedIconButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = colors.primary,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = colors.outline
                        ),
                    ) {
                        Icon(
                            painterResource(if (isGenerating) R.drawable.ic_stop else R.drawable.ic_arrow_up),
                            stringResource(when {
                                isSaving -> R.string.chat_saving_image
                                isCancelling -> R.string.chat_cancelling_generation
                                isGenerating -> R.string.chat_cancel_generation
                                else -> R.string.chat_send
                            })
                        )
                    }
                }
            },
            maxLines = 4,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceContainerLow,
                unfocusedContainerColor = colors.surfaceContainerLow
            )
        )
        if (showSteps) {
            ChatStepPopup(steps, composerHeight, onStepsChange, onDismiss = { showSteps = false })
        }
    }
}

@Composable
private fun ChatStepPopup(
    steps: Int,
    composerHeight: Int,
    onStepsChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val gap = 8.dp
    val dismissAreaHeight = with(LocalDensity.current) { composerHeight.toDp() } + gap
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val start = if (layoutDirection == LayoutDirection.Ltr) {
                    anchorBounds.right - popupContentSize.width
                } else {
                    anchorBounds.left
                }
                return IntOffset(
                    start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    (anchorBounds.bottom - popupContentSize.height).coerceAtLeast(0)
                )
            }
        }
    }
    // A non-focusable popup keeps an active prompt keyboard in place.
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss) {
        Column(Modifier.width(216.dp)) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val stepCount = stringResource(R.string.chat_step_count, steps)
                    DiscreteSlider(
                        value = steps,
                        choices = StaticLcmSteps,
                        onValueChange = onStepsChange,
                        modifier = Modifier.semantics { stateDescription = stepCount }
                    )
                }
            }
            // Consume taps over the anchor so dismissal cannot also reopen the popup.
            Box(
                Modifier.fillMaxWidth().height(dismissAreaHeight)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClickLabel = stringResource(R.string.chat_close_steps),
                        onClick = onDismiss
                    )
            )
        }
    }
}

@Composable
private fun ChatContent(messages: List<ChatGeneration>) {
    val listState = rememberLazyListState()
    val latestId = messages.last().id
    var previousId by remember { mutableLongStateOf(latestId) }
    LaunchedEffect(latestId) {
        if (previousId != latestId) {
            previousId = latestId
            listState.animateScrollToItem(0)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 16.dp),
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom)
    ) {
        items(messages.asReversed(), key = { it.id }) { generation ->
            ChatMessage(generation)
        }
    }
}

@Composable
private fun ChatMessage(generation: ChatGeneration) {
    var placeholder by remember { mutableStateOf<ImageBitmap?>(null) }
    val status = generation.status
    if (status !is ChatGeneration.Status.Complete) {
        SideEffect {
            placeholder = when (status) {
                is ChatGeneration.Status.Running -> status.preview?.image
                is ChatGeneration.Status.Saving -> status.preview?.image
                else -> null
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.8f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    generation.prompt,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        Column(Modifier.fillMaxWidth(0.8f)) {
            val imageArea = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
            when (status) {
                is ChatGeneration.Status.Running ->
                    ChatGenerationProgress(imageArea, status.preview, status.message, status.step, status.total)
                ChatGeneration.Status.Cancelling -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.chat_cancelling_generation))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is ChatGeneration.Status.Saving ->
                    ChatGenerationProgress(imageArea, status.preview, R.string.chat_saving_image)
                is ChatGeneration.Status.Complete -> {
                    val context = LocalContext.current
                    val request = remember(context, status.file) {
                        ImageRequest.Builder(context).data(status.file)
                            .memoryCachePolicy(CachePolicy.DISABLED).build()
                    }
                    var unavailable by remember(status.file) { mutableStateOf(false) }
                    var loaded by remember(status.file) { mutableStateOf(false) }
                    if (unavailable) {
                        Text(stringResource(R.string.gallery_image_unavailable))
                    } else {
                        AsyncImage(
                            model = request,
                            contentDescription = if (loaded) stringResource(R.string.chat_generated_image) else null,
                            modifier = imageArea,
                            placeholder = remember(placeholder) { placeholder?.let { BitmapPainter(it) } },
                            onSuccess = {
                                loaded = true
                                placeholder = null
                            },
                            onError = {
                                placeholder = null
                                unavailable = true
                            }
                        )
                    }
                }
                is ChatGeneration.Status.SaveFailed -> {
                    status.image?.let {
                        Image(
                            bitmap = it,
                            contentDescription = stringResource(R.string.chat_generated_image),
                            modifier = imageArea
                        )
                    }
                    Text(stringResource(R.string.chat_image_save_failed), color = MaterialTheme.colorScheme.error)
                }
                is ChatGeneration.Status.Failed -> Text(stringResource(status.message), color = MaterialTheme.colorScheme.error)
                ChatGeneration.Status.Cancelled -> Text(stringResource(R.string.chat_generation_cancelled))
            }
        }
    }
}

@Composable
private fun ChatGenerationProgress(
    imageArea: Modifier,
    preview: ChatPreview?,
    @StringRes message: Int,
    step: Int = 0,
    total: Int = 0,
) {
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    Box(imageArea.background(background)) {
        if (preview != null) {
            Image(
                bitmap = preview.image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth().background(background).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (preview != null) {
                Text(
                    stringResource(R.string.chat_image_preview, preview.step, preview.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview == null && total == 0 &&
                message != R.string.chat_finishing_image && message != R.string.chat_saving_image
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(message), style = MaterialTheme.typography.labelMedium)
                }
            } else if (total > 0) {
                LinearProgressIndicator({ step.toFloat() / total }, Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
