package io.github.danivi.yanistickers.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.ui.theme.AddedBadgeBorder
import io.github.danivi.yanistickers.ui.theme.AddedBadgeContainer
import io.github.danivi.yanistickers.ui.theme.AnimatedBadgeBorder
import io.github.danivi.yanistickers.ui.theme.AnimatedBadgeContainer
import io.github.danivi.yanistickers.ui.theme.IconActionContainer
import io.github.danivi.yanistickers.ui.theme.OnAddedBadgeContainer
import io.github.danivi.yanistickers.ui.theme.OnAnimatedBadgeContainer
import io.github.danivi.yanistickers.ui.theme.OnIconActionContainer
import io.github.danivi.yanistickers.ui.theme.OnStaticBadgeContainer
import io.github.danivi.yanistickers.ui.theme.StaticBadgeBorder
import io.github.danivi.yanistickers.ui.theme.StaticBadgeContainer
import io.github.danivi.yanistickers.validation.PackValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackListScreen(
    viewModel: PackListViewModel,
    onOpenEditor: (String) -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val playMeow = rememberMeowSoundPlayer()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var packPendingDelete by remember { mutableStateOf<PackSummaryUi?>(null) }
    var pendingExportPackId by remember { mutableStateOf<String?>(null) }

    val exportSingleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val packId = pendingExportPackId
        pendingExportPackId = null
        if (uri != null && packId != null) {
            viewModel.exportPackToUri(packId, uri)
        }
    }

    val triggerAddToWhatsApp = rememberWhatsAppAddAction(
        installedTargets = state.installedTargets,
        snackbarHostState = snackbarHostState,
        onCompleted = viewModel::refresh,
    )

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    if (showCreateDialog) {
        CreatePackDialog(
            onCreate = { name, publisher, animated ->
                showCreateDialog = false
                viewModel.createPack(name, publisher, animated, onCreated = onOpenEditor)
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    packPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { packPendingDelete = null },
            title = { Text("Borrar pack") },
            text = {
                Text(
                    "¿Seguro que quieres eliminar \"${target.pack.name}\" y todos sus archivos locales? " +
                        "Esta acción no se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        packPendingDelete = null
                        viewModel.deletePack(target.pack.identifier)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { packPendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.app_logo),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp),
                            )
                        }
                        Text(stringResource(R.string.pack_list_title))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = stringResource(R.string.action_sort_packs),
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            PackSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    trailingIcon = if (state.sortOption == option) {
                                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        showSortMenu = false
                                        viewModel.setSortOption(option)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenBackup) {
                        Icon(
                            imageVector = Icons.Outlined.Archive,
                            contentDescription = stringResource(R.string.action_backup),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_new_pack)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            if (state.installedTargets.isEmpty() && !state.isLoading) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.status_whatsapp_missing),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.packs.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.pack_list_empty_title),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = stringResource(R.string.pack_list_empty_body),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }

                        GatitasFooterImage(
                            onClick = playMeow,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(bottom = 76.dp),
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                    ) {
                        items(state.packs, key = { it.pack.identifier }) { item ->
                            PackSummaryCard(
                                item = item,
                                onEdit = { onOpenEditor(item.pack.identifier) },
                                onDuplicate = { viewModel.duplicatePack(item.pack.identifier) },
                                onExport = {
                                    pendingExportPackId = item.pack.identifier
                                    exportSingleLauncher.launch("yanistickers_${item.pack.identifier}.zip")
                                },
                                onDelete = { packPendingDelete = item },
                                onAddToWhatsApp = { triggerAddToWhatsApp(item.pack) },
                            )
                        }
                        item(key = "gatitas_footer") {
                            GatitasFooterImage(
                                onClick = playMeow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackSummaryCard(
    item: PackSummaryUi,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onAddToWhatsApp: () -> Unit,
) {
    val pack = item.pack
    val errors = remember(item.validation) { item.validation.allErrorMessages() }
    val isValid = errors.isEmpty()
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocalStickerThumbnail(
                    file = item.trayFile,
                    contentDescription = pack.name,
                    size = 56.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${pack.publisher} • ${pack.stickers.size} stickers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "Opciones del pack",
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_duplicate)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_export_zip)) },
                            leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PackStatusBadge(
                    text = stringResource(
                        if (pack.animated) R.string.badge_animated else R.string.badge_static,
                    ),
                    containerColor = if (pack.animated) AnimatedBadgeContainer else StaticBadgeContainer,
                    contentColor = if (pack.animated) OnAnimatedBadgeContainer else OnStaticBadgeContainer,
                    borderColor = if (pack.animated) AnimatedBadgeBorder else StaticBadgeBorder,
                )

                if (item.whitelistStatus.isAddedAnywhere) {
                    PackStatusBadge(
                        text = stringResource(R.string.status_added),
                        containerColor = AddedBadgeContainer,
                        contentColor = OnAddedBadgeContainer,
                        borderColor = AddedBadgeBorder,
                    )
                }
            }

            if (item.previewStickerFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                PackStickersPreviewStrip(
                    files = item.previewStickerFiles,
                    packName = pack.name,
                )
            }

            if (errors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                PackErrorsBanner(errors = errors)
            }

            Spacer(modifier = Modifier.height(12.dp))

            AddToWhatsAppButton(
                enabled = isValid,
                onClick = onAddToWhatsApp,
            )
        }
    }
}

@Composable
private fun PackStatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun rememberMeowSoundPlayer(): () -> Unit {
    val context = LocalContext.current
    val controller = remember(context) {
        object {
            var activePlayer: MediaPlayer? = null

            fun play() {
                if (activePlayer != null) return
                val player = createPreparedMeowPlayer(context) ?: return

                activePlayer = player
                player.setOnCompletionListener { completed ->
                    if (activePlayer === completed) {
                        activePlayer = null
                    }
                    runCatching { completed.release() }
                }
                player.setOnErrorListener { errored, _, _ ->
                    if (activePlayer === errored) {
                        activePlayer = null
                    }
                    runCatching { errored.release() }
                    true
                }
                runCatching { player.start() }.onFailure {
                    if (activePlayer === player) {
                        activePlayer = null
                    }
                    runCatching { player.release() }
                }
            }

            fun release() {
                val current = activePlayer
                activePlayer = null
                if (current != null) {
                    runCatching { current.release() }
                }
            }
        }
    }

    DisposableEffect(controller) {
        onDispose {
            controller.release()
        }
    }

    return controller::play
}

private fun createPreparedMeowPlayer(context: android.content.Context): MediaPlayer? {
    val player = MediaPlayer()
    return runCatching {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        context.resources.openRawResourceFd(R.raw.miau).use { afd ->
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        player.prepare()
        player
    }.getOrElse {
        runCatching { player.release() }
        null
    }
}

@Composable
private fun GatitasFooterImage(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.gatitas),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .heightIn(max = 160.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        )
    }
}

@Composable
private fun PackStickersPreviewStrip(
    files: List<java.io.File>,
    packName: String,
) {
    val previewCount = 5
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (index in 0 until previewCount) {
            val file = files.getOrNull(index)
            if (file != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                    ) {
                        LocalStickerThumbnail(
                            file = file,
                            contentDescription = "$packName sticker ${index + 1}",
                        )
                    }
                }
            } else {
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                )
            }
        }
    }
}

@Composable
private fun PackErrorsBanner(errors: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
private fun CreatePackDialog(
    onCreate: (name: String, publisher: String, animated: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var publisher by remember { mutableStateOf("MrDaniix") }
    var animated by remember { mutableStateOf(false) }

    val canCreate = name.trim().isNotEmpty() &&
        name.trim().length <= PackValidator.MAX_TEXT_LENGTH &&
        publisher.trim().isNotEmpty() &&
        publisher.trim().length <= PackValidator.MAX_TEXT_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_new_pack)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= PackValidator.MAX_TEXT_LENGTH) name = it },
                    label = { Text("Nombre del pack") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = publisher,
                    onValueChange = { if (it.length <= PackValidator.MAX_TEXT_LENGTH) publisher = it },
                    label = { Text("Autor") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Tipo de pack (no se pueden mezclar estáticos y animados):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PackTypeChoiceChip(
                        selected = !animated,
                        label = stringResource(R.string.badge_static),
                        containerColor = StaticBadgeContainer,
                        contentColor = OnStaticBadgeContainer,
                        borderColor = StaticBadgeBorder,
                        onClick = { animated = false },
                    )
                    PackTypeChoiceChip(
                        selected = animated,
                        label = stringResource(R.string.badge_animated),
                        containerColor = AnimatedBadgeContainer,
                        contentColor = OnAnimatedBadgeContainer,
                        borderColor = AnimatedBadgeBorder,
                        onClick = { animated = true },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim(), publisher.trim(), animated) },
                enabled = canCreate,
            ) {
                Text("Crear")
            }
        },
        dismissButton = {
            FilledTonalButton(
                onClick = onDismiss,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = IconActionContainer,
                    contentColor = OnIconActionContainer,
                ),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PackTypeChoiceChip(
    selected: Boolean,
    label: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = containerColor.copy(alpha = 0.45f),
            labelColor = contentColor.copy(alpha = 0.78f),
            selectedContainerColor = containerColor,
            selectedLabelColor = contentColor,
            selectedLeadingIconColor = contentColor,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = borderColor.copy(alpha = 0.55f),
            selectedBorderColor = borderColor,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.5.dp,
        ),
    )
}
