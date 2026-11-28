package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.ImageContainerInspector
import io.github.danivi.yanistickers.validation.PackValidator
import io.github.danivi.yanistickers.validation.TestImageBuilders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedStickerEncoderTest {

    @Test
    fun adaptiveEstimation_picksAppropriateFpsAndQualitySteps() {
        assertEquals(20, AnimatedStickerEncoder.chooseInitialVideoFps(400L))
        assertEquals(15, AnimatedStickerEncoder.chooseInitialVideoFps(2_000L))
        assertEquals(12, AnimatedStickerEncoder.chooseInitialVideoFps(5_000L))
        assertEquals(10, AnimatedStickerEncoder.chooseInitialVideoFps(9_000L))

        assertEquals(1, AnimatedStickerEncoder.computeShortLoopMultiplier(600L))
        assertEquals(1, AnimatedStickerEncoder.computeShortLoopMultiplier(500L))
        assertEquals(2, AnimatedStickerEncoder.computeShortLoopMultiplier(400L))
        assertEquals(2, AnimatedStickerEncoder.computeShortLoopMultiplier(250L))
        assertEquals(3, AnimatedStickerEncoder.computeShortLoopMultiplier(200L))
        assertEquals(5, AnimatedStickerEncoder.computeShortLoopMultiplier(100L))

        assertTrue(AnimatedStickerEncoder.estimateInitialQuality(15) > AnimatedStickerEncoder.estimateInitialQuality(90))
        val reduced = AnimatedStickerEncoder.estimateNextQuality(60f, 900 * 1024L)
        assertTrue(reduced in 20f..45f)
    }

    @Test
    fun isDirectValidAnimatedWebp_acceptsCompliantAnimatedWebpAndRejectsInvalidOnes() {
        val validBytes = TestImageBuilders.animatedWebp(512, 512, listOf(80, 80, 80))
        val validInfo = ImageContainerInspector.inspectBytes(validBytes)
        assertTrue(AnimatedSourceProbe.isDirectValidAnimatedWebp(AnimatedSourceKind.WEBP, validInfo))
        assertFalse(AnimatedSourceProbe.isDirectValidAnimatedWebp(AnimatedSourceKind.GIF, validInfo))

        // Dimensiones incorrectas (400x400) -> debe requerir recodificación
        val wrongSizeBytes = TestImageBuilders.animatedWebp(400, 400, listOf(80, 80))
        assertFalse(
            AnimatedSourceProbe.isDirectValidAnimatedWebp(
                AnimatedSourceKind.WEBP,
                ImageContainerInspector.inspectBytes(wrongSizeBytes),
            ),
        )

        // Exceso de peso (> 500 KB) -> debe requerir recodificación
        val oversizedInfo = validInfo!!.copy(
            fileSizeBytes = PackValidator.MAX_ANIMATED_STICKER_BYTES + 1L,
        )
        assertFalse(AnimatedSourceProbe.isDirectValidAnimatedWebp(AnimatedSourceKind.WEBP, oversizedInfo))

        // Frame demasiado corto (< 8 ms) -> debe requerir recodificación
        val fastFrameBytes = TestImageBuilders.animatedWebp(512, 512, listOf(5, 80))
        assertFalse(
            AnimatedSourceProbe.isDirectValidAnimatedWebp(
                AnimatedSourceKind.WEBP,
                ImageContainerInspector.inspectBytes(fastFrameBytes),
            ),
        )

        // Duración total > 10 s -> debe requerir recodificación
        val tooLongBytes = TestImageBuilders.animatedWebp(512, 512, listOf(5_000, 5_500))
        assertFalse(
            AnimatedSourceProbe.isDirectValidAnimatedWebp(
                AnimatedSourceKind.WEBP,
                ImageContainerInspector.inspectBytes(tooLongBytes),
            ),
        )
    }

    @Test
    fun retimeAnimatedWebpIfPossible_updatesAnmfDurationsWithoutReencodingWhenWithinLimits() {
        // 10 frames de 100 ms = 1.000 ms totales
        val original = TestImageBuilders.animatedWebp(512, 512, List(10) { 100 })

        // 2.0x -> 10 frames de 50 ms = 500 ms totales (válido sin recomprimir)
        val faster = AnimatedStickerEncoder.retimeAnimatedWebpIfPossible(original, speedMultiplier = 2.0f)
        assertNotNull(faster)
        val fasterInfo = ImageContainerInspector.inspectBytes(faster!!)!!
        assertEquals(10, fasterInfo.frameCount)
        assertEquals(500L, fasterInfo.totalDurationMs)
        assertTrue(fasterInfo.frameDurationsMs.all { it == 50 })

        // 0.25x -> 10 frames de 400 ms = 4.000 ms totales (válido sin recomprimir)
        val slower = AnimatedStickerEncoder.retimeAnimatedWebpIfPossible(original, speedMultiplier = 0.25f)
        assertNotNull(slower)
        val slowerInfo = ImageContainerInspector.inspectBytes(slower!!)!!
        assertEquals(10, slowerInfo.frameCount)
        assertEquals(4_000L, slowerInfo.totalDurationMs)
        assertTrue(slowerInfo.frameDurationsMs.all { it == 400 })
    }

    @Test
    fun retimeAnimatedWebpIfPossible_returnsNullWhenReencodingIsRequired() {
        // Si al acelerar algún frame baja de 8 ms -> debe devolver null para fusionar frames
        val fastFrames = TestImageBuilders.animatedWebp(512, 512, List(60) { 12 })
        assertNull(AnimatedStickerEncoder.retimeAnimatedWebpIfPossible(fastFrames, speedMultiplier = 2.0f))

        // Si al ralentizar supera los 10 s -> debe devolver null para recortar
        val longClip = TestImageBuilders.animatedWebp(512, 512, List(40) { 100 }) // 4 s * 4 (0.25x) = 16 s
        assertNull(AnimatedStickerEncoder.retimeAnimatedWebpIfPossible(longClip, speedMultiplier = 0.25f))

        // Si al acelerar baja de 500 ms totales -> debe devolver null para poder repetir bucle si aplica
        val shortClip = TestImageBuilders.animatedWebp(512, 512, List(10) { 60 }) // 600 ms / 2 = 300 ms
        assertNull(AnimatedStickerEncoder.retimeAnimatedWebpIfPossible(shortClip, speedMultiplier = 2.0f))
    }
}
