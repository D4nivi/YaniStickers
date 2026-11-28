package io.github.danivi.yanistickers.data

import kotlin.math.abs
import kotlin.math.max

/**
 * Estado de las líneas guía activas mientras el usuario ajusta el encuadre del sticker.
 */
data class FramingSnapGuides(
    val centerX: Boolean = false,
    val centerY: Boolean = false,
    val leftEdge: Boolean = false,
    val rightEdge: Boolean = false,
    val topEdge: Boolean = false,
    val bottomEdge: Boolean = false,
    val zoomSnap: Boolean = false,
) {
    val hasAnyActive: Boolean
        get() = centerX || centerY || leftEdge || rightEdge || topEdge || bottomEdge || zoomSnap
}

/**
 * Resultado de aplicar ajuste magnético (snap) a una transformación cruda de gestos.
 */
data class FramingSnapResult(
    val transform: StickerFramingTransform,
    val guides: FramingSnapGuides,
)

/**
 * Calcula el ajuste magnético (snap) al centro horizontal/vertical, a los bordes del lienzo `1:1`
 * y a los puntos clave de zoom, manteniendo intacta la transformación cruda externa para evitar
 * que el gesto quede «pegado».
 */
object StickerFramingSnap {

    const val PAN_SNAP_THRESHOLD = 0.025f
    const val ZOOM_SNAP_THRESHOLD = 0.045f
    private const val EDGE_COINCIDENCE_EPSILON = 0.008f

    fun applySnap(
        rawTransform: StickerFramingTransform,
        srcWidth: Int,
        srcHeight: Int,
        contentBounds: OpaqueContentBounds? = null,
    ): FramingSnapResult {
        val raw = rawTransform.normalized()
        if (srcWidth <= 0 || srcHeight <= 0) {
            return FramingSnapResult(transform = raw, guides = FramingSnapGuides())
        }

        val (snappedZoom, zoomSnapped) = snapZoom(
            raw = raw,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            contentBounds = contentBounds,
        )

        val baseScaleUnit = StaticStickerEncoder.computeBaseScale(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            canvasSizePx = 1f,
            mode = raw.mode,
            rotationDegrees = raw.rotationDegrees,
        )
        val scale = baseScaleUnit * snappedZoom
        val rotatedOdd = raw.rotationDegrees % 180 != 0

        val validBounds = contentBounds?.takeIf {
            !it.fillsImage &&
                it.imageWidth == srcWidth &&
                it.imageHeight == srcHeight
        }

        val boxCenterOffsetX: Float
        val boxCenterOffsetY: Float
        val boxWidthNorm: Float
        val boxHeightNorm: Float

        if (validBounds != null) {
            val dx = (validBounds.left + validBounds.right) / 2f - srcWidth / 2f
            val dy = (validBounds.top + validBounds.bottom) / 2f - srcHeight / 2f
            val (rx, ry) = rotateOffset(dx, dy, raw.rotationDegrees)
            boxCenterOffsetX = rx * scale
            boxCenterOffsetY = ry * scale
            boxWidthNorm = (if (rotatedOdd) validBounds.height else validBounds.width) * scale
            boxHeightNorm = (if (rotatedOdd) validBounds.width else validBounds.height) * scale
        } else {
            boxCenterOffsetX = 0f
            boxCenterOffsetY = 0f
            boxWidthNorm = (if (rotatedOdd) srcHeight else srcWidth) * scale
            boxHeightNorm = (if (rotatedOdd) srcWidth else srcHeight) * scale
        }

        val (snappedPanX, centerX, leftEdge, rightEdge) = snapAxis(
            rawPan = raw.normalizedPanX,
            boxCenterOffset = boxCenterOffsetX,
            boxSizeNorm = boxWidthNorm,
        )
        val (snappedPanY, centerY, topEdge, bottomEdge) = snapAxis(
            rawPan = raw.normalizedPanY,
            boxCenterOffset = boxCenterOffsetY,
            boxSizeNorm = boxHeightNorm,
        )

        val snappedTransform = raw.copy(
            zoom = snappedZoom,
            normalizedPanX = snappedPanX,
            normalizedPanY = snappedPanY,
        ).normalized()

        return FramingSnapResult(
            transform = snappedTransform,
            guides = FramingSnapGuides(
                centerX = centerX,
                centerY = centerY,
                leftEdge = leftEdge,
                rightEdge = rightEdge,
                topEdge = topEdge,
                bottomEdge = bottomEdge,
                zoomSnap = zoomSnapped,
            ),
        )
    }

    private fun snapZoom(
        raw: StickerFramingTransform,
        srcWidth: Int,
        srcHeight: Int,
        contentBounds: OpaqueContentBounds?,
    ): Pair<Float, Boolean> {
        val candidates = mutableListOf(StickerFramingTransform.MIN_ZOOM)
        if (raw.mode == FramingMode.FIT) {
            val fillZoom = if (contentBounds != null &&
                !contentBounds.fillsImage &&
                contentBounds.imageWidth == srcWidth &&
                contentBounds.imageHeight == srcHeight
            ) {
                StaticStickerEncoder.buildCropTransformForBounds(contentBounds, raw.rotationDegrees).zoom
            } else {
                val fitUnit = StaticStickerEncoder.computeBaseScale(
                    srcWidth = srcWidth,
                    srcHeight = srcHeight,
                    canvasSizePx = 1f,
                    mode = FramingMode.FIT,
                    rotationDegrees = raw.rotationDegrees,
                )
                val cropUnit = StaticStickerEncoder.computeBaseScale(
                    srcWidth = srcWidth,
                    srcHeight = srcHeight,
                    canvasSizePx = 1f,
                    mode = FramingMode.CROP,
                    rotationDegrees = raw.rotationDegrees,
                )
                if (fitUnit > 0f) cropUnit / fitUnit else 1f
            }
            if (fillZoom in 1.05f..StickerFramingTransform.MAX_ZOOM) {
                candidates += fillZoom
            }
        }

        var bestCandidate: Float? = null
        var bestDistance = Float.MAX_VALUE
        for (candidate in candidates) {
            val distance = abs(raw.zoom - candidate)
            val threshold = ZOOM_SNAP_THRESHOLD * max(1f, candidate)
            if (distance <= threshold && distance < bestDistance) {
                bestDistance = distance
                bestCandidate = candidate
            }
        }
        return if (bestCandidate != null) bestCandidate to true else raw.zoom to false
    }

    private data class AxisSnapResult(
        val snappedPan: Float,
        val snappedCenter: Boolean,
        val snappedStartEdge: Boolean,
        val snappedEndEdge: Boolean,
    )

    private fun snapAxis(
        rawPan: Float,
        boxCenterOffset: Float,
        boxSizeNorm: Float,
    ): AxisSnapResult {
        val centerTarget = -boxCenterOffset
        val startEdgeTarget = (boxSizeNorm - 1f) / 2f - boxCenterOffset
        val endEdgeTarget = (1f - boxSizeNorm) / 2f - boxCenterOffset

        val distCenter = abs(rawPan - centerTarget)
        val distStart = abs(rawPan - startEdgeTarget)
        val distEnd = abs(rawPan - endEdgeTarget)

        val edgesMatchCenter = abs(boxSizeNorm - 1f) <= EDGE_COINCIDENCE_EPSILON

        if (distCenter <= PAN_SNAP_THRESHOLD && distCenter <= distStart && distCenter <= distEnd) {
            return AxisSnapResult(
                snappedPan = centerTarget,
                snappedCenter = true,
                snappedStartEdge = edgesMatchCenter,
                snappedEndEdge = edgesMatchCenter,
            )
        }
        if (distStart <= PAN_SNAP_THRESHOLD && distStart <= distEnd) {
            return AxisSnapResult(
                snappedPan = startEdgeTarget,
                snappedCenter = abs(startEdgeTarget - centerTarget) <= EDGE_COINCIDENCE_EPSILON,
                snappedStartEdge = true,
                snappedEndEdge = edgesMatchCenter,
            )
        }
        if (distEnd <= PAN_SNAP_THRESHOLD) {
            return AxisSnapResult(
                snappedPan = endEdgeTarget,
                snappedCenter = abs(endEdgeTarget - centerTarget) <= EDGE_COINCIDENCE_EPSILON,
                snappedStartEdge = edgesMatchCenter,
                snappedEndEdge = true,
            )
        }
        return AxisSnapResult(
            snappedPan = rawPan,
            snappedCenter = false,
            snappedStartEdge = false,
            snappedEndEdge = false,
        )
    }

    private fun rotateOffset(dx: Float, dy: Float, rotationDegrees: Int): Pair<Float, Float> {
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> -dy to dx
            180 -> -dx to -dy
            270 -> dy to -dx
            else -> dx to dy
        }
    }
}
