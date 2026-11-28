package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.ImageContainerFormat
import io.github.danivi.yanistickers.validation.ImageContainerInspector
import io.github.danivi.yanistickers.validation.PackValidator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Resultado de generar o importar un sticker animado validado frente a los límites de WhatsApp.
 */
data class EncodedAnimatedWebpResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val totalDurationMs: Long,
    val quality: Float,
    val targetFps: Int?,
    val recompressed: Boolean,
    val durationTrimmed: Boolean,
) {
    val sizeBytes: Long
        get() = bytes.size.toLong()
}

/**
 * Escritor secuencial de buffers RGBA `512×512` en caché temporal para reintentos sin redecodificar.
 */
internal class RawRgbaSequenceWriter(
    file: File,
    sizePx: Int,
) : Closeable {
    private val frameBytes = sizePx * sizePx * 4
    private val scratch = ByteArray(frameBytes)
    private val out = FileOutputStream(file)

    fun writeBuffer(directRgbaBuffer: ByteBuffer) {
        directRgbaBuffer.rewind()
        directRgbaBuffer.get(scratch, 0, frameBytes)
        directRgbaBuffer.rewind()
        out.write(scratch, 0, frameBytes)
    }

    override fun close() {
        out.flush()
        out.close()
    }
}

/**
 * Lector aleatorio de buffers RGBA directos `512×512` previamente extraídos a disco.
 */
internal class RawRgbaSequenceReader(
    file: File,
    sizePx: Int,
) : Closeable {
    private val frameBytes = sizePx * sizePx * 4
    private val raf = RandomAccessFile(file, "r")
    private val scratch = ByteArray(frameBytes)

    fun readFrameInto(index: Int, targetDirectBuffer: ByteBuffer) {
        raf.seek(index.toLong() * frameBytes.toLong())
        raf.readFully(scratch)
        targetDirectBuffer.clear()
        targetDirectBuffer.put(scratch, 0, frameBytes)
        targetDirectBuffer.rewind()
    }

    override fun close() {
        raf.close()
    }
}

/**
 * Codifica stickers animados a WebP (`512×512`, `≤ 500 KB`, `≤ 10 s`) mediante `libwebp` nativo en
 * modo rápido, estimando calidad inicial y ajustando en 1–2 pasadas con progreso estrictamente monótono.
 */
object AnimatedStickerEncoder {

    private const val WEBP_METHOD = 1
    private const val TARGET_SAFE_BYTES = 465 * 1024L
    internal const val MIN_RECOMMENDED_ANIMATION_MS = 500L
    private const val MAX_AUTO_LOOPS = 5

    /**
     * Calcula cuántas repeticiones debe hacer una animación muy corta (`< 500 ms`) para alcanzar
     * una duración mínima cómoda (`≥ 500 ms`) sin superar el límite de 10 s.
     */
    internal fun computeShortLoopMultiplier(totalDurationMs: Long): Int {
        if (totalDurationMs <= 0L || totalDurationMs >= MIN_RECOMMENDED_ANIMATION_MS) return 1
        val needed = kotlin.math.ceil(MIN_RECOMMENDED_ANIMATION_MS.toDouble() / totalDurationMs.toDouble()).toInt()
        val maxSafeLoops = (PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS / totalDurationMs).toInt().coerceAtLeast(1)
        return needed.coerceIn(2, minOf(MAX_AUTO_LOOPS, maxSafeLoops))
    }

    /**
     * Calcula la tasa de FPS inicial según la duración del vídeo para equilibrar fluidez y peso.
     */
    internal fun chooseInitialVideoFps(durationMs: Long): Int = when {
        durationMs <= 1_000L -> 20
        durationMs <= 3_500L -> 15
        durationMs <= 6_500L -> 12
        else -> 10
    }

    /**
     * Estima la calidad inicial más probable para caber en 500 KB en la primera pasada.
     */
    internal fun estimateInitialQuality(frameCount: Int): Float = when {
        frameCount <= 20 -> 72f
        frameCount <= 45 -> 58f
        frameCount <= 75 -> 44f
        frameCount <= 105 -> 34f
        else -> 26f
    }

    /**
     * Calcula la siguiente calidad estimada a partir del exceso real de tamaño respecto al límite.
     */
    internal fun estimateNextQuality(currentQuality: Float, actualSizeBytes: Long): Float {
        if (actualSizeBytes <= 0L) return (currentQuality - 14f).coerceAtLeast(6f)
        val ratio = TARGET_SAFE_BYTES.toFloat() / actualSizeBytes.toFloat()
        val scaled = currentQuality * ratio * 0.92f
        return scaled.coerceIn(6f, (currentQuality - 10f).coerceAtLeast(6f))
    }

    suspend fun encodeFromProbe(
        probe: AnimatedSourceProbe,
        transform: StickerFramingTransform,
        trimStartMs: Long,
        trimEndMs: Long,
        sessionDir: File,
        allowDirectPassthrough: Boolean = true,
        onProgress: (progress: Float, status: String) -> Unit = { _, _ -> },
    ): EncodedAnimatedWebpResult {
        currentCoroutineContext().ensureActive()

        // Passthrough instantáneo para WebP animado ya válido (≥ 500 ms) sin cambios de encuadre
        if (allowDirectPassthrough &&
            probe.isDirectValidWebp &&
            probe.totalDurationMs >= MIN_RECOMMENDED_ANIMATION_MS &&
            transform.normalized() == StickerFramingTransform(mode = FramingMode.FIT)
        ) {
            onProgress(0.85f, "Verificando WebP animado original...")
            val bytes = probe.sourceFile.readBytes()
            val verified = verifyAnimatedWebp(bytes)
            onProgress(1f, "Listo")
            return EncodedAnimatedWebpResult(
                bytes = bytes,
                width = verified.width,
                height = verified.height,
                frameCount = verified.frameCount,
                totalDurationMs = verified.totalDurationMs,
                quality = 100f,
                targetFps = null,
                recompressed = false,
                durationTrimmed = false,
            )
        }

        val sizePx = PackValidator.STICKER_DIMENSION_PX
        val rawFramesFile = File(sessionDir, "frames_${System.nanoTime()}.rgba")
        val selectedDurationMs = (trimEndMs - trimStartMs).coerceIn(250L, PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS)
        val initialFps = chooseInitialVideoFps(selectedDurationMs)
        val estimatedCount = if (probe.kind == AnimatedSourceKind.VIDEO) {
            ((selectedDurationMs / 1000.0) * initialFps).toInt().coerceAtLeast(2)
        } else {
            probe.frameDurationsMs.size.coerceAtLeast(2)
        }
        val initialQuality = estimateInitialQuality(estimatedCount)

        try {
            var lastProgress = 0.02f
            fun emitProgress(p: Float, msg: String) {
                val monotonic = p.coerceIn(lastProgress, 0.99f)
                lastProgress = monotonic
                onProgress(monotonic, msg)
            }

            emitProgress(0.02f, "Convirtiendo y guardando...")

            // Pasada 1: decodificar y codificar en paralelo (sin esperar a una segunda lectura si ya cabe)
            var pass1Bytes: ByteArray? = null
            val baseDurations: List<Int>
            var loopMultiplier = 1
            NativeWebpCodec.openEncoder(
                width = sizePx,
                height = sizePx,
                loopCount = 0,
                minimizeSize = false,
            ).use { encoder ->
                RawRgbaSequenceWriter(rawFramesFile, sizePx).use { writer ->
                    baseDurations = AnimatedFrameExtractor.streamSourceFrames(
                        probe = probe,
                        transform = transform,
                        trimStartMs = trimStartMs,
                        trimEndMs = trimEndMs,
                        videoFps = initialFps,
                    ) { idx, totalEst, durationMs, directRgbaBuffer ->
                        writer.writeBuffer(directRgbaBuffer)
                        encoder.addRgbaBufferFrame(
                            directRgbaBuffer = directRgbaBuffer,
                            durationMs = durationMs,
                            quality = initialQuality,
                            lossless = false,
                            method = WEBP_METHOD,
                        )
                        val frac = ((idx + 1).toFloat() / totalEst.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        emitProgress(0.05f + frac * 0.60f, "Codificando frames (${idx + 1}/$totalEst)...")
                    }
                }
                val singleLoopDurationMs = baseDurations.fold(0L) { acc, d -> acc + d.toLong() }
                loopMultiplier = computeShortLoopMultiplier(singleLoopDurationMs)
                if (loopMultiplier > 1) {
                    emitProgress(0.66f, "Repitiendo clip corto (${loopMultiplier}x)...")
                    val loopBuffer = ByteBuffer.allocateDirect(sizePx * sizePx * 4)
                    RawRgbaSequenceReader(rawFramesFile, sizePx).use { reader ->
                        repeat(loopMultiplier - 1) {
                            for (i in baseDurations.indices) {
                                currentCoroutineContext().ensureActive()
                                reader.readFrameInto(i, loopBuffer)
                                encoder.addRgbaBufferFrame(
                                    directRgbaBuffer = loopBuffer,
                                    durationMs = baseDurations[i],
                                    quality = initialQuality,
                                    lossless = false,
                                    method = WEBP_METHOD,
                                )
                            }
                        }
                    }
                }
                emitProgress(0.68f, "Ensamblando WebP animado...")
                pass1Bytes = encoder.finish()
            }

            currentCoroutineContext().ensureActive()
            val firstResultBytes = pass1Bytes!!
            if (firstResultBytes.size.toLong() <= PackValidator.MAX_ANIMATED_STICKER_BYTES) {
                emitProgress(0.95f, "Verificando sticker...")
                val verified = verifyAnimatedWebp(firstResultBytes)
                onProgress(1f, "Listo")
                return EncodedAnimatedWebpResult(
                    bytes = firstResultBytes,
                    width = verified.width,
                    height = verified.height,
                    frameCount = verified.frameCount,
                    totalDurationMs = verified.totalDurationMs,
                    quality = initialQuality,
                    targetFps = if (probe.kind == AnimatedSourceKind.VIDEO) initialFps else null,
                    recompressed = true,
                    durationTrimmed = false,
                )
            }

            // Pasada 2+: ajuste adaptativo desde la caché ya extraída (0 decodificación de vídeo)
            return fitFromCachedFrames(
                baseDurations = baseDurations,
                rawFramesFile = rawFramesFile,
                initialQuality = initialQuality,
                initialSizeBytes = firstResultBytes.size.toLong(),
                startFps = initialFps,
                loopMultiplier = loopMultiplier,
                emitProgress = ::emitProgress,
            ).also {
                onProgress(1f, "Listo")
            }
        } finally {
            rawFramesFile.delete()
        }
    }

    private suspend fun fitFromCachedFrames(
        baseDurations: List<Int>,
        rawFramesFile: File,
        initialQuality: Float,
        initialSizeBytes: Long,
        startFps: Int,
        loopMultiplier: Int,
        emitProgress: (Float, String) -> Unit,
    ): EncodedAnimatedWebpResult {
        val sizePx = PackValidator.STICKER_DIMENSION_PX
        val directBuffer = ByteBuffer.allocateDirect(sizePx * sizePx * 4)
        val maxSingleLoopMs = PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS / loopMultiplier.coerceAtLeast(1)
        val baseTotalMs = baseDurations.fold(0L) { acc, d -> acc + d.toLong() }
            .coerceAtMost(maxSingleLoopMs)

        val fpsCandidates = buildList {
            add(null)
            if (startFps > 10) add(10)
            add(8)
            add(6)
        }
        val durationCaps = buildList {
            add(baseTotalMs)
            listOf(7_000L, 5_000L, 3_500L).forEach { cap ->
                if (cap < baseTotalMs) add(cap)
            }
        }

        var currentProgress = 0.70f
        RawRgbaSequenceReader(rawFramesFile, sizePx).use { reader ->
            for ((capIdx, maxDurMs) in durationCaps.withIndex()) {
                val trimmed = capIdx > 0
                for ((fpsIdx, targetFps) in fpsCandidates.withIndex()) {
                    val schedule = FrameSamplingPlanner.planFrameSampling(
                        sourceDurationsMs = baseDurations,
                        maxTotalDurationMs = maxDurMs,
                        targetMaxFps = targetFps,
                    )
                    if (schedule.size < 2) continue

                    var quality = if (capIdx == 0 && fpsIdx == 0) {
                        estimateNextQuality(initialQuality, initialSizeBytes)
                    } else {
                        26f
                    }

                    repeat(3) { attempt ->
                        currentCoroutineContext().ensureActive()
                        emitProgress(
                            currentProgress,
                            "Ajustando tamaño (calidad ${quality.toInt()})...",
                        )
                        val bytes = encodeSchedule(schedule, quality, loopMultiplier, reader, directBuffer)
                        currentProgress = (currentProgress + 0.04f).coerceAtMost(0.95f)

                        if (bytes.size.toLong() <= PackValidator.MAX_ANIMATED_STICKER_BYTES) {
                            emitProgress(0.96f, "Verificando sticker...")
                            val verified = verifyAnimatedWebp(bytes)
                            return EncodedAnimatedWebpResult(
                                bytes = bytes,
                                width = verified.width,
                                height = verified.height,
                                frameCount = verified.frameCount,
                                totalDurationMs = verified.totalDurationMs,
                                quality = quality,
                                targetFps = targetFps,
                                recompressed = true,
                                durationTrimmed = trimmed,
                            )
                        }
                        if (quality <= 7f || attempt == 2) return@repeat
                        quality = estimateNextQuality(quality, bytes.size.toLong())
                    }
                }
            }
        }

        error("No se pudo comprimir la animación por debajo de ${PackValidator.MAX_ANIMATED_STICKER_KB} KB. Prueba un tramo más corto.")
    }

    private fun encodeSchedule(
        schedule: List<SampledSourceFrame>,
        quality: Float,
        loopMultiplier: Int,
        reader: RawRgbaSequenceReader,
        directBuffer: ByteBuffer,
    ): ByteArray {
        val sizePx = PackValidator.STICKER_DIMENSION_PX
        NativeWebpCodec.openEncoder(
            width = sizePx,
            height = sizePx,
            loopCount = 0,
            minimizeSize = false,
        ).use { encoder ->
            repeat(loopMultiplier.coerceAtLeast(1)) {
                for (item in schedule) {
                    reader.readFrameInto(item.sourceIndex, directBuffer)
                    encoder.addRgbaBufferFrame(
                        directRgbaBuffer = directBuffer,
                        durationMs = item.outputDurationMs,
                        quality = quality,
                        lossless = false,
                        method = WEBP_METHOD,
                    )
                }
            }
            return encoder.finish()
        }
    }

    private fun verifyAnimatedWebp(bytes: ByteArray) =
        (ImageContainerInspector.inspectBytes(bytes)
            ?: error("No se pudo verificar el archivo WebP animado generado.")).also { info ->
            check(info.format == ImageContainerFormat.WEBP && info.isAnimated && info.frameCount > 1) {
                "El archivo seleccionado no tiene variación entre frames (solo 1 frame único). Elige un vídeo o GIF con movimiento."
            }
            check(info.width == PackValidator.STICKER_DIMENSION_PX && info.height == PackValidator.STICKER_DIMENSION_PX) {
                "Dimensiones inesperadas del WebP animado (${info.width}×${info.height})."
            }
            check(info.fileSizeBytes <= PackValidator.MAX_ANIMATED_STICKER_BYTES) {
                "El WebP animado supera ${PackValidator.MAX_ANIMATED_STICKER_KB} KB."
            }
            check(info.totalDurationMs <= PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS) {
                "La duración del WebP animado supera los 10 s."
            }
            val minDuration = info.minFrameDurationMs
            check(minDuration != null && minDuration >= PackValidator.MIN_ANIMATED_FRAME_DURATION_MS) {
                "Un frame dura menos de ${PackValidator.MIN_ANIMATED_FRAME_DURATION_MS} ms."
            }
        }
}
