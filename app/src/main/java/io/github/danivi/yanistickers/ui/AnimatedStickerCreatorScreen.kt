package io.github.danivi.yanistickers.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.data.AnimatedSourceKind
import io.github.danivi.yanistickers.data.AnimatedSourceProbe
import io.github.danivi.yanistickers.data.FramingMode
import io.github.danivi.yanistickers.data.StaticStickerEncoder
import io.github.danivi.yanistickers.data.StickerFramingTransform
import io.github.danivi.yanistickers.validation.PackValidator
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedStickerCreatorScreen(
    viewModel: AnimatedStickerCreatorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var launchedInitialPicker by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showChangeSourceMenu by remember { mutableStateOf(false) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.onMediaSelected(uri)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onMediaSelected(uri)
        }
    }

    fun openVideoPicker() {
        videoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
        )
    }

    fun openDocumentPicker() {
        documentPickerLauncher.launch(arrayOf("image/webp", "image/gif", "video/*"))
    }

    LaunchedEffect(state.isEditMode) {
        if (!state.isEditMode && !launchedInitialPicker && state.probe == null && !state.isProbing) {
            launchedInitialPicker = true
            openVideoPicker()
        }
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onBack()
        }
    }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    if (state.isSaving) {
        val percent = (state.saveProgress * 100f).roundToInt().coerceIn(0, 100)
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(if (state.isEditMode) "Actualizando sticker animado" else "Creando sticker animado")
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = state.saveStatus.ifBlank { "Convirtiendo a WebP..." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { state.saveProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "$percent %",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::cancelSave) {
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
                        stringResource(
                            if (state.isEditMode) R.string.creator_edit_animated_title else R.string.creator_animated_title,
                        ),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val probe = state.probe
            if (probe == null) {
                EmptyAnimatedSelectionCard(
                    isProbing = state.isProbing,
                    onPickVideo = ::openVideoPicker,
                    onPickDocument = ::openDocumentPicker,
                )
            } else {
                if (probe.kind == AnimatedSourceKind.VIDEO) {
                    LiveVideoFramingPreviewBox(
                        probe = probe,
                        transform = state.transform,
                        trimStartMs = state.trimStartMs,
                        trimEndMs = state.trimEndMs,
                        onTransformGesture = viewModel::applyGesture,
                    )
                } else {
                    LiveAnimatedImageFramingPreviewBox(
                        probe = probe,
                        transform = state.transform,
                        onTransformGesture = viewModel::applyGesture,
                    )
                }

                StickerFramingControlsRow(
                    mode = state.transform.mode,
                    onSelectMode = viewModel::setFramingMode,
                    onRotate90 = viewModel::rotate90,
                    onReset = viewModel::resetTransform,
                    onChangeSource = { showChangeSourceMenu = true },
                    changeSourceIcon = Icons.Outlined.PermMedia,
                    changeSourceContentDescription = "Cambiar archivo",
                    changeSourceMenu = {
                        DropdownMenu(
                            expanded = showChangeSourceMenu,
                            onDismissRequest = { showChangeSourceMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_pick_media)) },
                                leadingIcon = { Icon(Icons.Outlined.Movie, contentDescription = null) },
                                onClick = {
                                    showChangeSourceMenu = false
                                    openVideoPicker()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_pick_document)) },
                                leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                                onClick = {
                                    showChangeSourceMenu = false
                                    openDocumentPicker()
                                },
                            )
                        }
                    },
                )

                if (state.isVideoSource) {
                    VideoTrimCard(
                        totalDurationMs = probe.totalDurationMs,
                        trimStartMs = state.trimStartMs,
                        trimEndMs = state.trimEndMs,
                        enabled = !state.isProbing && !state.isSaving,
                        onRangeChanged = viewModel::onTrimRangeChanged,
                    )
                }

                AdvancedStickerMetadataCard(
                    expanded = showAdvanced,
                    onToggleExpanded = { showAdvanced = !showAdvanced },
                    nameText = state.nameInput,
                    onNameTextChange = viewModel::onNameChange,
                    emojisText = state.emojisInput,
                    onEmojisTextChange = viewModel::onEmojisChange,
                    accessibilityText = state.accessibilityInput,
                    onAccessibilityTextChange = viewModel::onAccessibilityChange,
                    animatedPack = true,
                )

                Button(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (state.isEditMode) R.string.action_save_changes else R.string.action_save_sticker,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAnimatedSelectionCard(
    isProbing: Boolean,
    onPickVideo: () -> Unit,
    onPickDocument: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isProbing) {
                CircularProgressIndicator()
                Text("Analizando archivo...")
            } else {
                Icon(
                    imageVector = Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Elige un vídeo o GIF (o importa un WebP/GIF desde Archivos). Se convertirá a WebP animado al guardar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onPickVideo,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_pick_media))
                }
                OutlinedButton(
                    onClick = onPickDocument,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_pick_document))
                }
            }
        }
    }
}

/**
 * Reproductor de vídeo en vivo `1:1` sobre `TextureView` con transformación directa de encuadre
 * (`Encajar`/`Rellenar`, rotación, zoom y paneo) y bucle del tramo seleccionado sin convertir nada.
 */
@Composable
private fun LiveVideoFramingPreviewBox(
    probe: AnimatedSourceProbe,
    transform: StickerFramingTransform,
    trimStartMs: Long,
    trimEndMs: Long,
    onTransformGesture: (zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) -> Unit,
) {
    var canvasSizePx by remember { mutableFloatStateOf(0f) }
    var videoW by remember(probe.sourceFile.absolutePath) { mutableIntStateOf(probe.width) }
    var videoH by remember(probe.sourceFile.absolutePath) { mutableIntStateOf(probe.height) }
    var mediaPlayer by remember(probe.sourceFile.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember(probe.sourceFile.absolutePath) { mutableStateOf(false) }

    val latestTrimStart by rememberUpdatedState(trimStartMs)
    val latestTrimEnd by rememberUpdatedState(trimEndMs)
    val latestTransform by rememberUpdatedState(transform)

    DisposableEffect(probe.sourceFile.absolutePath) {
        onDispose {
            isPrepared = false
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
    }

    LaunchedEffect(probe.sourceFile.absolutePath, isPrepared) {
        val player = mediaPlayer ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        while (true) {
            runCatching {
                val pos = player.currentPosition.toLong()
                if (pos >= latestTrimEnd || pos < latestTrimStart - 250L) {
                    player.seekTo(latestTrimStart, MediaPlayer.SEEK_CLOSEST)
                    if (!player.isPlaying) {
                        player.start()
                    }
                } else if (!player.isPlaying) {
                    player.start()
                }
            }
            delay(75)
        }
    }

    LaunchedEffect(trimStartMs, isPrepared) {
        val player = mediaPlayer ?: return@LaunchedEffect
        if (isPrepared) {
            runCatching {
                player.seekTo(trimStartMs, MediaPlayer.SEEK_CLOSEST)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .onSizeChanged { size ->
                canvasSizePx = size.width.toFloat()
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransformGesture(zoom, pan.x, pan.y, canvasSizePx)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTransparencyCheckerboard(cellSizePx = 16.dp.toPx())
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null

                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            applyTextureViewTransform(this@apply, videoW, videoH, width.toFloat(), latestTransform)
                            val surf = Surface(st)
                            surface = surf
                            val mp = MediaPlayer()
                            mediaPlayer = mp
                            runCatching {
                                mp.setDataSource(probe.sourceFile.absolutePath)
                                mp.setSurface(surf)
                                mp.setVolume(0f, 0f)
                                mp.setOnVideoSizeChangedListener { _, w, h ->
                                    if (w > 0 && h > 0) {
                                        videoW = w
                                        videoH = h
                                        applyTextureViewTransform(
                                            this@apply,
                                            w,
                                            h,
                                            this@apply.width.toFloat(),
                                            latestTransform,
                                        )
                                    }
                                }
                                mp.setOnPreparedListener { prepared ->
                                    isPrepared = true
                                    prepared.seekTo(latestTrimStart, MediaPlayer.SEEK_CLOSEST)
                                    prepared.start()
                                }
                                mp.prepareAsync()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                            applyTextureViewTransform(this@apply, videoW, videoH, width.toFloat(), latestTransform)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            isPrepared = false
                            runCatching { mediaPlayer?.release() }
                            mediaPlayer = null
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            update = { textureView ->
                applyTextureViewTransform(
                    textureView = textureView,
                    videoW = videoW,
                    videoH = videoH,
                    canvasSizePx = textureView.width.toFloat(),
                    transform = transform,
                )
            },
        )
    }
}

private fun applyTextureViewTransform(
    textureView: TextureView,
    videoW: Int,
    videoH: Int,
    canvasSizePx: Float,
    transform: StickerFramingTransform,
) {
    if (canvasSizePx <= 0f || videoW <= 0 || videoH <= 0) return
    val matrix = Matrix()
    matrix.setScale(videoW.toFloat() / canvasSizePx, videoH.toFloat() / canvasSizePx)
    matrix.postConcat(
        StaticStickerEncoder.buildRenderMatrix(
            srcWidth = videoW,
            srcHeight = videoH,
            canvasSizePx = canvasSizePx,
            transform = transform,
        ),
    )
    textureView.setTransform(matrix)
}

/**
 * Vista previa animada en vivo del GIF o WebP animado original con encuadre interactivo.
 */
@Composable
private fun LiveAnimatedImageFramingPreviewBox(
    probe: AnimatedSourceProbe,
    transform: StickerFramingTransform,
    onTransformGesture: (zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { StickerImageLoaderProvider.get(context) }
    var canvasSizePx by remember { mutableFloatStateOf(0f) }

    val request = remember(probe.sourceFile.absolutePath) {
        ImageRequest.Builder(context)
            .data(probe.sourceFile)
            .crossfade(false)
            .build()
    }

    val safeTransform = transform.normalized()
    val fitScale = StaticStickerEncoder.computeBaseScale(
        srcWidth = probe.width,
        srcHeight = probe.height,
        canvasSizePx = 1f,
        mode = FramingMode.FIT,
        rotationDegrees = 0,
    )
    val targetBaseScale = StaticStickerEncoder.computeBaseScale(
        srcWidth = probe.width,
        srcHeight = probe.height,
        canvasSizePx = 1f,
        mode = safeTransform.mode,
        rotationDegrees = safeTransform.rotationDegrees,
    )
    val scaleMultiplier = if (fitScale > 0f) (targetBaseScale / fitScale) * safeTransform.zoom else safeTransform.zoom

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .onSizeChanged { size ->
                canvasSizePx = size.width.toFloat()
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransformGesture(zoom, pan.x, pan.y, canvasSizePx)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTransparencyCheckerboard(cellSizePx = 16.dp.toPx())
        }
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = "Vista previa animada",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleMultiplier
                    scaleY = scaleMultiplier
                    rotationZ = safeTransform.rotationDegrees.toFloat()
                    translationX = safeTransform.normalizedPanX * canvasSizePx
                    translationY = safeTransform.normalizedPanY * canvasSizePx
                },
        )
    }
}

@Composable
private fun VideoTrimCard(
    totalDurationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    enabled: Boolean,
    onRangeChanged: (Long, Long) -> Unit,
) {
    val maxDuration = max(500L, totalDurationMs).toFloat()
    val startVal = trimStartMs.toFloat().coerceIn(0f, maxDuration)
    val endVal = trimEndMs.toFloat().coerceIn(startVal, maxDuration)
    val selectedDurationMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Tramo del vídeo: ${PackValidator.formatSeconds(trimStartMs)} – " +
                    "${PackValidator.formatSeconds(trimEndMs)} " +
                    "(${PackValidator.formatSeconds(selectedDurationMs)} / máx. 10 s)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            RangeSlider(
                value = startVal..endVal,
                onValueChange = { range ->
                    onRangeChanged(range.start.roundToLong(), range.endInclusive.roundToLong())
                },
                valueRange = 0f..maxDuration,
                enabled = enabled,
            )
        }
    }
}
