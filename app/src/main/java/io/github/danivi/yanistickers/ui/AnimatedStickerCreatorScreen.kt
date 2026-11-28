package io.github.danivi.yanistickers.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.danivi.yanistickers.data.AnimatedFrameExtractor
import io.github.danivi.yanistickers.data.AnimatedSourceKind
import io.github.danivi.yanistickers.data.AnimatedSourceProbe
import io.github.danivi.yanistickers.data.FrameSamplingPlanner
import io.github.danivi.yanistickers.data.FramingMode
import io.github.danivi.yanistickers.data.FramingSnapGuides
import io.github.danivi.yanistickers.data.StaticStickerEncoder
import io.github.danivi.yanistickers.data.StickerFramingTransform
import io.github.danivi.yanistickers.validation.PackValidator
import kotlinx.coroutines.delay
import kotlin.math.abs
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
                        snapGuides = state.snapGuides,
                        isGestureActive = state.isGestureActive,
                        trimStartMs = state.trimStartMs,
                        trimEndMs = state.effectiveTrimEndMs,
                        speedMultiplier = state.speedMultiplier,
                        onTransformGesture = viewModel::applyGesture,
                        onTransformGestureEnd = viewModel::onTransformGestureEnd,
                    )
                } else {
                    LiveAnimatedImageFramingPreviewBox(
                        probe = probe,
                        transform = state.transform,
                        snapGuides = state.snapGuides,
                        isGestureActive = state.isGestureActive,
                        speedMultiplier = state.speedMultiplier,
                        onTransformGesture = viewModel::applyGesture,
                        onTransformGestureEnd = viewModel::onTransformGestureEnd,
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
                        trimEndMs = state.effectiveTrimEndMs,
                        rawTrimEndMs = state.trimEndMs,
                        speedMultiplier = state.speedMultiplier,
                        enabled = !state.isProbing && !state.isSaving,
                        onRangeChanged = viewModel::onTrimRangeChanged,
                    )
                }

                SpeedControlCard(
                    speedMultiplier = state.speedMultiplier,
                    rawScaledDurationMs = state.rawScaledDurationMs,
                    autoLoopMultiplier = state.autoLoopMultiplier,
                    estimatedOutputDurationMs = state.estimatedOutputDurationMs,
                    enabled = !state.isProbing && !state.isSaving,
                    onSpeedChange = viewModel::onSpeedChange,
                )

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
    snapGuides: FramingSnapGuides,
    isGestureActive: Boolean,
    trimStartMs: Long,
    trimEndMs: Long,
    speedMultiplier: Float,
    onTransformGesture: (zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) -> Unit,
    onTransformGestureEnd: () -> Unit,
) {
    var canvasSizePx by remember { mutableFloatStateOf(0f) }
    var videoW by remember(probe.sourceFile.absolutePath) { mutableIntStateOf(probe.width) }
    var videoH by remember(probe.sourceFile.absolutePath) { mutableIntStateOf(probe.height) }
    var mediaPlayer by remember(probe.sourceFile.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember(probe.sourceFile.absolutePath) { mutableStateOf(false) }
    var lastAppliedTrimStart by remember(probe.sourceFile.absolutePath) { mutableLongStateOf(-1L) }
    var appliedSpeed by remember(probe.sourceFile.absolutePath) { mutableFloatStateOf(1f) }
    var nativeSpeedSupported by remember(probe.sourceFile.absolutePath) { mutableStateOf(true) }

    val isFullClipLoop = trimStartMs == 0L && trimEndMs >= probe.totalDurationMs - 40L
    val latestTrimStart by rememberUpdatedState(trimStartMs)
    val latestTrimEnd by rememberUpdatedState(trimEndMs)
    val latestIsFullClipLoop by rememberUpdatedState(isFullClipLoop)
    val latestSpeed by rememberUpdatedState(speedMultiplier)
    val latestNativeSpeedSupported by rememberUpdatedState(nativeSpeedSupported)
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
        var manualStepCursorMs = latestTrimStart
        while (true) {
            if (!latestNativeSpeedSupported && abs(latestSpeed - 1f) >= 0.001f) {
                val frameIntervalMs = 45L
                val advanceMs = (frameIntervalMs * latestSpeed).roundToLong().coerceAtLeast(16L)
                manualStepCursorMs += advanceMs
                if (manualStepCursorMs >= latestTrimEnd || manualStepCursorMs < latestTrimStart) {
                    manualStepCursorMs = latestTrimStart
                }
                runCatching {
                    if (player.isPlaying) player.pause()
                    player.seekTo(manualStepCursorMs, MediaPlayer.SEEK_CLOSEST)
                }
                delay(frameIntervalMs)
            } else {
                runCatching {
                    val pos = player.currentPosition.toLong()
                    if (!latestIsFullClipLoop && (pos >= latestTrimEnd || pos < latestTrimStart - 250L)) {
                        player.seekTo(latestTrimStart, MediaPlayer.SEEK_CLOSEST)
                        if (!player.isPlaying) {
                            player.start()
                        }
                    } else if (!player.isPlaying) {
                        player.start()
                    }
                }
                delay(40)
            }
        }
    }

    LaunchedEffect(isFullClipLoop, isPrepared) {
        val player = mediaPlayer ?: return@LaunchedEffect
        if (isPrepared) {
            runCatching { player.isLooping = isFullClipLoop }
        }
    }

    LaunchedEffect(trimStartMs, isPrepared) {
        val player = mediaPlayer ?: return@LaunchedEffect
        if (isPrepared && trimStartMs != lastAppliedTrimStart) {
            lastAppliedTrimStart = trimStartMs
            runCatching {
                player.seekTo(trimStartMs, MediaPlayer.SEEK_CLOSEST)
            }
        }
    }

    LaunchedEffect(speedMultiplier, isPrepared) {
        val player = mediaPlayer ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        if (abs(speedMultiplier - appliedSpeed) < 0.001f && nativeSpeedSupported) return@LaunchedEffect
        delay(60)
        val ok = applyMediaPlayerSpeed(player, speedMultiplier)
        nativeSpeedSupported = ok
        appliedSpeed = speedMultiplier
        if (ok) {
            runCatching {
                if (!player.isPlaying) player.start()
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
            .stickerFramingGestures(
                canvasSizeProvider = { canvasSizePx },
                onTransformGesture = onTransformGesture,
                onTransformGestureEnd = onTransformGestureEnd,
            ),
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
                                mp.isLooping = latestIsFullClipLoop
                                mp.setOnCompletionListener { completed ->
                                    if (!latestIsFullClipLoop) {
                                        runCatching {
                                            completed.seekTo(latestTrimStart, MediaPlayer.SEEK_CLOSEST)
                                            completed.start()
                                        }
                                    }
                                }
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
                                    lastAppliedTrimStart = latestTrimStart
                                    if (latestTrimStart > 0L) {
                                        prepared.seekTo(latestTrimStart, MediaPlayer.SEEK_CLOSEST)
                                    }
                                    prepared.start()
                                    val speedOk = if (abs(latestSpeed - 1f) >= 0.001f) {
                                        applyMediaPlayerSpeed(prepared, latestSpeed)
                                    } else {
                                        true
                                    }
                                    nativeSpeedSupported = speedOk
                                    appliedSpeed = latestSpeed
                                    isPrepared = true
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
        FramingSnapGuidesOverlay(
            guides = snapGuides,
            isGestureActive = isGestureActive,
        )
    }
}

private fun applyMediaPlayerSpeed(player: MediaPlayer, speed: Float): Boolean {
    val withMuteFallback = runCatching {
        player.playbackParams = PlaybackParams()
            .setSpeed(speed)
            .setPitch(1.0f)
            .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_MUTE)
        abs(player.playbackParams.speed - speed) < 0.08f
    }.getOrDefault(false)
    if (withMuteFallback) return true

    return runCatching {
        player.playbackParams = PlaybackParams().setSpeed(speed)
        abs(player.playbackParams.speed - speed) < 0.08f
    }.getOrDefault(false)
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
    snapGuides: FramingSnapGuides,
    isGestureActive: Boolean,
    speedMultiplier: Float,
    onTransformGesture: (zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) -> Unit,
    onTransformGestureEnd: () -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { StickerImageLoaderProvider.get(context) }
    var canvasSizePx by remember { mutableFloatStateOf(0f) }
    val useCustomSpeedPlayer = abs(speedMultiplier - FrameSamplingPlanner.DEFAULT_SPEED_MULTIPLIER) >= 0.001f
    var speedPreviewFrame by remember(probe.sourceFile.absolutePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(probe.sourceFile.absolutePath, speedMultiplier, useCustomSpeedPlayer) {
        if (!useCustomSpeedPlayer) {
            speedPreviewFrame = null
            return@LaunchedEffect
        }
        AnimatedFrameExtractor.playAnimatedPreviewLoop(
            probe = probe,
            speedMultiplier = speedMultiplier,
        ) { bmp ->
            speedPreviewFrame = bmp.asImageBitmap()
        }
    }

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
            .stickerFramingGestures(
                canvasSizeProvider = { canvasSizePx },
                onTransformGesture = onTransformGesture,
                onTransformGestureEnd = onTransformGestureEnd,
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTransparencyCheckerboard(cellSizePx = 16.dp.toPx())
        }
        val contentModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scaleMultiplier
                scaleY = scaleMultiplier
                rotationZ = safeTransform.rotationDegrees.toFloat()
                translationX = safeTransform.normalizedPanX * canvasSizePx
                translationY = safeTransform.normalizedPanY * canvasSizePx
            }
        if (useCustomSpeedPlayer) {
            val frameBitmap = speedPreviewFrame ?: remember(probe.previewBitmap) {
                probe.previewBitmap.asImageBitmap()
            }
            Image(
                bitmap = frameBitmap,
                contentDescription = "Vista previa animada",
                contentScale = ContentScale.Fit,
                modifier = contentModifier,
            )
        } else {
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = "Vista previa animada",
                contentScale = ContentScale.Fit,
                modifier = contentModifier,
            )
        }
        FramingSnapGuidesOverlay(
            guides = snapGuides,
            isGestureActive = isGestureActive,
        )
    }
}

@Composable
private fun VideoTrimCard(
    totalDurationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    rawTrimEndMs: Long,
    speedMultiplier: Float,
    enabled: Boolean,
    onRangeChanged: (Long, Long) -> Unit,
) {
    val safeTotalMs = totalDurationMs.coerceAtLeast(1L)
    val maxDuration = safeTotalMs.toFloat()
    val startVal = trimStartMs.toFloat().coerceIn(0f, maxDuration)
    val endVal = trimEndMs.toFloat().coerceIn(startVal, maxDuration)
    val selectedDurationMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)
    val maxSourceSpanMs = FrameSamplingPlanner.maxSourceDurationForSpeed(speedMultiplier)
    val canTrim = safeTotalMs > 250L

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (canTrim) {
                    "Tramo del vídeo: ${PackValidator.formatSeconds(trimStartMs)} – " +
                        "${PackValidator.formatSeconds(trimEndMs)} " +
                        "(${PackValidator.formatSeconds(selectedDurationMs)} / máx. ${PackValidator.formatSeconds(maxSourceSpanMs)})"
                } else {
                    "Duración del vídeo: ${PackValidator.formatSeconds(safeTotalMs)} " +
                        "(se repetirá en bucle al guardar)"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            if (trimEndMs < rawTrimEndMs) {
                Text(
                    text = "Recortado al máximo de 10 s finales a ${FrameSamplingPlanner.formatSpeedMultiplier(speedMultiplier)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canTrim) {
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedControlCard(
    speedMultiplier: Float,
    rawScaledDurationMs: Long,
    autoLoopMultiplier: Int,
    estimatedOutputDurationMs: Long,
    enabled: Boolean,
    onSpeedChange: (Float) -> Unit,
) {
    val presets = remember { listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Velocidad: ${FrameSamplingPlanner.formatSpeedMultiplier(speedMultiplier)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Salida: ${PackValidator.formatSeconds(estimatedOutputDurationMs)} / máx. 10 s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = speedMultiplier.coerceIn(
                    FrameSamplingPlanner.MIN_SPEED_MULTIPLIER,
                    FrameSamplingPlanner.MAX_SPEED_MULTIPLIER,
                ),
                onValueChange = onSpeedChange,
                valueRange = FrameSamplingPlanner.MIN_SPEED_MULTIPLIER..FrameSamplingPlanner.MAX_SPEED_MULTIPLIER,
                steps = 34,
                enabled = enabled,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = abs(speedMultiplier - preset) < 0.01f,
                        onClick = { onSpeedChange(preset) },
                        enabled = enabled,
                        label = { Text(FrameSamplingPlanner.formatSpeedMultiplier(preset)) },
                    )
                }
            }
            if (autoLoopMultiplier > 1) {
                Text(
                    text = "Duración resultante (${PackValidator.formatSeconds(rawScaledDurationMs)}) menor al mínimo de 0.50 s: " +
                        "se repetirá ${autoLoopMultiplier}x en bucle (${PackValidator.formatSeconds(estimatedOutputDurationMs)}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
