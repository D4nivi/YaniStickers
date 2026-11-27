package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.ImageContainerInspector
import io.github.danivi.yanistickers.validation.PackValidator
import io.github.danivi.yanistickers.validation.TestImageBuilders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedStickerEncoderTest {

    @Test
    fun adaptiveEstimation_picksAppropriateFpsAndQualitySteps() {
        assertEquals(15, AnimatedStickerEncoder.chooseInitialVideoFps(2_000L))
        assertEquals(12, AnimatedStickerEncoder.chooseInitialVideoFps(5_000L))
        assertEquals(10, AnimatedStickerEncoder.chooseInitialVideoFps(9_000L))

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
}
