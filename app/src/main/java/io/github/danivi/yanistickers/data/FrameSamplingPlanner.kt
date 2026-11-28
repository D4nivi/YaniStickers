package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.PackValidator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

    const val MIN_SPEED_MULTIPLIER = 0.25f
    const val MAX_SPEED_MULTIPLIER = 2.0f
    const val DEFAULT_SPEED_MULTIPLIER = 1.0f

    fun normalizeSpeedMultiplier(speed: Float): Float {
        if (!speed.isFinite()) return DEFAULT_SPEED_MULTIPLIER
        val clamped = speed.coerceIn(MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER)
        val stepped = (clamped * 20f).roundToInt() / 20f
        return if (abs(stepped - DEFAULT_SPEED_MULTIPLIER) < 0.001f) DEFAULT_SPEED_MULTIPLIER else stepped
    }

    fun formatSpeedMultiplier(speed: Float): String {
        val safe = normalizeSpeedMultiplier(speed)
        val hundredths = (safe * 100f).roundToInt()
        return when {
            hundredths % 100 == 0 -> "${hundredths / 100}x"
            hundredths % 10 == 0 -> String.format(Locale.US, "%.1fx", safe)
            else -> String.format(Locale.US, "%.2fx", safe)
        }
    }

    fun maxSourceDurationForSpeed(speedMultiplier: Float): Long {
        val safeSpeed = speedMultiplier.coerceIn(MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER).toDouble()
        return (PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS * safeSpeed)
            .roundToLong()
            .coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS * 2L)
    }

    /**
     * Escala una lista de duraciones por frame según [speedMultiplier] acumulando en doble precisión
     * para evitar deriva temporal por redondeo entre frames.
     */
    fun scaleFrameDurations(
        sourceDurationsMs: List<Int>,
        speedMultiplier: Float = DEFAULT_SPEED_MULTIPLIER,
    ): List<Int> {
        if (sourceDurationsMs.isEmpty()) return emptyList()
        val safeSpeed = speedMultiplier.coerceIn(MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER).toDouble()
        if (abs(safeSpeed - 1.0) < 0.001) {
            return sourceDurationsMs.map { it.coerceAtLeast(1) }
        }
        var accSourceMs = 0.0
        var prevOutputMs = 0L
        return sourceDurationsMs.map { raw ->
            accSourceMs += raw.coerceAtLeast(1).toDouble()
            val nextOutputMs = (accSourceMs / safeSpeed).roundToLong()
            val delta = (nextOutputMs - prevOutputMs).toInt().coerceAtLeast(1)
            prevOutputMs = nextOutputMs
            delta
        }
    }

    /**
     * Calcula los instantes de extracción y duraciones para un tramo de vídeo `[startMs, endMs]`
     * aplicando [speedMultiplier] (`0.25x–2.0x`) y respetando el límite de 10 s de salida.
     */
    fun buildVideoSamplePoints(
        startMs: Long,
        endMs: Long,
        targetFps: Int,
        speedMultiplier: Float = DEFAULT_SPEED_MULTIPLIER,
    ): List<VideoSamplePoint> {
        val safeStart = startMs.coerceAtLeast(0L)
        val safeSpeed = speedMultiplier.coerceIn(MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER).toDouble()
        val maxSourceSpanMs = maxSourceDurationForSpeed(safeSpeed.toFloat())
        val effectiveSourceSpanMs = (endMs - safeStart).coerceIn(0L, maxSourceSpanMs)
        val outputDurationMs = (effectiveSourceSpanMs / safeSpeed)
            .roundToLong()
            .coerceAtMost(PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS)
        if (effectiveSourceSpanMs <= 0L || outputDurationMs < PackValidator.MIN_ANIMATED_FRAME_DURATION_MS * 2L) {
            return emptyList()
        }

        val safeFps = targetFps.coerceIn(1, 30)
        val nominalStepMs = (1000.0 / safeFps).roundToInt()
            .coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS)

        val outputTimestamps = mutableListOf<Long>()
        var outCursor = 0L
        while (outCursor < outputDurationMs) {
            if (outputDurationMs - outCursor < PackValidator.MIN_ANIMATED_FRAME_DURATION_MS &&
                outputTimestamps.isNotEmpty()
            ) {
                break
            }
            outputTimestamps += outCursor
            outCursor += nominalStepMs
        }

        if (outputTimestamps.size < 2) {
            val midOut = outputDurationMs / 2L
            val midSrc = safeStart + (midOut * safeSpeed).roundToLong()
                .coerceIn(0L, max(0L, effectiveSourceSpanMs - 1L))
            return listOf(
                VideoSamplePoint(
                    timestampMs = safeStart,
                    outputDurationMs = midOut.toInt().coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS),
                ),
                VideoSamplePoint(
                    timestampMs = midSrc,
                    outputDurationMs = (outputDurationMs - midOut).toInt()
                        .coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS),
                ),
            )
        }

        val points = ArrayList<VideoSamplePoint>(outputTimestamps.size)
        for (i in outputTimestamps.indices) {
            val currentOut = outputTimestamps[i]
            val nextOut = if (i < outputTimestamps.lastIndex) outputTimestamps[i + 1] else outputDurationMs
            val duration = (nextOut - currentOut).toInt()
                .coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS)
            val srcTimestamp = safeStart + (currentOut * safeSpeed).roundToLong()
                .coerceIn(0L, max(0L, effectiveSourceSpanMs - 1L))
            points += VideoSamplePoint(timestampMs = srcTimestamp, outputDurationMs = duration)
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
            val rawDuration = sourceDurationsMs[index].coerceAtLeast(1)
            if (clipped.size >= 2 && elapsed >= safeMaxDuration) {
                break
            }
            val remainingBudget = safeMaxDuration - elapsed
            if (clipped.size >= 2 && remainingBudget < 1L) {
                break
            }
            val duration = if (clipped.size >= 2) {
                min(rawDuration.toLong(), remainingBudget).toInt().coerceAtLeast(1)
            } else {
                rawDuration
            }
            clipped += SampledSourceFrame(sourceIndex = index, outputDurationMs = duration)
            elapsed += duration
        }

        val minFrameIntervalMs = max(
            PackValidator.MIN_ANIMATED_FRAME_DURATION_MS,
            targetMaxFps?.let { (1000.0 / it.coerceIn(1, 30)).roundToInt() } ?: 0,
        )

        // 2. Fusionar frames consecutivos por debajo del intervalo mínimo (incluidos < 8 ms por speedup)
        val merged = mutableListOf<SampledSourceFrame>()
        var current = clipped.first()
        for (i in 1 until clipped.size) {
            val next = clipped[i]
            val remainingFramesAfter = clipped.size - 1 - i
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

        // Si el último frame residual quedó por debajo de 8 ms, fusionarlo con el penúltimo cuando haya > 2
        if (merged.size > 2 && merged.last().outputDurationMs < PackValidator.MIN_ANIMATED_FRAME_DURATION_MS) {
            val last = merged.removeAt(merged.lastIndex)
            val prev = merged.removeAt(merged.lastIndex)
            merged += prev.copy(outputDurationMs = prev.outputDurationMs + last.outputDurationMs)
        }

        val normalized = merged.map { item ->
            item.copy(
                outputDurationMs = item.outputDurationMs.coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS),
            )
        }
        val clamped = clampTotalDuration(normalized) { item, newDuration ->
            item.copy(outputDurationMs = newDuration)
        }
        return if (clamped.size >= 2) clamped else clipped.take(2).map {
            it.copy(outputDurationMs = it.outputDurationMs.coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS))
        }
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
