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

    @Test
    fun buildVideoSamplePoints_supportsSpeedUpAndSlowDownWithinWhatsAppLimits() {
        // A 2.0x, un tramo de 20 s cabe íntegramente en 10 s de salida
        val fastPoints = FrameSamplingPlanner.buildVideoSamplePoints(
            startMs = 0L,
            endMs = 20_000L,
            targetFps = 10,
            speedMultiplier = 2.0f,
        )
        assertTrue(fastPoints.size >= 2)
        assertEquals(PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS, fastPoints.sumOf { it.outputDurationMs.toLong() })
        assertTrue(fastPoints.last().timestampMs > 15_000L)
        assertTrue(fastPoints.all { it.outputDurationMs >= PackValidator.MIN_ANIMATED_FRAME_DURATION_MS })

        // A 0.25x, un tramo de 1 s se estira a 4 s de salida
        val slowPoints = FrameSamplingPlanner.buildVideoSamplePoints(
            startMs = 1_000L,
            endMs = 2_000L,
            targetFps = 15,
            speedMultiplier = 0.25f,
        )
        assertTrue(slowPoints.size >= 2)
        assertEquals(4_000L, slowPoints.sumOf { it.outputDurationMs.toLong() })
        assertEquals(1_000L, slowPoints.first().timestampMs)
        assertTrue(slowPoints.last().timestampMs < 2_000L)
    }

    @Test
    fun scaleFrameDurations_preservesExactDurationAndMergesSub8msFramesOnSpeedUp() {
        // 100 frames de 10 ms = 1.000 ms; a 2.0x son 500 ms totales y deben fusionarse para >= 8 ms/frame
        val source = List(100) { 10 }
        val scaled = FrameSamplingPlanner.scaleFrameDurations(source, speedMultiplier = 2.0f)
        assertEquals(500, scaled.sum())

        val planned = FrameSamplingPlanner.planFrameSampling(
            sourceDurationsMs = scaled,
            maxTotalDurationMs = PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS,
            targetMaxFps = null,
        )
        assertEquals(500, planned.sumOf { it.outputDurationMs })
        assertTrue(planned.all { it.outputDurationMs >= PackValidator.MIN_ANIMATED_FRAME_DURATION_MS })

        // A 0.25x, 10 frames de 50 ms (500 ms) pasan a 2.000 ms
        val slowed = FrameSamplingPlanner.scaleFrameDurations(List(10) { 50 }, speedMultiplier = 0.25f)
        assertEquals(2_000, slowed.sum())
    }
}
