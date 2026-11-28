package io.github.danivi.yanistickers.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.data.FramingMode
import io.github.danivi.yanistickers.data.FramingSnapGuides
import io.github.danivi.yanistickers.data.StaticStickerEncoder
import io.github.danivi.yanistickers.data.StickerFramingTransform
import kotlin.math.abs

/**
 * Lienzo cuadrado interactivo `1:1` con cuadrícula de transparencia, gestos de zoom/paneo y
 * líneas guía de ajuste magnético.
 */
@Composable
fun StickerFramingCanvas(
    bitmap: Bitmap,
    transform: StickerFramingTransform,
    snapGuides: FramingSnapGuides = FramingSnapGuides(),
    isGestureActive: Boolean = false,
    onTransformGesture: (zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) -> Unit,
    onTransformGestureEnd: () -> Unit = {},
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
            .stickerFramingGestures(
                canvasSizeProvider = { canvasSizePx },
                onTransformGesture = onTransformGesture,
                onTransformGestureEnd = onTransformGestureEnd,
            ),
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
        FramingSnapGuidesOverlay(
            guides = snapGuides,
            isGestureActive = isGestureActive,
        )
    }
}

/**
 * Detector de gestos multitáctil compartido para encuadre que notifica también la liberación final
 * de los dedos para ocultar las líneas guía de snap.
 */
@Composable
fun Modifier.stickerFramingGestures(
    canvasSizeProvider: () -> Float,
    onTransformGesture: (zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) -> Unit,
    onTransformGestureEnd: () -> Unit,
): Modifier {
    val latestSizeProvider by rememberUpdatedState(canvasSizeProvider)
    val latestOnGesture by rememberUpdatedState(onTransformGesture)
    val latestOnEnd by rememberUpdatedState(onTransformGestureEnd)

    return pointerInput(Unit) {
        awaitEachGesture {
            var zoom = 1f
            var pan = Offset.Zero
            var pastTouchSlop = false
            val touchSlop = viewConfiguration.touchSlop

            awaitFirstDown(requireUnconsumed = false)
            try {
                do {
                    val event = awaitPointerEvent()
                    val canceled = event.changes.any { it.isConsumed }
                    if (canceled) break

                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()

                    if (!pastTouchSlop) {
                        zoom *= zoomChange
                        pan += panChange

                        val centroidSize = event.calculateCentroidSize(useCurrent = false)
                        val zoomMotion = abs(1f - zoom) * centroidSize
                        val panMotion = pan.getDistance()

                        if (zoomMotion > touchSlop || panMotion > touchSlop) {
                            pastTouchSlop = true
                        }
                    }

                    if (pastTouchSlop) {
                        val canvasSize = latestSizeProvider()
                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            latestOnGesture(zoomChange, panChange.x, panChange.y, canvasSize)
                        }
                        event.changes.forEach { change ->
                            if (change.positionChanged()) {
                                change.consume()
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })
            } finally {
                if (pastTouchSlop) {
                    latestOnEnd()
                }
            }
        }
    }
}

/**
 * Superposición de líneas guía temporales y feedback háptico cuando el sticker se imanta al
 * centro, bordes del lienzo o zoom base.
 */
@Composable
fun FramingSnapGuidesOverlay(
    guides: FramingSnapGuides,
    isGestureActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var previousGuides by remember { mutableStateOf(FramingSnapGuides()) }

    LaunchedEffect(guides, isGestureActive) {
        if (isGestureActive) {
            val enteredNewSnap =
                (guides.centerX && !previousGuides.centerX) ||
                    (guides.centerY && !previousGuides.centerY) ||
                    (guides.leftEdge && !previousGuides.leftEdge) ||
                    (guides.rightEdge && !previousGuides.rightEdge) ||
                    (guides.topEdge && !previousGuides.topEdge) ||
                    (guides.bottomEdge && !previousGuides.bottomEdge) ||
                    (guides.zoomSnap && !previousGuides.zoomSnap)
            if (enteredNewSnap) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            previousGuides = guides
        } else {
            previousGuides = FramingSnapGuides()
        }
    }

    if (!isGestureActive || !guides.hasAnyActive) return

    val guideColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.fillMaxSize()) {
        val lineStroke = 1.5.dp.toPx()
        val haloStroke = 3.5.dp.toPx()
        val haloColor = Color.White.copy(alpha = 0.72f)
        val centerDash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()), 0f)

        fun drawGuideLine(start: Offset, end: Offset, dashed: Boolean) {
            val effect = if (dashed) centerDash else null
            drawLine(
                color = haloColor,
                start = start,
                end = end,
                strokeWidth = haloStroke,
                pathEffect = effect,
            )
            drawLine(
                color = guideColor,
                start = start,
                end = end,
                strokeWidth = lineStroke,
                pathEffect = effect,
            )
        }

        if (guides.centerX) {
            val x = size.width / 2f
            drawGuideLine(Offset(x, 0f), Offset(x, size.height), dashed = true)
        }
        if (guides.centerY) {
            val y = size.height / 2f
            drawGuideLine(Offset(0f, y), Offset(size.width, y), dashed = true)
        }
        val edgeInset = lineStroke / 2f
        if (guides.leftEdge) {
            drawGuideLine(Offset(edgeInset, 0f), Offset(edgeInset, size.height), dashed = false)
        }
        if (guides.rightEdge) {
            val x = size.width - edgeInset
            drawGuideLine(Offset(x, 0f), Offset(x, size.height), dashed = false)
        }
        if (guides.topEdge) {
            drawGuideLine(Offset(0f, edgeInset), Offset(size.width, edgeInset), dashed = false)
        }
        if (guides.bottomEdge) {
            val y = size.height - edgeInset
            drawGuideLine(Offset(0f, y), Offset(size.width, y), dashed = false)
        }
        if (guides.zoomSnap) {
            drawRect(
                color = guideColor.copy(alpha = 0.55f),
                style = Stroke(width = 2.dp.toPx()),
            )
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
