package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.PackValidator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Representa un frame seleccionado de la secuencia original y la duración que debe tener en la
 * salida animada final.
 */
data class SampledSourceFrame(
    val sourceIndex: Int,
    val outputDurationMs: Int,
)

/**
 * Instante de muestreo y duración para extraer frames de un vídeo.
 */
data class VideoSamplePoint(
    val timestampMs: Long,
    val outputDurationMs: Int,
)

/**
 * Planifica el muestreo temporal y la reducción de FPS conservando la velocidad de reproducción y
 * respetando los límites de WhatsApp (`>= 8 ms`/frame y `<= 10.000 ms` totales).
 */
object FrameSamplingPlanner {

    /**
     * Calcula los instantes de extracción y duraciones para un tramo de vídeo `[startMs, endMs]`.
     */
    fun buildVideoSamplePoints(
        startMs: Long,
        endMs: Long,
        targetFps: Int,
    ): List<VideoSamplePoint> {
        val safeStart = startMs.coerceAtLeast(0L)
        val clampedDuration = (endMs - safeStart)
            .coerceAtMost(PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS)
        if (clampedDuration < PackValidator.MIN_ANIMATED_FRAME_DURATION_MS * 2L) {
            return emptyList()
        }

        val safeFps = targetFps.coerceIn(1, 30)
        val nominalStepMs = (1000.0 / safeFps).roundToInt()
            .coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS)

        val timestamps = mutableListOf<Long>()
        var cursor = safeStart
        val safeEnd = safeStart + clampedDuration
        while (cursor < safeEnd) {
            if (safeEnd - cursor < PackValidator.MIN_ANIMATED_FRAME_DURATION_MS && timestamps.isNotEmpty()) {
                break
            }
            timestamps += cursor
            cursor += nominalStepMs
        }

        if (timestamps.size < 2) {
            val mid = safeStart + (clampedDuration / 2L)
            return listOf(
                VideoSamplePoint(safeStart, (mid - safeStart).toInt()),
                VideoSamplePoint(mid, (safeEnd - mid).toInt()),
            )
        }

        val points = ArrayList<VideoSamplePoint>(timestamps.size)
        for (i in timestamps.indices) {
            val currentTs = timestamps[i]
            val nextTs = if (i < timestamps.lastIndex) timestamps[i + 1] else safeEnd
            val duration = (nextTs - currentTs).toInt()
                .coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS)
            points += VideoSamplePoint(timestampMs = currentTs, outputDurationMs = duration)
        }
        return clampTotalDuration(points) { point, newDuration ->
            point.copy(outputDurationMs = newDuration)
        }
    }

    /**
     * Submuestrea una secuencia de frames con sus duraciones [sourceDurationsMs] para ajustarse a
     * [maxTotalDurationMs] y, opcionalmente, reducir la tasa máxima de FPS manteniendo la duración
     * total real al sumar los tiempos de los frames descartados.
     */
    fun planFrameSampling(
        sourceDurationsMs: List<Int>,
        maxTotalDurationMs: Long = PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS,
        targetMaxFps: Int? = null,
    ): List<SampledSourceFrame> {
        if (sourceDurationsMs.size < 2) return emptyList()

        val safeMaxDuration = maxTotalDurationMs.coerceAtLeast(
            PackValidator.MIN_ANIMATED_FRAME_DURATION_MS * 2L,
        )

        // 1. Recortar frames que excedan maxTotalDurationMs conservando al menos 2 frames
        val clipped = mutableListOf<SampledSourceFrame>()
        var elapsed = 0L
        for (index in sourceDurationsMs.indices) {
            val rawDuration = sourceDurationsMs[index]
                .coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS)
            if (clipped.size >= 2 && elapsed >= safeMaxDuration) {
                break
            }
            val remainingBudget = safeMaxDuration - elapsed
            if (clipped.size >= 2 && remainingBudget < PackValidator.MIN_ANIMATED_FRAME_DURATION_MS) {
                break
            }
            val duration = if (clipped.size >= 2) {
                min(rawDuration.toLong(), remainingBudget).toInt()
                    .coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS)
            } else {
                rawDuration
            }
            clipped += SampledSourceFrame(sourceIndex = index, outputDurationMs = duration)
            elapsed += duration
        }

        val normalizedClipped = clampTotalDuration(clipped) { item, newDuration ->
            item.copy(outputDurationMs = newDuration)
        }
        if (targetMaxFps == null || normalizedClipped.size <= 2) {
            return normalizedClipped
        }

        val minFrameIntervalMs = (1000.0 / targetMaxFps.coerceIn(1, 30)).roundToInt()
            .coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS)

        // 2. Fusionar frames consecutivos demasiado cortos respecto a minFrameIntervalMs
        val merged = mutableListOf<SampledSourceFrame>()
        var current = normalizedClipped.first()
        for (i in 1 until normalizedClipped.size) {
            val next = normalizedClipped[i]
            val remainingFramesAfter = normalizedClipped.size - 1 - i
            val mustKeepForMinimumCount = merged.isEmpty() && remainingFramesAfter == 0
            if (!mustKeepForMinimumCount && current.outputDurationMs < minFrameIntervalMs) {
                current = current.copy(
                    outputDurationMs = current.outputDurationMs + next.outputDurationMs,
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current

        return if (merged.size >= 2) merged else normalizedClipped.take(2)
    }

    private inline fun <T> clampTotalDuration(
        items: List<T>,
        crossinline copyWithDuration: (T, Int) -> T,
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        val result = items.toMutableList()
        var total = 0L
        for (item in result) {
            total += when (item) {
                is SampledSourceFrame -> item.outputDurationMs
                is VideoSamplePoint -> item.outputDurationMs
                else -> 0
            }
        }
        if (total <= PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS) return result

        var excess = total - PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS
        for (i in result.indices.reversed()) {
            if (excess <= 0L) break
            val cur = when (val item = result[i]) {
                is SampledSourceFrame -> item.outputDurationMs
                is VideoSamplePoint -> item.outputDurationMs
                else -> 0
            }
            val reducible = max(0, cur - PackValidator.MIN_ANIMATED_FRAME_DURATION_MS)
            if (reducible > 0) {
                val delta = min(excess, reducible.toLong()).toInt()
                result[i] = copyWithDuration(result[i], cur - delta)
                excess -= delta
            }
        }
        return result
    }
}
