package io.github.danivi.yanistickers.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.data.ConflictResolution
import io.github.danivi.yanistickers.data.StagedBackup
import io.github.danivi.yanistickers.data.StagedPackEntry
import io.github.danivi.yanistickers.ui.theme.ImportTeal
import io.github.danivi.yanistickers.ui.theme.PureWhite

private val ZIP_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingExportPackIds by remember { mutableStateOf<List<String>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val ids = pendingExportPackIds
        pendingExportPackIds = null
        if (uri != null) {
            viewModel.exportToUri(uri = uri, packIdentifiers = ids)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importFromUri(uri)
        }
    }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.isLoading && state.packs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val stagedConflicts = state.pendingStagedBackup
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (stagedConflicts != null) {
                        item(key = "conflicts_card") {
                            ConflictResolutionSectionCard(
                                staged = stagedConflicts,
                                resolutions = state.conflictResolutions,
                                isBusy = state.isBusy,
                                onSelectResolution = viewModel::setConflictResolution,
                                onSelectAll = viewModel::setAllConflictResolutions,
                                onConfirm = viewModel::confirmConflictImport,
                                onCancel = viewModel::cancelConflictImport,
                            )
                        }
                    } else {
                        item(key = "export_card") {
                            ExportSectionCard(
                                packCount = state.packs.size,
                                isBusy = state.isBusy,
                                onExportAll = {
                                    pendingExportPackIds = null
                                    exportLauncher.launch(viewModel.suggestedAllBackupFileName())
                                },
                            )
                        }

                        item(key = "import_card") {
                            ImportSectionCard(
                                isBusy = state.isBusy,
                                onImportClick = { importLauncher.launch(ZIP_MIME_TYPES) },
                            )
                        }

                        if (state.packs.isNotEmpty()) {
                            item(key = "single_export_header") {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.backup_export_single_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            items(
                                items = state.packs,
                                key = { "export_${it.pack.identifier}" },
                            ) { item ->
                                SinglePackExportRowCard(
                                    item = item,
                                    isBusy = state.isBusy,
                                    onExport = {
                                        pendingExportPackIds = listOf(item.pack.identifier)
                                        exportLauncher.launch(viewModel.suggestedSingleBackupFileName(item.pack))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SinglePackExportRowCard(
    item: BackupPackItemUi,
    isBusy: Boolean,
    onExport: () -> Unit,
) {
    val pack = item.pack
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalStickerThumbnail(
                file = item.trayFile,
                contentDescription = pack.name,
                size = 44.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pack.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${pack.publisher} • ${pack.stickers.size} stickers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onExport,
                enabled = !isBusy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Save,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_export_short))
            }
        }
    }
}

@Composable
private fun ExportSectionCard(
    packCount: Int,
    isBusy: Boolean,
    onExportAll: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.backup_export_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "Guarda una copia .zip con tus packs, stickers e identificadores para poder restaurarlos si cambias o borras la app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onExportAll,
                enabled = packCount > 0 && !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (packCount == 0) {
                        "No hay packs para exportar"
                    } else {
                        "Exportar todos los packs ($packCount)"
                    },
                )
            }
        }
    }
}

@Composable
private fun ImportSectionCard(
    isBusy: Boolean,
    onImportClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FileOpen,
                    contentDescription = null,
                    tint = ImportTeal,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.backup_import_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "Selecciona un archivo .zip de YaniStickers. Si algún pack ya existe en el dispositivo, podrás elegir qué hacer antes de instalarlo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onImportClick,
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ImportTeal,
                    contentColor = PureWhite,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_import_zip))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictResolutionSectionCard(
    staged: StagedBackup,
    resolutions: Map<String, ConflictResolution>,
    isBusy: Boolean,
    onSelectResolution: (String, ConflictResolution) -> Unit,
    onSelectAll: (ConflictResolution) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val conflicts = staged.conflicts
    val nonConflictCount = staged.entries.size - conflicts.size

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFD2DFEE),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Conflictos detectados (${conflicts.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildString {
                    append("Estos packs del archivo .zip ya existen en este dispositivo.")
                    if (nonConflictCount > 0) {
                        append(" Otros $nonConflictCount pack(s) sin conflicto se importarán directamente.")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (conflicts.size > 1) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextButton(onClick = { onSelectAll(ConflictResolution.REPLACE) }) {
                        Text("Reemplazar todos")
                    }
                    TextButton(onClick = { onSelectAll(ConflictResolution.DUPLICATE_NEW_ID) }) {
                        Text("Duplicar todos")
                    }
                    TextButton(onClick = { onSelectAll(ConflictResolution.SKIP) }) {
                        Text("Omitir todos")
                    }
                }
            }

            conflicts.forEach { entry ->
                ConflictItemBlock(
                    entry = entry,
                    selected = resolutions[entry.pack.identifier] ?: ConflictResolution.DUPLICATE_NEW_ID,
                    onSelect = { onSelectResolution(entry.pack.identifier, it) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = !isBusy,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    enabled = !isBusy,
                ) {
                    Text(stringResource(R.string.backup_confirm_import))
                }
            }
        }
    }
}

@Composable
private fun ConflictItemBlock(
    entry: StagedPackEntry,
    selected: ConflictResolution,
    onSelect: (ConflictResolution) -> Unit,
) {
    val existing = entry.existingPack
    val options = listOf(
        ConflictResolution.REPLACE to "Reemplazar",
        ConflictResolution.DUPLICATE_NEW_ID to "Duplicar",
        ConflictResolution.SKIP to "Omitir",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entry.pack.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildString {
                    append("En copia: ${entry.pack.stickers.size} stickers")
                    if (existing != null) {
                        append(" • Local actual: \"${existing.name}\" (${existing.stickers.size} stickers)")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { (resolution, label) ->
                    FilterChip(
                        selected = selected == resolution,
                        onClick = { onSelect(resolution) },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }
            }
        }
    }
}
