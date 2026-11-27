package io.github.danivi.yanistickers.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.data.StickerPack
import io.github.danivi.yanistickers.whatsapp.PackWhitelistStatus
import io.github.danivi.yanistickers.whatsapp.WhatsAppBridge
import io.github.danivi.yanistickers.whatsapp.WhatsAppContract
import io.github.danivi.yanistickers.whatsapp.WhatsAppTarget
import kotlinx.coroutines.launch
import java.io.File

data class PackCardUiState(
    val pack: StickerPack,
    val trayFile: File?,
    val whitelistStatus: PackWhitelistStatus,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Phase1HomeScreen(
    packs: List<PackCardUiState>,
    installedTargets: List<WhatsAppTarget>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingTargetPack by remember { mutableStateOf<StickerPack?>(null) }

    val addPackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val validationError = result.data?.getStringExtra(WhatsAppContract.EXTRA_VALIDATION_ERROR)
        val message = when {
            result.resultCode == Activity.RESULT_OK -> "Pack añadido a WhatsApp correctamente."
            !validationError.isNullOrBlank() -> "Error de WhatsApp: $validationError"
            else -> "Operación cancelada."
        }
        onRefresh()
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun launchAddToWhatsApp(pack: StickerPack, targetPackage: String?) {
        val intent = WhatsAppBridge.buildAddPackIntent(context, pack, targetPackage)
        try {
            addPackLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch {
                snackbarHostState.showSnackbar("No se pudo abrir WhatsApp (app no instalada).")
            }
        }
    }

    pendingTargetPack?.let { pack ->
        TargetPickerDialog(
            targets = installedTargets,
            onSelect = { target ->
                pendingTargetPack = null
                launchAddToWhatsApp(pack, target.packageName)
            },
            onDismiss = { pendingTargetPack = null },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.phase1_title)) },
                actions = {
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.phase1_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (installedTargets.isEmpty() && !isLoading) {
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

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(packs, key = { it.pack.identifier }) { item ->
                        PackCard(
                            state = item,
                            onAddClick = {
                                when (installedTargets.size) {
                                    0 -> launchAddToWhatsApp(item.pack, null)
                                    1 -> launchAddToWhatsApp(item.pack, installedTargets.first().packageName)
                                    else -> pendingTargetPack = item.pack
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackCard(
    state: PackCardUiState,
    onAddClick: () -> Unit,
) {
    val pack = state.pack
    val trayBitmap = remember(state.trayFile?.absolutePath) {
        state.trayFile?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (trayBitmap != null) {
                    Image(
                        bitmap = trayBitmap,
                        contentDescription = pack.name,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {}
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${pack.publisher} · ${pack.stickers.size} stickers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "ID: ${pack.identifier}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(
                                if (pack.animated) R.string.badge_animated else R.string.badge_static,
                            ),
                        )
                    },
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(
                                if (state.whitelistStatus.isAddedAnywhere) {
                                    R.string.status_added
                                } else {
                                    R.string.status_not_added
                                },
                            ),
                        )
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onAddClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_add_to_whatsapp))
            }
        }
    }
}

@Composable
private fun TargetPickerDialog(
    targets: List<WhatsAppTarget>,
    onSelect: (WhatsAppTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_whatsapp_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { target ->
                    OutlinedButton(
                        onClick = { onSelect(target) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(target.displayName)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
