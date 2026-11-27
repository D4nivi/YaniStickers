package io.github.danivi.yanistickers.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.github.danivi.yanistickers.validation.ImageContainerFormat
import io.github.danivi.yanistickers.validation.ImageContainerInspector
import io.github.danivi.yanistickers.validation.InspectedImageInfo
import io.github.danivi.yanistickers.validation.PackValidator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AnimatedSourceKind {
    WEBP,
    GIF,
    VIDEO,
}

/**
 * Resultado de inspeccionar un archivo multimedia copiado a caché local para crear un sticker animado.
 */
data class AnimatedSourceProbe(
    val sourceFile: File,
    val kind: AnimatedSourceKind,
    val width: Int,
    val height: Int,
    val totalDurationMs: Long,
    val frameDurationsMs: List<Int>,
    val previewBitmap: Bitmap,
    val inspectedWebp: InspectedImageInfo? = null,
) {
    /**
     * Indica si la entrada ya es un WebP animado que cumple íntegramente los requisitos de WhatsApp
     * y puede importarse directamente sin recomprimir.
     */
    val isDirectValidWebp: Boolean
        get() = isDirectValidAnimatedWebp(kind, inspectedWebp)

    companion object {
        fun isDirectValidAnimatedWebp(
            kind: AnimatedSourceKind,
            info: InspectedImageInfo?,
        ): Boolean {
            if (info == null || kind != AnimatedSourceKind.WEBP) return false
            if (info.format != ImageContainerFormat.WEBP || !info.isAnimated || info.frameCount <= 1) {
                return false
            }
            if (info.width != PackValidator.STICKER_DIMENSION_PX ||
                info.height != PackValidator.STICKER_DIMENSION_PX
            ) {
                return false
            }
            if (info.fileSizeBytes <= 0L || info.fileSizeBytes > PackValidator.MAX_ANIMATED_STICKER_BYTES) {
                return false
            }
            if (info.totalDurationMs > PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS) {
                return false
            }
            val minFrame = info.minFrameDurationMs ?: return false
            return minFrame >= PackValidator.MIN_ANIMATED_FRAME_DURATION_MS
        }
    }
}

/**
 * Inspecciona el origen seleccionado (WebP animado, GIF o vídeo MP4/WebM) y extrae frames
 * renderizados a `512×512` de forma incremental para evitar picos de memoria.
 */
object AnimatedFrameExtractor {

    private const val MAX_SOURCE_TEMP_BYTES = 128L * 1024L * 1024L
    private const val MAX_PREVIEW_DIM = 1024
    private const val MAX_VIDEO_FRAME_DIM = 768
    private const val MAX_INITIAL_ANIM_FPS = 20

    fun createSessionTempDir(context: Context): File {
        val root = File(context.cacheDir, "animated_creator_sessions")
        root.mkdirs()
        val sessionDir = File(root, UUID.randomUUID().toString().replace("-", "").take(12))
        sessionDir.mkdirs()
        return sessionDir
    }

    /**
     * Limpia sesiones huérfanas antiguas de la caché temporal.
     */
    fun cleanupStaleSessions(context: Context, keepDir: File? = null) {
        val root = File(context.cacheDir, "animated_creator_sessions")
        if (!root.isDirectory) return
        root.listFiles()?.forEach { child ->
            if (keepDir == null || child.absolutePath != keepDir.absolutePath) {
                child.deleteRecursively()
            }
        }
    }

    /**
     * Copia el contenido de [uri] a un archivo temporal en [sessionDir] y analiza su formato,
     * dimensiones, duración y frame de vista previa.
     */
    fun probeUri(
        context: Context,
        uri: Uri,
        sessionDir: File,
    ): AnimatedSourceProbe {
        sessionDir.mkdirs()
        val tempFile = File(sessionDir, "source_${System.nanoTime()}")
        copyUriToFile(context, uri, tempFile)

        val header = readHeaderBytes(tempFile, 32)
        val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase()

        return when {
            isWebpHeader(header) -> probeWebp(tempFile)
            isGifHeader(header) || mimeType.contains("gif") -> probeGif(tempFile)
            else -> probeVideo(tempFile)
        }
    }

    /**
     * Extrae y renderiza los frames del origen aplicando [transform] sobre un lienzo `512×512` con
     * transparencia, entregando cada frame por callback sin acumular bitmaps en RAM.
     */
    fun streamSourceFrames(
        probe: AnimatedSourceProbe,
        transform: StickerFramingTransform,
        trimStartMs: Long,
        trimEndMs: Long,
        videoFps: Int,
        onFrameRgba: (index: Int, totalEstimated: Int, durationMs: Int, directRgbaBuffer: java.nio.ByteBuffer) -> Unit,
    ): List<Int> {
        return when (probe.kind) {
            AnimatedSourceKind.VIDEO -> streamVideoFrames(
                probe = probe,
                transform = transform,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                targetFps = videoFps,
                onFrameRgba = onFrameRgba,
            )
            AnimatedSourceKind.WEBP -> streamBitmapSourceAsRgba(probe, transform, onFrameRgba) { emit ->
                renderWebpFramesCallback(probe, transform, emit)
            }
            AnimatedSourceKind.GIF -> streamBitmapSourceAsRgba(probe, transform, onFrameRgba) { emit ->
                renderGifFramesCallback(probe, transform, emit)
            }
        }
    }

    private inline fun streamBitmapSourceAsRgba(
        @Suppress("UNUSED_PARAMETER") probe: AnimatedSourceProbe,
        @Suppress("UNUSED_PARAMETER") transform: StickerFramingTransform,
        noinline onFrameRgba: (index: Int, totalEstimated: Int, durationMs: Int, directRgbaBuffer: java.nio.ByteBuffer) -> Unit,
        block: ((index: Int, total: Int, durationMs: Int, bitmap: Bitmap) -> Unit) -> List<Int>,
    ): List<Int> {
        val sizePx = PackValidator.STICKER_DIMENSION_PX
        val directRgba = java.nio.ByteBuffer.allocateDirect(sizePx * sizePx * 4)
        val rowPixels = IntArray(sizePx)
        val outBytes = ByteArray(sizePx * sizePx * 4)
        return block { index, total, durationMs, bitmap ->
            // Extraer RGBA no premultiplicado del Bitmap reciclable
            var outIdx = 0
            for (y in 0 until sizePx) {
                bitmap.getPixels(rowPixels, 0, sizePx, 0, y, sizePx, 1)
                for (x in 0 until sizePx) {
                    val c = rowPixels[x]
                    outBytes[outIdx++] = ((c ushr 16) and 0xFF).toByte()
                    outBytes[outIdx++] = ((c ushr 8) and 0xFF).toByte()
                    outBytes[outIdx++] = (c and 0xFF).toByte()
                    outBytes[outIdx++] = ((c ushr 24) and 0xFF).toByte()
                }
            }
            directRgba.clear()
            directRgba.put(outBytes)
            directRgba.rewind()
            onFrameRgba(index, total, durationMs, directRgba)
        }
    }

    private fun probeWebp(file: File): AnimatedSourceProbe {
        val info = ImageContainerInspector.inspect(file)
            ?: error("El archivo WebP seleccionado está dañado o no es válido.")
        require(info.isAnimated && info.frameCount > 1) {
            "El archivo WebP seleccionado es estático. Elige un WebP animado, GIF o vídeo."
        }
        val bytes = file.readBytes()
        val preview = NativeWebpCodec.openDecoder(bytes).use { decoder ->
            val frame = Bitmap.createBitmap(decoder.width, decoder.height, Bitmap.Config.ARGB_8888)
            val duration = decoder.decodeNextFrame(frame)
            if (duration == null) {
                frame.recycle()
                error("No se pudo decodificar el primer frame del WebP animado.")
            }
            scaleDownIfNeeded(frame, MAX_PREVIEW_DIM)
        }
        return AnimatedSourceProbe(
            sourceFile = file,
            kind = AnimatedSourceKind.WEBP,
            width = info.width,
            height = info.height,
            totalDurationMs = info.totalDurationMs,
            frameDurationsMs = info.frameDurationsMs,
            previewBitmap = preview,
            inspectedWebp = info,
        )
    }

    @Suppress("DEPRECATION")
    private fun probeGif(file: File): AnimatedSourceProbe {
        val meta = GifMetadataParser.parse(file)
            ?: error("No se pudo leer la estructura del GIF seleccionado.")
        require(meta.frameCount > 1) {
            "El GIF seleccionado solo tiene 1 frame y no es animado."
        }
        val movie = FileInputStream(file).use { input -> android.graphics.Movie.decodeStream(input) }
            ?: error("Android no pudo decodificar el archivo GIF seleccionado.")

        val srcWidth = max(1, meta.width)
        val srcHeight = max(1, meta.height)
        val rawPreview = Bitmap.createBitmap(srcWidth, srcHeight, Bitmap.Config.ARGB_8888)
        rawPreview.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(rawPreview)
        movie.setTime(0)
        movie.draw(canvas, 0f, 0f)
        val preview = scaleDownIfNeeded(rawPreview, MAX_PREVIEW_DIM)

        return AnimatedSourceProbe(
            sourceFile = file,
            kind = AnimatedSourceKind.GIF,
            width = srcWidth,
            height = srcHeight,
            totalDurationMs = meta.totalDurationMs,
            frameDurationsMs = meta.frameDurationsMs,
            previewBitmap = preview,
        )
    }

    private fun probeVideo(file: File): AnimatedSourceProbe {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            require(durationMs >= PackValidator.MIN_ANIMATED_FRAME_DURATION_MS * 2L) {
                "El vídeo es demasiado corto o no contiene pista de vídeo válida."
            }

            val rawPreview = extractScaledVideoFrame(retriever, 0L, MAX_PREVIEW_DIM)
                ?: error("No se pudo extraer ningún frame del vídeo seleccionado.")

            return AnimatedSourceProbe(
                sourceFile = file,
                kind = AnimatedSourceKind.VIDEO,
                width = rawPreview.width,
                height = rawPreview.height,
                totalDurationMs = durationMs,
                frameDurationsMs = emptyList(),
                previewBitmap = rawPreview,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun renderWebpFramesCallback(
        probe: AnimatedSourceProbe,
        transform: StickerFramingTransform,
        onFrame: (index: Int, total: Int, durationMs: Int, bitmap: Bitmap) -> Unit,
    ): List<Int> {
        val schedule = FrameSamplingPlanner.planFrameSampling(
            sourceDurationsMs = probe.frameDurationsMs,
            maxTotalDurationMs = PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS,
            targetMaxFps = MAX_INITIAL_ANIM_FPS,
        )
        require(schedule.size >= 2) { "El WebP animado no tiene suficientes frames válidos." }
        val wantedByIndex = schedule.associateBy { it.sourceIndex }
        val bytes = probe.sourceFile.readBytes()

        val durationsOut = mutableListOf<Int>()
        val sizePx = PackValidator.STICKER_DIMENSION_PX
        val decodedFrame = Bitmap.createBitmap(probe.width, probe.height, Bitmap.Config.ARGB_8888)
        val outputFrame = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputFrame)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = StaticStickerEncoder.buildRenderMatrix(
            srcWidth = probe.width,
            srcHeight = probe.height,
            canvasSizePx = sizePx.toFloat(),
            transform = transform,
        )

        try {
            NativeWebpCodec.openDecoder(bytes).use { decoder ->
                var srcIndex = 0
                while (true) {
                    val decodedDuration = decoder.decodeNextFrame(decodedFrame) ?: break
                    val sampled = wantedByIndex[srcIndex]
                    if (sampled != null) {
                        outputFrame.eraseColor(Color.TRANSPARENT)
                        canvas.drawBitmap(decodedFrame, matrix, paint)
                        val dur = sampled.outputDurationMs.coerceAtLeast(
                            decodedDuration.coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS),
                        )
                        onFrame(durationsOut.size, schedule.size, dur, outputFrame)
                        durationsOut += dur
                    }
                    srcIndex++
                    if (durationsOut.size >= schedule.size) break
                }
            }
        } finally {
            decodedFrame.recycle()
            outputFrame.recycle()
        }
        return durationsOut
    }

    @Suppress("DEPRECATION")
    private fun renderGifFramesCallback(
        probe: AnimatedSourceProbe,
        transform: StickerFramingTransform,
        onFrame: (index: Int, total: Int, durationMs: Int, bitmap: Bitmap) -> Unit,
    ): List<Int> {
        val schedule = FrameSamplingPlanner.planFrameSampling(
            sourceDurationsMs = probe.frameDurationsMs,
            maxTotalDurationMs = PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS,
            targetMaxFps = MAX_INITIAL_ANIM_FPS,
        )
        require(schedule.size >= 2) { "El GIF no tiene suficientes frames válidos." }

        val movie = FileInputStream(probe.sourceFile).use { android.graphics.Movie.decodeStream(it) }
            ?: error("No se pudo abrir el GIF para extraer los frames.")

        val startTimes = IntArray(probe.frameDurationsMs.size)
        var accTime = 0
        for (i in probe.frameDurationsMs.indices) {
            startTimes[i] = accTime
            accTime += probe.frameDurationsMs[i]
        }

        val sizePx = PackValidator.STICKER_DIMENSION_PX
        val srcFrame = Bitmap.createBitmap(probe.width, probe.height, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(srcFrame)
        val outputFrame = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(outputFrame)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = StaticStickerEncoder.buildRenderMatrix(
            srcWidth = probe.width,
            srcHeight = probe.height,
            canvasSizePx = sizePx.toFloat(),
            transform = transform,
        )

        val durationsOut = ArrayList<Int>(schedule.size)
        try {
            schedule.forEachIndexed { idx, sampled ->
                val frameTimeMs = startTimes.getOrElse(sampled.sourceIndex) { 0 }
                srcFrame.eraseColor(Color.TRANSPARENT)
                movie.setTime(frameTimeMs)
                movie.draw(srcCanvas, 0f, 0f)

                outputFrame.eraseColor(Color.TRANSPARENT)
                outCanvas.drawBitmap(srcFrame, matrix, paint)
                onFrame(idx, schedule.size, sampled.outputDurationMs, outputFrame)
                durationsOut += sampled.outputDurationMs
            }
        } finally {
            srcFrame.recycle()
            outputFrame.recycle()
        }
        return durationsOut
    }

    private fun streamVideoFrames(
        probe: AnimatedSourceProbe,
        transform: StickerFramingTransform,
        trimStartMs: Long,
        trimEndMs: Long,
        targetFps: Int,
        onFrameRgba: (index: Int, totalEstimated: Int, durationMs: Int, directRgbaBuffer: java.nio.ByteBuffer) -> Unit,
    ): List<Int> {
        val samplePoints = FrameSamplingPlanner.buildVideoSamplePoints(
            startMs = trimStartMs,
            endMs = trimEndMs,
            targetFps = targetFps,
        )
        require(samplePoints.size >= 2) {
            "El tramo seleccionado del vídeo es demasiado corto (mínimo 2 frames)."
        }

        val durationsOut = mutableListOf<Int>()
        val hardwareOk = runCatching {
            HardwareVideoFrameExtractor.extractFrames(
                videoFile = probe.sourceFile,
                samplePoints = samplePoints,
                transform = transform,
            ) { idx, durationMs, rgbaBuffer ->
                durationsOut += durationMs
                onFrameRgba(idx, samplePoints.size, durationMs, rgbaBuffer)
            }
        }.isSuccess

        if (hardwareOk && durationsOut.size >= 2) {
            return durationsOut
        }

        // Respaldo con MediaMetadataRetriever si un códec concreto falla en Surface GL
        durationsOut.clear()
        val sizePx = PackValidator.STICKER_DIMENSION_PX
        val outputFrame = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputFrame)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val directRgba = java.nio.ByteBuffer.allocateDirect(sizePx * sizePx * 4)
        val rowPixels = IntArray(sizePx)
        val outBytes = ByteArray(sizePx * sizePx * 4)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(probe.sourceFile.absolutePath)
            var cachedMatrix: android.graphics.Matrix? = null
            var matrixSrcW = -1
            var matrixSrcH = -1

            samplePoints.forEachIndexed { idx, point ->
                val decoded = extractScaledVideoFrame(
                    retriever = retriever,
                    timeUs = point.timestampMs * 1000L,
                    maxDim = MAX_VIDEO_FRAME_DIM,
                )
                if (decoded != null) {
                    try {
                        if (cachedMatrix == null || matrixSrcW != decoded.width || matrixSrcH != decoded.height) {
                            matrixSrcW = decoded.width
                            matrixSrcH = decoded.height
                            cachedMatrix = StaticStickerEncoder.buildRenderMatrix(
                                srcWidth = decoded.width,
                                srcHeight = decoded.height,
                                canvasSizePx = sizePx.toFloat(),
                                transform = transform,
                            )
                        }
                        outputFrame.eraseColor(Color.TRANSPARENT)
                        canvas.drawBitmap(decoded, cachedMatrix!!, paint)

                        var outIdx = 0
                        for (y in 0 until sizePx) {
                            outputFrame.getPixels(rowPixels, 0, sizePx, 0, y, sizePx, 1)
                            for (x in 0 until sizePx) {
                                val c = rowPixels[x]
                                outBytes[outIdx++] = ((c ushr 16) and 0xFF).toByte()
                                outBytes[outIdx++] = ((c ushr 8) and 0xFF).toByte()
                                outBytes[outIdx++] = (c and 0xFF).toByte()
                                outBytes[outIdx++] = ((c ushr 24) and 0xFF).toByte()
                            }
                        }
                        directRgba.clear()
                        directRgba.put(outBytes)
                        directRgba.rewind()
                        durationsOut += point.outputDurationMs
                        onFrameRgba(idx, samplePoints.size, point.outputDurationMs, directRgba)
                    } finally {
                        decoded.recycle()
                    }
                }
            }
        } finally {
            outputFrame.recycle()
            runCatching { retriever.release() }
        }
        require(durationsOut.size >= 2) {
            "No se pudieron extraer suficientes frames del vídeo seleccionado."
        }
        return durationsOut
    }

    private fun extractScaledVideoFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        maxDim: Int,
    ): Bitmap? {
        val videoW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val videoH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val effectiveW = if (rotation % 180 != 0) videoH else videoW
        val effectiveH = if (rotation % 180 != 0) videoW else videoH

        if (effectiveW > 0 && effectiveH > 0) {
            val scale = min(1f, maxDim.toFloat() / max(effectiveW, effectiveH).toFloat())
            val dstW = (effectiveW * scale).roundToInt().coerceAtLeast(1)
            val dstH = (effectiveH * scale).roundToInt().coerceAtLeast(1)
            val closest = retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                dstW,
                dstH,
            )
            if (closest != null) return ensureArgb8888(closest)
            val sync = retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                dstW,
                dstH,
            )
            if (sync != null) return ensureArgb8888(sync)
        }
        val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        return scaleDownIfNeeded(ensureArgb8888(raw), maxDim)
    }

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.recycle()
        return copy
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return ensureArgb8888(bitmap)
        val scale = maxDim.toFloat() / longest.toFloat()
        val dstW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val dstH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return ensureArgb8888(scaled)
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("No se pudo abrir el archivo seleccionado.")
        input.use { stream ->
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    require(total <= MAX_SOURCE_TEMP_BYTES) {
                        "El archivo seleccionado supera el límite permitido (${MAX_SOURCE_TEMP_BYTES / (1024 * 1024)} MB)."
                    }
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        }
    }

    private fun readHeaderBytes(file: File, count: Int): ByteArray {
        val buffer = ByteArray(count)
        val read = FileInputStream(file).use { it.read(buffer) }
        return if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }

    private fun isWebpHeader(bytes: ByteArray): Boolean {
        return bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
    }

    private fun isGifHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val header = String(bytes, 0, 6, Charsets.US_ASCII)
        return header == "GIF87a" || header == "GIF89a"
    }
}
