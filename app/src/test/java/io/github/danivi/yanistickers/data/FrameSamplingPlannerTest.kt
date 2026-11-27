package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.PackValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSamplingPlannerTest {

    @Test
    fun buildVideoSamplePoints_respectsMaxDurationAndMinFrameDelay() {
        val points = FrameSamplingPlanner.buildVideoSamplePoints(
            startMs = 2_000L,
            endMs = 25_000L, // Excede el máximo de 10 s
            targetFps = 15,
        )

        assertTrue(points.size >= 2)
        assertEquals(2_000L, points.first().timestampMs)
        val totalDuration = points.sumOf { it.outputDurationMs.toLong() }
        assertTrue(totalDuration <= PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS)
        assertTrue(points.all { it.outputDurationMs >= PackValidator.MIN_ANIMATED_FRAME_DURATION_MS })
    }

    @Test
    fun planFrameSampling_preservesPlaybackDurationWhenReducingFps() {
        // 30 frames a ~30 fps (33 ms cada uno) -> 990 ms totales
        val thirtyFps = List(30) { 33 }
        val reduced = FrameSamplingPlanner.planFrameSampling(
            sourceDurationsMs = thirtyFps,
            maxTotalDurationMs = PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS,
            targetMaxFps = 10, // intervalo objetivo ~100 ms
        )

        assertTrue(reduced.size < thirtyFps.size)
        assertTrue(reduced.size in 8..12)
        assertEquals(thirtyFps.sum(), reduced.sumOf { it.outputDurationMs })
        assertTrue(reduced.all { it.outputDurationMs >= PackValidator.MIN_ANIMATED_FRAME_DURATION_MS })
    }

    @Test
    fun planFrameSampling_clipsOver10SecondsAndNormalizesShortFrames() {
        // 150 frames de 100 ms = 15 s (debe recortar <= 10 s), con algunos frames < 8 ms
        val source = listOf(2, 4, 100) + List(150) { 100 }
        val planned = FrameSamplingPlanner.planFrameSampling(
            sourceDurationsMs = source,
            maxTotalDurationMs = PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS,
            targetMaxFps = null,
        )

        val total = planned.sumOf { it.outputDurationMs.toLong() }
        assertTrue(total <= PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS)
        assertTrue(planned.all { it.outputDurationMs >= PackValidator.MIN_ANIMATED_FRAME_DURATION_MS })
    }
}
