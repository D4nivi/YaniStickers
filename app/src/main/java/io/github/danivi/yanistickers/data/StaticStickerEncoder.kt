package io.github.danivi.yanistickers.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import io.github.danivi.yanistickers.validation.ImageContainerFormat
import io.github.danivi.yanistickers.validation.ImageContainerInspector
import io.github.danivi.yanistickers.validation.PackValidator
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Modo de encuadre inicial sobre el lienzo cuadrado `1:1`.
 */
enum class FramingMode {
    FIT,
    CROP,
}

/**
 * Transformación normalizada del usuario independiente del tamaño del lienzo de previsualización o salida.
 */
data class StickerFramingTransform(
    val mode: FramingMode = FramingMode.FIT,
    val zoom: Float = 1f,
    val normalizedPanX: Float = 0f,
    val normalizedPanY: Float = 0f,
    val rotationDegrees: Int = 0,
) {
    fun normalized(): StickerFramingTransform {
        val rot = ((rotationDegrees % 360) + 360) % 360
        val snappedRot = ((rot / 90) * 90) % 360
        return copy(
            zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
            normalizedPanX = normalizedPanX.coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
            normalizedPanY = normalizedPanY.coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
            rotationDegrees = snappedRot,
        )
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
        const val MAX_PAN_FRACTION = 1.25f
    }
}

/**
 * Rectángulo envolvente de los píxeles visibles (no transparentes) dentro de un bitmap.
 */
data class OpaqueContentBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val fillsImage: Boolean
        get() = left <= 0 && top <= 0 && right >= imageWidth && bottom >= imageHeight
}

/**
 * Resultado de la codificación WebP estática validado frente a los límites de WhatsApp.
 */
data class EncodedWebpResult(
    val bytes: ByteArray,
    val usedLossless: Boolean,
    val quality: Int,
) {
    val sizeBytes: Long
        get() = bytes.size.toLong()
}

/**
 * Calcula y aplica el encuadre `1:1` con transparencia y la compresión WebP ajustada al tamaño máximo.
 */
object StaticStickerEncoder {

    private const val MAX_DECODE_DIMENSION = 2048
    val LOSSY_QUALITY_STEPS = intArrayOf(92, 85, 78, 70, 62, 54, 46, 38, 30, 22, 15, 10, 5)

    /**
     * Decodifica una imagen desde [uri] respetando su orientación EXIF y canal alfa, reescalando
     * entradas muy grandes para evitar picos de memoria.
     */
    fun decodeSourceBitmap(context: Context, uri: Uri): Bitmap =
        decodeSourceBitmap(ImageDecoder.createSource(context.contentResolver, uri))

    /**
     * Decodifica una imagen local desde [file] respetando canal alfa y reescalando si supera el
     * máximo permitido en memoria.
     */
    fun decodeSourceBitmap(file: File): Bitmap =
        decodeSourceBitmap(ImageDecoder.createSource(file))

    private fun decodeSourceBitmap(source: ImageDecoder.Source): Bitmap {
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val srcW = info.size.width
            val srcH = info.size.height
            val longest = max(srcW, srcH)
            if (longest > MAX_DECODE_DIMENSION && srcW > 0 && srcH > 0) {
                val scale = MAX_DECODE_DIMENSION.toFloat() / longest.toFloat()
                val targetW = (srcW * scale).roundToInt().coerceAtLeast(1)
                val targetH = (srcH * scale).roundToInt().coerceAtLeast(1)
                decoder.setTargetSize(targetW, targetH)
            }
        }
    }

    /**
     * Genera un icono de bandeja estático `96×96` (`≤ 50 KB`) a partir de los bytes de un sticker
     * WebP (si es animado, extrae automáticamente su primer frame).
     */
    fun encodeWebpBytesAsTray(webpBytes: ByteArray): ByteArray =
        encodeSourceAsTray(ImageDecoder.createSource(ByteBuffer.wrap(webpBytes)))

    /**
     * Genera un icono de bandeja estático `96×96` (`≤ 50 KB`) a partir de un archivo de sticker en disco
     * (si es animado, extrae automáticamente su primer frame).
     */
    fun encodeWebpFileAsTray(stickerFile: File): ByteArray =
        encodeSourceAsTray(ImageDecoder.createSource(stickerFile))

    private fun encodeSourceAsTray(source: ImageDecoder.Source): ByteArray {
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return try {
            renderAndEncodeWebp(
                sourceBitmap = decoded,
                transform = StickerFramingTransform(mode = FramingMode.FIT),
                outputSizePx = PackValidator.RECOMMENDED_TRAY_DIMENSION_PX,
                maxBytes = PackValidator.MAX_TRAY_BYTES,
            ).bytes
        } finally {
            decoded.recycle()
        }
    }

    /**
     * Calcula el factor de escala base para encajar o rellenar un lienzo cuadrado [canvasSizePx].
     */
    fun computeBaseScale(
        srcWidth: Int,
        srcHeight: Int,
        canvasSizePx: Float,
        mode: FramingMode,
        rotationDegrees: Int,
    ): Float {
        if (srcWidth <= 0 || srcHeight <= 0 || canvasSizePx <= 0f) return 1f
        val normalizedRot = ((rotationDegrees % 360) + 360) % 360
        val rotatedOdd = normalizedRot % 180 != 0
        val effectiveW = (if (rotatedOdd) srcHeight else srcWidth).toFloat()
        val effectiveH = (if (rotatedOdd) srcWidth else srcHeight).toFloat()
        return when (mode) {
            FramingMode.FIT -> min(canvasSizePx / effectiveW, canvasSizePx / effectiveH)
            FramingMode.CROP -> max(canvasSizePx / effectiveW, canvasSizePx / effectiveH)
        }
    }

    /**
     * Calcula la caja envolvente de los píxeles no transparentes de [bitmap].
     */
    fun computeOpaqueContentBounds(
        bitmap: Bitmap,
        alphaThreshold: Int = 8,
    ): OpaqueContentBounds {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) {
            return OpaqueContentBounds(0, 0, w, h, w, h)
        }
        val row = IntArray(w)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val alpha = (row[x] ushr 24) and 0xFF
                if (alpha > alphaThreshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < minX || maxY < minY) {
            OpaqueContentBounds(0, 0, w, h, w, h)
        } else {
            OpaqueContentBounds(minX, minY, maxX + 1, maxY + 1, w, h)
        }
    }

    /**
     * Combina dos cajas envolventes para obtener la unión visible entre múltiples frames.
     */
    fun mergeOpaqueContentBounds(
        current: OpaqueContentBounds?,
        next: OpaqueContentBounds,
    ): OpaqueContentBounds {
        if (current == null) return next
        return OpaqueContentBounds(
            left = min(current.left, next.left),
            top = min(current.top, next.top),
            right = max(current.right, next.right),
            bottom = max(current.bottom, next.bottom),
            imageWidth = next.imageWidth,
            imageHeight = next.imageHeight,
        )
    }

    /**
     * Calcula el encuadre `Rellenar` (`CROP`) equivalente teniendo en cuenta los márgenes
     * transparentes de un sticker ya renderizado en `512×512`.
     */
    fun buildCropTransformForBounds(
        bounds: OpaqueContentBounds?,
        rotationDegrees: Int = 0,
    ): StickerFramingTransform {
        val snappedRot = (((rotationDegrees % 360) + 360) % 360 / 90) * 90 % 360
        if (bounds == null || bounds.fillsImage) {
            return StickerFramingTransform(
                mode = FramingMode.CROP,
                rotationDegrees = snappedRot,
            ).normalized()
        }
        val w = bounds.imageWidth.toFloat()
        val h = bounds.imageHeight.toFloat()
        val rotatedOdd = snappedRot % 180 != 0
        val effW = if (rotatedOdd) h else w
        val effH = if (rotatedOdd) w else h
        val rotCw = (if (rotatedOdd) bounds.height else bounds.width).toFloat()
        val rotCh = (if (rotatedOdd) bounds.width else bounds.height).toFloat()
        val baseScaleUnit = computeBaseScale(
            srcWidth = bounds.imageWidth,
            srcHeight = bounds.imageHeight,
            canvasSizePx = 1f,
            mode = FramingMode.CROP,
            rotationDegrees = snappedRot,
        )
        val neededScaleUnit = max(1f / rotCw, 1f / rotCh)
        val fallbackScaleUnit = max(1f / effW, 1f / effH)
        val zoom = (neededScaleUnit / fallbackScaleUnit)
            .coerceIn(StickerFramingTransform.MIN_ZOOM, StickerFramingTransform.MAX_ZOOM)

        val dx = (bounds.left + bounds.right) / 2f - w / 2f
        val dy = (bounds.top + bounds.bottom) / 2f - h / 2f
        val (rx, ry) = when (snappedRot) {
            90 -> -dy to dx
            180 -> -dx to -dy
            270 -> dy to -dx
            else -> dx to dy
        }
        val panX = -rx * baseScaleUnit * zoom
        val panY = -ry * baseScaleUnit * zoom
        return StickerFramingTransform(
            mode = FramingMode.CROP,
            zoom = zoom,
            normalizedPanX = panX,
            normalizedPanY = panY,
            rotationDegrees = snappedRot,
        ).normalized()
    }

    /**
     * Calcula la transformación al cambiar entre `Encajar` (`FIT`) y `Rellenar` (`CROP`),
     * adaptando `CROP` al contenido visible cuando se edita un sticker `512×512`.
     */
    fun transformForFramingMode(
        mode: FramingMode,
        rotationDegrees: Int,
        contentBounds: OpaqueContentBounds?,
    ): StickerFramingTransform = when (mode) {
        FramingMode.FIT -> StickerFramingTransform(
            mode = FramingMode.FIT,
            rotationDegrees = rotationDegrees,
        ).normalized()
        FramingMode.CROP -> buildCropTransformForBounds(contentBounds, rotationDegrees)
    }

    /**
     * Rota 90° la transformación actual manteniendo el ajuste `Rellenar` sobre [contentBounds]
     * cuando está activo en modo edición.
     */
    fun rotateTransform90(
        current: StickerFramingTransform,
        contentBounds: OpaqueContentBounds?,
    ): StickerFramingTransform {
        val nextRot = (current.rotationDegrees + 90) % 360
        return if (current.mode == FramingMode.CROP && contentBounds != null && !contentBounds.fillsImage) {
            buildCropTransformForBounds(contentBounds, nextRot)
        } else {
            current.copy(
                rotationDegrees = nextRot,
                normalizedPanX = 0f,
                normalizedPanY = 0f,
            ).normalized()
        }
    }

    /**
     * Construye la matriz de transformación para dibujar una imagen [srcWidth]×[srcHeight] en un
     * lienzo cuadrado [canvasSizePx]×[canvasSizePx] (compartida entre vista previa y salida final).
     */
    fun buildRenderMatrix(
        srcWidth: Int,
        srcHeight: Int,
        canvasSizePx: Float,
        transform: StickerFramingTransform,
    ): Matrix {
        val safe = transform.normalized()
        val baseScale = computeBaseScale(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            canvasSizePx = canvasSizePx,
            mode = safe.mode,
            rotationDegrees = safe.rotationDegrees,
        )
        val scale = baseScale * safe.zoom
        val center = canvasSizePx / 2f

        return Matrix().apply {
            setTranslate(-srcWidth / 2f, -srcHeight / 2f)
            if (safe.rotationDegrees != 0) {
                postRotate(safe.rotationDegrees.toFloat())
            }
            postScale(scale, scale)
            postTranslate(
                center + safe.normalizedPanX * canvasSizePx,
                center + safe.normalizedPanY * canvasSizePx,
            )
        }
    }

    /**
     * Renderiza [sourceBitmap] en un nuevo `Bitmap` cuadrado [outputSizePx]×[outputSizePx] con fondo
     * transparente y lo codifica en WebP cumpliendo [maxBytes].
     */
    fun renderAndEncodeWebp(
        sourceBitmap: Bitmap,
        transform: StickerFramingTransform,
        outputSizePx: Int = PackValidator.STICKER_DIMENSION_PX,
        maxBytes: Long = PackValidator.MAX_STATIC_STICKER_BYTES,
    ): EncodedWebpResult {
        val outputBitmap = Bitmap.createBitmap(outputSizePx, outputSizePx, Bitmap.Config.ARGB_8888)
        try {
            outputBitmap.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(outputBitmap)
            val matrix = buildRenderMatrix(
                srcWidth = sourceBitmap.width,
                srcHeight = sourceBitmap.height,
                canvasSizePx = outputSizePx.toFloat(),
                transform = transform,
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(sourceBitmap, matrix, paint)

            val encoded = selectBestEncodedPayload(
                maxBytes = maxBytes,
                encodeLossless = { compressBitmap(outputBitmap, Bitmap.CompressFormat.WEBP_LOSSLESS, 100) },
                encodeLossy = { quality -> compressBitmap(outputBitmap, Bitmap.CompressFormat.WEBP_LOSSY, quality) },
            )

            val info = ImageContainerInspector.inspectBytes(encoded.bytes)
                ?: error("No se pudo verificar el archivo WebP generado.")
            check(info.format == ImageContainerFormat.WEBP && !info.isAnimated) {
                "El resultado generado no es un WebP estático válido."
            }
            check(info.width == outputSizePx && info.height == outputSizePx) {
                "Dimensiones inesperadas del WebP generado (${info.width}×${info.height})."
            }
            return encoded
        } finally {
            outputBitmap.recycle()
        }
    }

    /**
     * Selecciona primero compresión sin pérdida y, si supera [maxBytes], desciende por los niveles
     * de calidad con pérdida hasta encontrar el primer resultado válido.
     */
    internal fun selectBestEncodedPayload(
        maxBytes: Long,
        encodeLossless: () -> ByteArray,
        encodeLossy: (Int) -> ByteArray,
    ): EncodedWebpResult {
        val losslessBytes = encodeLossless()
        if (losslessBytes.isNotEmpty() && losslessBytes.size.toLong() <= maxBytes) {
            return EncodedWebpResult(
                bytes = losslessBytes,
                usedLossless = true,
                quality = 100,
            )
        }

        for (quality in LOSSY_QUALITY_STEPS) {
            val lossyBytes = encodeLossy(quality)
            if (lossyBytes.isNotEmpty() && lossyBytes.size.toLong() <= maxBytes) {
                return EncodedWebpResult(
                    bytes = lossyBytes,
                    usedLossless = false,
                    quality = quality,
                )
            }
        }

        val maxKb = maxBytes / 1024
        error("La imagen supera el límite de $maxKb KB incluso con compresión máxima.")
    }

    private fun compressBitmap(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, quality, out)) {
            "No se pudo comprimir la imagen en formato WebP."
        }
        return out.toByteArray()
    }
}
