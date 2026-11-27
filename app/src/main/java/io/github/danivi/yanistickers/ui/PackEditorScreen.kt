package io.github.danivi.yanistickers.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.material3.SuggestionChip
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
import io.github.danivi.yanistickers.validation.PackValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackEditorScreen(
    viewModel: PackEditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingSticker by remember { mutableStateOf<EditorStickerUi?>(null) }
    var stickerPendingDelete by remember { mutableStateOf<EditorStickerUi?>(null) }

    val triggerAddToWhatsApp = rememberWhatsAppAddAction(
        installedTargets = state.installedTargets,
        snackbarHostState = snackbarHostState,
        onCompleted = { viewModel.refresh() },
    )

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    editingSticker?.let { target ->
        EditStickerDialog(
            sticker = target,
            animatedPack = state.pack?.animated == true,
            onSave = { emojisRaw, a11yText ->
                editingSticker = null
                viewModel.updateSticker(target.index, emojisRaw, a11yText)
            },
            onDismiss = { editingSticker = null },
        )
    }

    stickerPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { stickerPendingDelete = null },
            title = { Text("Eliminar sticker") },
            text = {
                Text(
                    "¿Quieres quitar el sticker #${target.index + 1} del pack? " +
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

                LazyColumn(
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
                            onSave = viewModel::saveMetadata,
                        )
                    }

                    item(key = "stickers_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Stickers (${pack.stickers.size}/${PackValidator.MAX_STICKERS})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            OutlinedButton(onClick = viewModel::notifyAddStickerUpcoming) {
                                Icon(
                                    imageVector = Icons.Outlined.AddPhotoAlternate,
                                    contentDescription = null,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.action_add_sticker))
                            }
                        }
                    }

                    if (state.stickers.isEmpty()) {
                        item(key = "stickers_empty") {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Este pack todavía no tiene stickers.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    } else {
                        items(
                            items = state.stickers,
                            key = { "${it.index}:${it.item.file}" },
                        ) { sticker ->
                            StickerEditorRowCard(
                                sticker = sticker,
                                canMoveUp = sticker.index > 0,
                                canMoveDown = sticker.index < state.stickers.lastIndex,
                                onEdit = { editingSticker = sticker },
                                onMoveUp = { viewModel.moveStickerUp(sticker.index) },
                                onMoveDown = { viewModel.moveStickerDown(sticker.index) },
                                onDelete = { stickerPendingDelete = sticker },
                            )
                        }
                    }

                    item(key = "whatsapp_footer") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { triggerAddToWhatsApp(pack) },
                            enabled = isValid,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.action_add_to_whatsapp))
                        }
                    }
                }
            }
        }
    }
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
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val report = sticker.validation
    val assetLabel = report?.assetName ?: sticker.item.file

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocalStickerThumbnail(
                    file = sticker.assetFile,
                    contentDescription = assetLabel,
                    size = 64.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sticker #${sticker.index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
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
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Editar emojis",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditStickerDialog(
    sticker: EditorStickerUi,
    animatedPack: Boolean,
    onSave: (emojisRaw: String, accessibilityText: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var emojisText by remember(sticker) { mutableStateOf(sticker.item.emojis.joinToString(" ")) }
    var a11yText by remember(sticker) { mutableStateOf(sticker.item.accessibilityText) }

    val parsedEmojis = remember(emojisText) { PackJsonParser.splitEmojisGraphemes(emojisText) }
    val maxA11y = if (animatedPack) PackValidator.MAX_ANIMATED_A11Y_LENGTH else PackValidator.MAX_STATIC_A11Y_LENGTH
    val emojisValid = parsedEmojis.size in PackValidator.MIN_EMOJIS..PackValidator.MAX_EMOJIS
    val a11yValid = a11yText.trim().length <= maxA11y
    val quickEmojis = listOf("😀", "😂", "❤️", "🔥", "🎉", "👍", "✨", "🥳")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar sticker #${sticker.index + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = emojisText,
                    onValueChange = { emojisText = it },
                    label = { Text("Emojis (entre 1 y 3)") },
                    singleLine = true,
                    isError = !emojisValid,
                    supportingText = {
                        Text("Detectados ${parsedEmojis.size}/${PackValidator.MAX_EMOJIS}: ${parsedEmojis.joinToString(" ")}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    quickEmojis.forEach { emoji ->
                        SuggestionChip(
                            onClick = {
                                if (parsedEmojis.size < PackValidator.MAX_EMOJIS) {
                                    emojisText = (parsedEmojis + emoji).joinToString(" ")
                                }
                            },
                            label = { Text(emoji) },
                        )
                    }
                }
                OutlinedTextField(
                    value = a11yText,
                    onValueChange = { if (it.length <= maxA11y) a11yText = it },
                    label = { Text("Descripción de accesibilidad (opcional)") },
                    supportingText = { Text("${a11yText.trim().length}/$maxA11y") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(emojisText, a11yText) },
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
