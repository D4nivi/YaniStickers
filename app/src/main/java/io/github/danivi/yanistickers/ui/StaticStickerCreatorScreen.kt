package io.github.danivi.yanistickers.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.data.FramingMode
import io.github.danivi.yanistickers.data.StaticStickerEncoder
import io.github.danivi.yanistickers.data.StickerFramingTransform
import io.github.danivi.yanistickers.validation.PackValidator

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StaticStickerCreatorScreen(
    viewModel: StaticStickerCreatorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var launchedInitialPicker by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.onImageSelected(uri)
        }
    }

    fun openPhotoPicker() {
        pickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    LaunchedEffect(Unit) {
        if (!launchedInitialPicker && state.sourceBitmap == null) {
            launchedInitialPicker = true
            openPhotoPicker()
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.forTray) R.string.creator_tray_title else R.string.creator_static_title,
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
            if (state.sourceBitmap == null) {
                EmptyImageSelectionCard(
                    isDecoding = state.isDecoding,
                    forTray = state.forTray,
                    onPickImage = ::openPhotoPicker,
                )
            } else {
                StickerFramingCanvas(
                    bitmap = state.sourceBitmap!!,
                    transform = state.transform,
                    onTransformGesture = viewModel::applyGesture,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.transform.mode == FramingMode.FIT,
                            onClick = { viewModel.setFramingMode(FramingMode.FIT) },
                            label = { Text("Encajar") },
                        )
                        FilterChip(
                            selected = state.transform.mode == FramingMode.CROP,
                            onClick = { viewModel.setFramingMode(FramingMode.CROP) },
                            label = { Text("Rellenar") },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = viewModel::rotate90) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.RotateRight,
                                contentDescription = "Rotar 90°",
                            )
                        }
                        IconButton(onClick = viewModel::resetTransform) {
                            Icon(
                                imageVector = Icons.Outlined.RestartAlt,
                                contentDescription = "Restablecer encuadre",
                            )
                        }
                    }
                }

                EncodedSizeStatusBanner(
                    state = state,
                    onChangeImage = ::openPhotoPicker,
                )

                if (!state.forTray) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAdvanced = !showAdvanced }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.creator_advanced),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Icon(
                                    imageVector = if (showAdvanced) {
                                        Icons.Outlined.ExpandLess
                                    } else {
                                        Icons.Outlined.ExpandMore
                                    },
                                    contentDescription = if (showAdvanced) "Ocultar avanzado" else "Mostrar avanzado",
                                )
                            }
                            AnimatedVisibility(visible = showAdvanced) {
                                StickerMetadataEditorFields(
                                    nameText = state.nameInput,
                                    onNameTextChange = viewModel::onNameChange,
                                    emojisText = state.emojisInput,
                                    onEmojisTextChange = viewModel::onEmojisChange,
                                    accessibilityText = state.accessibilityInput,
                                    onAccessibilityTextChange = viewModel::onAccessibilityChange,
                                    animatedPack = false,
                                    modifier = Modifier.padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        bottom = 16.dp,
                                    ),
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (state.forTray) R.string.action_save_tray else R.string.action_save_sticker,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyImageSelectionCard(
    isDecoding: Boolean,
    forTray: Boolean,
    onPickImage: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isDecoding) {
                CircularProgressIndicator()
                Text("Cargando imagen...")
            } else {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (forTray) {
                        "Elige una imagen para el icono de bandeja (96×96 px)"
                    } else {
                        "Elige una imagen o PNG transparente para tu sticker (512×512 px)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onPickImage) {
                    Text(stringResource(R.string.action_pick_image))
                }
            }
        }
    }
}

@Composable
private fun StickerFramingCanvas(
    bitmap: Bitmap,
    transform: StickerFramingTransform,
    onTransformGesture: (zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) -> Unit,
) {
    var canvasSizePx by remember { mutableFloatStateOf(0f) }
    val drawPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
            drawCheckerboard(cellSizePx = 16.dp.toPx())
            val matrix = StaticStickerEncoder.buildRenderMatrix(
                srcWidth = bitmap.width,
                srcHeight = bitmap.height,
                canvasSizePx = size.width,
                transform = transform,
            )
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(bitmap, matrix, drawPaint)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckerboard(cellSizePx: Float) {
    val light = Color(0xFFF2F2F2)
    val dark = Color(0xFFDCDCDC)
    drawRect(color = light)
    val cols = (size.width / cellSizePx).toInt() + 1
    val rows = (size.height / cellSizePx).toInt() + 1
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            if ((row + col) % 2 == 1) {
                drawRect(
                    color = dark,
                    topLeft = Offset(col * cellSizePx, row * cellSizePx),
                    size = Size(cellSizePx, cellSizePx),
                )
            }
        }
    }
}

@Composable
private fun EncodedSizeStatusBanner(
    state: StaticStickerCreatorUiState,
    onChangeImage: () -> Unit,
) {
    val isError = state.encodeError != null
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val maxKb = state.maxBytes / 1024
                val statusText = when {
                    state.isEncoding -> "Calculando WebP (${state.targetDimensionPx}×${state.targetDimensionPx})..."
                    state.encodeError != null -> state.encodeError
                    state.encodedBytes != null -> {
                        val sizeKb = PackValidator.formatKb(state.encodedBytes.size.toLong())
                        val modeLabel = if (state.usedLossless) "sin pérdida" else "optimizado"
                        "WebP ${state.targetDimensionPx}×${state.targetDimensionPx} · $sizeKb / $maxKb KB ($modeLabel)"
                    }
                    else -> "Preparando..."
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onChangeImage) {
                Text("Cambiar")
            }
        }
    }
}
