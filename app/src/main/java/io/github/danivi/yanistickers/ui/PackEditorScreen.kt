package io.github.danivi.yanistickers.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.StickerPack
import io.github.danivi.yanistickers.validation.PackValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackEditorScreen(
    viewModel: PackEditorViewModel,
    onBack: () -> Unit,
    onAddStaticSticker: () -> Unit,
    onAddAnimatedSticker: () -> Unit,
    onChangeTrayIcon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var editingSticker by remember { mutableStateOf<EditorStickerUi?>(null) }
    var stickerPendingDelete by remember { mutableStateOf<EditorStickerUi?>(null) }
    var detailStickerFile by remember { mutableStateOf<String?>(null) }

    val triggerAddToWhatsApp = rememberWhatsAppAddAction(
        installedTargets = state.installedTargets,
        snackbarHostState = snackbarHostState,
        onCompleted = { viewModel.refresh(showLoading = false) },
    )

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    val detailSticker = detailStickerFile?.let { targetFile ->
        state.stickers.firstOrNull { it.item.file == targetFile }
    }
    if (detailSticker != null) {
        StickerDetailDialog(
            sticker = detailSticker,
            onEdit = {
                detailStickerFile = null
                editingSticker = detailSticker
            },
            onSetAsTray = {
                detailStickerFile = null
                viewModel.setStickerAsTrayIcon(detailSticker.index)
            },
            onDelete = {
                detailStickerFile = null
                stickerPendingDelete = detailSticker
            },
            onDismiss = { detailStickerFile = null },
        )
    }

    editingSticker?.let { target ->
        EditStickerDialog(
            sticker = target,
            animatedPack = state.pack?.animated == true,
            onSave = { name, emojisRaw, a11yText ->
                editingSticker = null
                viewModel.updateSticker(target.index, name, emojisRaw, a11yText)
            },
            onDismiss = { editingSticker = null },
        )
    }

    stickerPendingDelete?.let { target ->
        val displayTitle = target.item.name.ifBlank { "#${target.index + 1}" }
        AlertDialog(
            onDismissRequest = { stickerPendingDelete = null },
            title = { Text("Eliminar sticker") },
            text = {
                Text(
                    "¿Quieres quitar el sticker «$displayTitle» del pack? " +
                        "El archivo local también se borrará.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        stickerPendingDelete = null
                        viewModel.removeSticker(target.index)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { stickerPendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.pack?.name ?: stringResource(R.string.editor_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.packNotFound || state.pack == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No se encontró el pack solicitado.")
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onBack) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                }
            }
            else -> {
                val pack = state.pack!!
                val errors = remember(state.validation) { state.validation?.allErrorMessages().orEmpty() }
                val isValid = errors.isEmpty()

                if (state.isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (errors.isNotEmpty()) {
                            item(key = "pack_warning_banner", span = { GridItemSpan(maxLineSpan) }) {
                                EditorWarningBannerCard(errors = errors)
                            }
                        }

                        item(key = "metadata_card", span = { GridItemSpan(maxLineSpan) }) {
                            MetadataEditorCard(
                                state = state,
                                onNameChange = viewModel::onNameChange,
                                onPublisherChange = viewModel::onPublisherChange,
                                onChangeTrayIcon = onChangeTrayIcon,
                                onSave = viewModel::saveMetadata,
                            )
                        }

                        item(key = "stickers_header", span = { GridItemSpan(maxLineSpan) }) {
                            StickersSectionHeader(
                                pack = pack,
                                isGridView = true,
                                onToggleView = viewModel::toggleViewMode,
                                onAddSticker = {
                                    if (pack.animated) {
                                        onAddAnimatedSticker()
                                    } else {
                                        onAddStaticSticker()
                                    }
                                },
                            )
                        }

                        if (state.stickers.isEmpty()) {
                            item(key = "stickers_empty", span = { GridItemSpan(maxLineSpan) }) {
                                EmptyStickersCard()
                            }
                        } else {
                            gridItems(
                                items = state.stickers,
                                key = { it.item.file },
                            ) { sticker ->
                                StickerGridTileCard(
                                    sticker = sticker,
                                    onClick = { detailStickerFile = sticker.item.file },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }

                        item(key = "whatsapp_footer", span = { GridItemSpan(maxLineSpan) }) {
                            WhatsAppFooterButton(
                                isValid = isValid,
                                onClick = { triggerAddToWhatsApp(pack) },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (errors.isNotEmpty()) {
                            item(key = "pack_warning_banner") {
                                EditorWarningBannerCard(errors = errors)
                            }
                        }

                        item(key = "metadata_card") {
                            MetadataEditorCard(
                                state = state,
                                onNameChange = viewModel::onNameChange,
                                onPublisherChange = viewModel::onPublisherChange,
                                onChangeTrayIcon = onChangeTrayIcon,
                                onSave = viewModel::saveMetadata,
                            )
                        }

                        item(key = "stickers_header") {
                            StickersSectionHeader(
                                pack = pack,
                                isGridView = false,
                                onToggleView = viewModel::toggleViewMode,
                                onAddSticker = {
                                    if (pack.animated) {
                                        onAddAnimatedSticker()
                                    } else {
                                        onAddStaticSticker()
                                    }
                                },
                            )
                        }

                        if (state.stickers.isEmpty()) {
                            item(key = "stickers_empty") {
                                EmptyStickersCard()
                            }
                        } else {
                            items(
                                items = state.stickers,
                                key = { it.item.file },
                            ) { sticker ->
                                StickerEditorRowCard(
                                    sticker = sticker,
                                    canMoveUp = sticker.index > 0,
                                    canMoveDown = sticker.index < state.stickers.lastIndex,
                                    onEdit = { editingSticker = sticker },
                                    onSetAsTray = { viewModel.setStickerAsTrayIcon(sticker.index) },
                                    onMoveUp = { viewModel.moveStickerUp(sticker.index) },
                                    onMoveDown = { viewModel.moveStickerDown(sticker.index) },
                                    onDelete = { stickerPendingDelete = sticker },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }

                        item(key = "whatsapp_footer") {
                            WhatsAppFooterButton(
                                isValid = isValid,
                                onClick = { triggerAddToWhatsApp(pack) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickersSectionHeader(
    pack: StickerPack,
    isGridView: Boolean,
    onToggleView: () -> Unit,
    onAddSticker: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Stickers (${pack.stickers.size}/${PackValidator.MAX_STICKERS})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleView) {
            Icon(
                imageVector = if (isGridView) {
                    Icons.AutoMirrored.Outlined.ViewList
                } else {
                    Icons.Outlined.GridView
                },
                contentDescription = stringResource(R.string.action_toggle_view),
            )
        }
        OutlinedButton(
            onClick = onAddSticker,
            enabled = pack.stickers.size < PackValidator.MAX_STICKERS,
        ) {
            Icon(
                imageVector = Icons.Outlined.AddPhotoAlternate,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.action_add_sticker))
        }
    }
}

@Composable
private fun EmptyStickersCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Este pack todavía no tiene stickers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun WhatsAppFooterButton(
    isValid: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Spacer(modifier = Modifier.height(4.dp))
        AddToWhatsAppButton(
            enabled = isValid,
            onClick = onClick,
        )
    }
}

@Composable
private fun StickerGridTileCard(
    sticker: EditorStickerUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            LocalStickerThumbnail(
                file = sticker.assetFile,
                contentDescription = sticker.item.name.ifBlank { "Sticker #${sticker.index + 1}" },
                size = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun StickerDetailDialog(
    sticker: EditorStickerUi,
    onEdit: () -> Unit,
    onSetAsTray: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = sticker.item.name.ifBlank { "Sticker #${sticker.index + 1}" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LocalStickerThumbnail(
                    file = sticker.assetFile,
                    contentDescription = title,
                    size = 192.dp,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_edit))
                    }
                    OutlinedButton(
                        onClick = onSetAsTray,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SentimentSatisfied,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_set_as_tray))
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.action_delete))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        },
    )
}

@Composable
private fun EditorWarningBannerCard(errors: List<String>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "No se puede añadir a WhatsApp todavía",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                errors.forEach { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataEditorCard(
    state: PackEditorUiState,
    onNameChange: (String) -> Unit,
    onPublisherChange: (String) -> Unit,
    onChangeTrayIcon: () -> Unit,
    onSave: () -> Unit,
) {
    val pack = state.pack ?: return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocalStickerThumbnail(
                    file = state.trayFile,
                    contentDescription = pack.trayImageFile,
                    size = 52.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (pack.animated) "Pack animado" else "Pack estático",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onChangeTrayIcon) {
                    Text(stringResource(R.string.action_change_tray))
                }
            }

            OutlinedTextField(
                value = state.nameInput,
                onValueChange = onNameChange,
                label = { Text("Nombre del pack") },
                singleLine = true,
                supportingText = { Text("${state.nameInput.trim().length}/${PackValidator.MAX_TEXT_LENGTH}") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.publisherInput,
                onValueChange = onPublisherChange,
                label = { Text("Autor") },
                singleLine = true,
                supportingText = { Text("${state.publisherInput.trim().length}/${PackValidator.MAX_TEXT_LENGTH}") },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onSave,
                enabled = state.canSaveMetadata,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerEditorRowCard(
    sticker: EditorStickerUi,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onSetAsTray: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = sticker.validation
    val assetLabel = report?.assetName ?: sticker.item.file

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(
                start = 12.dp,
                top = 12.dp,
                end = 12.dp,
                bottom = 2.dp,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocalStickerThumbnail(
                    file = sticker.assetFile,
                    contentDescription = assetLabel,
                    size = 64.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sticker.item.name.ifBlank { "Sticker #${sticker.index + 1}" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sticker.item.emojis.isEmpty()) {
                            Text(
                                text = "Sin emojis",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            sticker.item.emojis.forEach { emoji ->
                                AssistChip(
                                    onClick = onEdit,
                                    label = { Text(emoji) },
                                )
                            }
                        }
                    }
                }
            }

            if (report != null && report.errors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                report.errors.forEach { issue ->
                    Text(
                        text = "• ${issue.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSetAsTray) {
                    Text(stringResource(R.string.action_use_as_tray_short))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Editar",
                        )
                    }
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowUpward,
                            contentDescription = "Subir",
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowDownward,
                            contentDescription = "Bajar",
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditStickerDialog(
    sticker: EditorStickerUi,
    animatedPack: Boolean,
    onSave: (name: String, emojisRaw: String, accessibilityText: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameText by remember(sticker) { mutableStateOf(sticker.item.name) }
    var emojisText by remember(sticker) { mutableStateOf(sticker.item.emojis.joinToString(" ")) }
    var a11yText by remember(sticker) { mutableStateOf(sticker.item.accessibilityText) }

    val parsedEmojis = remember(emojisText) { PackJsonParser.splitEmojisGraphemes(emojisText) }
    val maxA11y = if (animatedPack) PackValidator.MAX_ANIMATED_A11Y_LENGTH else PackValidator.MAX_STATIC_A11Y_LENGTH
    val emojisValid = parsedEmojis.size in PackValidator.MIN_EMOJIS..PackValidator.MAX_EMOJIS
    val a11yValid = a11yText.trim().length <= maxA11y
    val dialogTitle = sticker.item.name.ifBlank { "Sticker #${sticker.index + 1}" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar $dialogTitle") },
        text = {
            StickerMetadataEditorFields(
                nameText = nameText,
                onNameTextChange = { nameText = it },
                emojisText = emojisText,
                onEmojisTextChange = { emojisText = it },
                accessibilityText = a11yText,
                onAccessibilityTextChange = { a11yText = it },
                animatedPack = animatedPack,
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(nameText, emojisText, a11yText) },
                enabled = emojisValid && a11yValid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
