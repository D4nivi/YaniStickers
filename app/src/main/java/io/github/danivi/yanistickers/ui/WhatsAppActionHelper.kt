package io.github.danivi.yanistickers.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.data.StickerPack
import io.github.danivi.yanistickers.whatsapp.WhatsAppBridge
import io.github.danivi.yanistickers.whatsapp.WhatsAppContract
import io.github.danivi.yanistickers.whatsapp.WhatsAppTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordina el flujo `ENABLE_STICKER_PACK` (selector de WhatsApp/Business y resultado) para evitar
 * duplicación entre pantallas.
 */
@Composable
fun rememberWhatsAppAddAction(
    installedTargets: List<WhatsAppTarget>,
    snackbarHostState: SnackbarHostState,
    onCompleted: () -> Unit,
): (StickerPack) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingTargetPack by remember { mutableStateOf<StickerPack?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val validationError = result.data?.getStringExtra(WhatsAppContract.EXTRA_VALIDATION_ERROR)
        val message = when {
            result.resultCode == Activity.RESULT_OK -> "Pack añadido a WhatsApp correctamente."
            !validationError.isNullOrBlank() -> "Error de WhatsApp: $validationError"
            else -> "Operación cancelada."
        }
        onCompleted()
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun launchWithTarget(pack: StickerPack, targetPackage: String?) {
        val intent = WhatsAppBridge.buildAddPackIntent(context, pack, targetPackage)
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch {
                snackbarHostState.showSnackbar("No se pudo abrir WhatsApp (app no instalada).")
            }
        }
    }

    pendingTargetPack?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingTargetPack = null },
            title = { Text(stringResource(R.string.choose_whatsapp_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    installedTargets.forEach { target ->
                        OutlinedButton(
                            onClick = {
                                pendingTargetPack = null
                                launchWithTarget(pack, target.packageName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(target.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingTargetPack = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    return { pack ->
        when (installedTargets.size) {
            0 -> launchWithTarget(pack, null)
            1 -> launchWithTarget(pack, installedTargets.first().packageName)
            else -> pendingTargetPack = pack
        }
    }
}

/**
 * Decodifica en segundo plano un archivo de imagen local (`tray` o miniatura de sticker) para la UI.
 */
@Composable
fun LocalStickerThumbnail(
    file: File?,
    contentDescription: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val fileKey = file?.let { "${it.absolutePath}:${it.lastModified()}:${it.length()}" }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = fileKey) {
        value = if (file != null && file.isFile) {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
            }
        } else {
            null
        }
    }

    val shape = RoundedCornerShape(12.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = contentDescription,
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
