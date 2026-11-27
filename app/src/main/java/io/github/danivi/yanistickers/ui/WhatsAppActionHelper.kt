package io.github.danivi.yanistickers.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.StickerPack
import io.github.danivi.yanistickers.validation.PackValidator
import io.github.danivi.yanistickers.whatsapp.WhatsAppBridge
import io.github.danivi.yanistickers.whatsapp.WhatsAppContract
import io.github.danivi.yanistickers.whatsapp.WhatsAppTarget
import kotlinx.coroutines.launch
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
 * Proveedor compartido de [ImageLoader] de Coil configurado con [AnimatedImageDecoder] para
 * reproducir WebP y GIF animados en toda la interfaz sin instanciar loaders por tarjeta.
 */
object StickerImageLoaderProvider {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    add(AnimatedImageDecoder.Factory())
                }
                .build()
                .also { instance = it }
        }
    }
}

/**
 * Muestra un archivo de imagen local (`tray` o sticker estático/animado) con soporte de animación
 * mediante Coil e invalidación automática cuando el archivo cambia en disco.
 */
@Composable
fun LocalStickerThumbnail(
    file: File?,
    contentDescription: String,
    size: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { StickerImageLoaderProvider.get(context) }
    val shape = RoundedCornerShape(12.dp)
    val sizedModifier = if (size != null) modifier.size(size) else modifier.fillMaxSize()

    if (file != null && file.isFile) {
        val fileKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        val request = remember(fileKey) {
            ImageRequest.Builder(context)
                .data(file)
                .memoryCacheKey(fileKey)
                .diskCacheKey(fileKey)
                .crossfade(false)
                .build()
        }
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = sizedModifier.clip(shape),
        )
    } else {
        Surface(
            modifier = sizedModifier,
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

private val QUICK_EMOJIS = listOf("😀", "😂", "❤️", "🔥", "🎉", "👍", "✨", "🥳", "🗣️", "😈")

private val PICKER_EMOJIS = listOf(
    "😀", "😂", "🤣", "😍", "🥰", "😘", "😎", "🥳", "🤩", "😭",
    "😡", "🤬", "🤯", "😱", "🤔", "🤫", "🫡", "🫠", "🙄", "😴",
    "😈", "👿", "💀", "🤡", "👻", "🗣️", "👀", "🧠", "🙏", "👍",
    "👎", "👏", "🙌", "🤝", "✌️", "🤞", "🫶", "💪", "❤️", "🧡",
    "💛", "💚", "💙", "💜", "🖤", "💔", "🔥", "✨", "💥", "💯",
    "🎉", "🎊", "🎈", "🎁", "⭐", "🌟", "⚡", "☀️", "🌈", "🍕",
    "🍺", "☕", "🐶", "🐱", "🐸", "🦋", "🚀", "🏆", "✅", "❌",
)

/**
 * Campos reutilizables para editar nombre interno, emojis (`1..3`) y descripción de accesibilidad
 * de un sticker. Al pulsar el campo de emojis se abre el selector y nunca permite texto plano.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StickerMetadataEditorFields(
    nameText: String,
    onNameTextChange: (String) -> Unit,
    emojisText: String,
    onEmojisTextChange: (String) -> Unit,
    accessibilityText: String,
    onAccessibilityTextChange: (String) -> Unit,
    animatedPack: Boolean,
    modifier: Modifier = Modifier,
) {
    val parsedEmojis = remember(emojisText) { PackJsonParser.splitEmojisGraphemes(emojisText) }
    val maxA11y = if (animatedPack) {
        PackValidator.MAX_ANIMATED_A11Y_LENGTH
    } else {
        PackValidator.MAX_STATIC_A11Y_LENGTH
    }
    val emojisValid = parsedEmojis.size in PackValidator.MIN_EMOJIS..PackValidator.MAX_EMOJIS
    var showEmojiPicker by remember { mutableStateOf(false) }
    val emojiInteractionSource = remember { MutableInteractionSource() }

    LaunchedEffect(emojiInteractionSource) {
        emojiInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                showEmojiPicker = true
            }
        }
    }

    if (showEmojiPicker) {
        EmojiPickerDialog(
            selectedEmojis = parsedEmojis,
            onEmojisChange = { updated -> onEmojisTextChange(updated.joinToString(" ")) },
            onDismiss = { showEmojiPicker = false },
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = nameText,
            onValueChange = { if (it.length <= PackValidator.MAX_TEXT_LENGTH) onNameTextChange(it) },
            label = { Text("Nombre del sticker (opcional)") },
            singleLine = true,
            supportingText = { Text("Solo visible dentro de la app") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = parsedEmojis.joinToString(" "),
            onValueChange = { filtered ->
                onEmojisTextChange(PackJsonParser.filterEmojiInput(filtered, PackValidator.MAX_EMOJIS))
            },
            readOnly = true,
            interactionSource = emojiInteractionSource,
            label = { Text("Emojis (entre 1 y 3)") },
            singleLine = true,
            isError = !emojisValid,
            trailingIcon = {
                IconButton(onClick = { showEmojiPicker = true }) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEmotions,
                        contentDescription = "Abrir selector de emojis",
                    )
                }
            },
            supportingText = {
                Text(
                    "Toca para abrir el selector (${parsedEmojis.size}/${PackValidator.MAX_EMOJIS})",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QUICK_EMOJIS.forEach { emoji ->
                SuggestionChip(
                    onClick = {
                        if (parsedEmojis.size < PackValidator.MAX_EMOJIS) {
                            onEmojisTextChange((parsedEmojis + emoji).joinToString(" "))
                        }
                    },
                    label = { Text(emoji) },
                )
            }
        }

        OutlinedTextField(
            value = accessibilityText,
            onValueChange = { if (it.length <= maxA11y) onAccessibilityTextChange(it) },
            label = { Text("Descripción de accesibilidad (opcional)") },
            supportingText = { Text("${accessibilityText.trim().length}/$maxA11y") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiPickerDialog(
    selectedEmojis: List<String>,
    onEmojisChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyboardInput by remember(selectedEmojis) { mutableStateOf(selectedEmojis.joinToString(" ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elegir emojis (${selectedEmojis.size}/${PackValidator.MAX_EMOJIS})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectedEmojis.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        selectedEmojis.forEachIndexed { idx, emoji ->
                            InputChip(
                                selected = true,
                                onClick = {
                                    val next = selectedEmojis.toMutableList().apply { removeAt(idx) }
                                    onEmojisChange(next)
                                    keyboardInput = next.joinToString(" ")
                                },
                                label = { Text(emoji) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Quitar $emoji",
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = keyboardInput,
                    onValueChange = { raw ->
                        val filtered = PackJsonParser.filterEmojiInput(raw, PackValidator.MAX_EMOJIS)
                        keyboardInput = filtered
                        onEmojisChange(PackJsonParser.splitEmojisGraphemes(filtered))
                    },
                    label = { Text("Desde el teclado (solo emojis)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PICKER_EMOJIS.forEach { emoji ->
                            SuggestionChip(
                                onClick = {
                                    if (selectedEmojis.size < PackValidator.MAX_EMOJIS) {
                                        val next = selectedEmojis + emoji
                                        onEmojisChange(next)
                                        keyboardInput = next.joinToString(" ")
                                    }
                                },
                                label = { Text(emoji) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Listo")
            }
        },
        dismissButton = {
            if (selectedEmojis.isNotEmpty()) {
                TextButton(
                    onClick = {
                        onEmojisChange(emptyList())
                        keyboardInput = ""
                    },
                ) {
                    Text("Limpiar")
                }
            }
        },
    )
}
