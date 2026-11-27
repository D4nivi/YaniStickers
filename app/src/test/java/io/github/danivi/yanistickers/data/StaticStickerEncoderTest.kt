package io.github.danivi.yanistickers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StaticStickerEncoderTest {

    @Test
    fun computeBaseScale_respectsFitCropAnd90DegreeRotation() {
        // Imagen panorámica 1000x500 sobre lienzo 512x512
        val fitScale = StaticStickerEncoder.computeBaseScale(
            srcWidth = 1000,
            srcHeight = 500,
            canvasSizePx = 512f,
            mode = FramingMode.FIT,
            rotationDegrees = 0,
        )
        assertEquals(512f / 1000f, fitScale, 0.0001f)

        val cropScale = StaticStickerEncoder.computeBaseScale(
            srcWidth = 1000,
            srcHeight = 500,
            canvasSizePx = 512f,
            mode = FramingMode.CROP,
            rotationDegrees = 0,
        )
        assertEquals(512f / 500f, cropScale, 0.0001f)

        // Al rotar 90°, el ancho efectivo pasa a ser 500 y el alto 1000
        val rotatedFit = StaticStickerEncoder.computeBaseScale(
            srcWidth = 1000,
            srcHeight = 500,
            canvasSizePx = 512f,
            mode = FramingMode.FIT,
            rotationDegrees = 90,
        )
        assertEquals(512f / 1000f, rotatedFit, 0.0001f)
    }

    @Test
    fun selectBestEncodedPayload_prefersLosslessAndFallsBackToLossyWithinLimit() {
        // 1. Si lossless entra en el límite, se elige directamente sin probar lossy
        var lossyCalled = false
        val losslessWin = StaticStickerEncoder.selectBestEncodedPayload(
            maxBytes = 100 * 1024L,
            encodeLossless = { ByteArray(40 * 1024) },
            encodeLossy = {
                lossyCalled = true
                ByteArray(10 * 1024)
            },
        )
        assertTrue(losslessWin.usedLossless)
        assertEquals(100, losslessWin.quality)
        assertFalse(lossyCalled)

        // 2. Si lossless excede el límite, baja calidad lossy hasta encajar
        val triedQualities = mutableListOf<Int>()
        val lossyWin = StaticStickerEncoder.selectBestEncodedPayload(
            maxBytes = 100 * 1024L,
            encodeLossless = { ByteArray(140 * 1024) },
            encodeLossy = { q ->
                triedQualities += q
                if (q <= 70) ByteArray(85 * 1024) else ByteArray(110 * 1024)
            },
        )
        assertFalse(lossyWin.usedLossless)
        assertEquals(70, lossyWin.quality)
        assertEquals(listOf(92, 85, 78, 70), triedQualities)

        // 3. Si ni con calidad mínima cabe, lanza error descriptivo
        try {
            StaticStickerEncoder.selectBestEncodedPayload(
                maxBytes = 50 * 1024L,
                encodeLossless = { ByteArray(90 * 1024) },
                encodeLossy = { ByteArray(60 * 1024) },
            )
            fail("Debió fallar cuando ninguna calidad cumple el límite de tamaño.")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("50 KB"))
        }
    }

    @Test
    fun framingTransformNormalized_clampsZoomPanAndRotation() {
        val clamped = StickerFramingTransform(
            mode = FramingMode.CROP,
            zoom = 10f,
            normalizedPanX = -5f,
            normalizedPanY = 4f,
            rotationDegrees = 450,
        ).normalized()

        assertEquals(StickerFramingTransform.MAX_ZOOM, clamped.zoom, 0.0001f)
        assertEquals(-StickerFramingTransform.MAX_PAN_FRACTION, clamped.normalizedPanX, 0.0001f)
        assertEquals(StickerFramingTransform.MAX_PAN_FRACTION, clamped.normalizedPanY, 0.0001f)
        assertEquals(90, clamped.rotationDegrees)
    }
}
