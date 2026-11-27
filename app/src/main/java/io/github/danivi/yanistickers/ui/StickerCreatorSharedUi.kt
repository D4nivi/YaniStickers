package io.github.danivi.yanistickers.ui

import android.graphics.Bitmap
import android.graphics.Paint
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.data.FramingMode
import io.github.danivi.yanistickers.data.StaticStickerEncoder
import io.github.danivi.yanistickers.data.StickerFramingTransform

/**
 * Lienzo cuadrado interactivo `1:1` con cuadrícula de transparencia y gestos de zoom/paneo.
 */
@Composable
fun StickerFramingCanvas(
    bitmap: Bitmap,
    transform: StickerFramingTransform,
    onTransformGesture: (zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSizePx by remember { mutableFloatStateOf(0f) }
    val drawPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }

    Box(
        modifier = modifier
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

fun DrawScope.drawTransparencyCheckerboard(cellSizePx: Float) {
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

/**
 * Barra compartida de controles de encuadre (`Encajar`/`Rellenar`, rotación 90°, restablecer y
 * cambiar archivo de origen).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StickerFramingControlsRow(
    mode: FramingMode,
    onSelectMode: (FramingMode) -> Unit,
    onRotate90: () -> Unit,
    onReset: () -> Unit,
    onChangeSource: () -> Unit,
    changeSourceIcon: ImageVector = Icons.Outlined.AddPhotoAlternate,
    changeSourceContentDescription: String = "Cambiar archivo",
    changeSourceMenu: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == FramingMode.FIT,
                onClick = { onSelectMode(FramingMode.FIT) },
                label = { Text("Encajar") },
            )
            FilterChip(
                selected = mode == FramingMode.CROP,
                onClick = { onSelectMode(FramingMode.CROP) },
                label = { Text("Rellenar") },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRotate90) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.RotateRight,
                    contentDescription = "Rotar 90°",
                )
            }
            IconButton(onClick = onReset) {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = "Restablecer encuadre",
                )
            }
            Box {
                IconButton(onClick = onChangeSource) {
                    Icon(
                        imageVector = changeSourceIcon,
                        contentDescription = changeSourceContentDescription,
                    )
                }
                changeSourceMenu()
            }
        }
    }
}

/**
 * Tarjeta colapsable "Avanzado" con nombre interno, selector de emojis y accesibilidad.
 */
@Composable
fun AdvancedStickerMetadataCard(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    nameText: String,
    onNameTextChange: (String) -> Unit,
    emojisText: String,
    onEmojisTextChange: (String) -> Unit,
    accessibilityText: String,
    onAccessibilityTextChange: (String) -> Unit,
    animatedPack: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
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
                    imageVector = if (expanded) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    contentDescription = if (expanded) "Ocultar avanzado" else "Mostrar avanzado",
                )
            }
            AnimatedVisibility(visible = expanded) {
                StickerMetadataEditorFields(
                    nameText = nameText,
                    onNameTextChange = onNameTextChange,
                    emojisText = emojisText,
                    onEmojisTextChange = onEmojisTextChange,
                    accessibilityText = accessibilityText,
                    onAccessibilityTextChange = onAccessibilityTextChange,
                    animatedPack = animatedPack,
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
