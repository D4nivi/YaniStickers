package io.github.danivi.yanistickers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerFramingSnapTest {

    @Test
    fun applySnap_snapsToCenterAndReleasesOutsideThreshold() {
        // Imagen cuadrada 512x512 ligeramente desplazada dentro del umbral -> imanta al centro (0, 0)
        val snapped = StickerFramingSnap.applySnap(
            rawTransform = StickerFramingTransform(
                mode = FramingMode.FIT,
                zoom = 1.5f,
                normalizedPanX = 0.015f,
                normalizedPanY = -0.02f,
            ),
            srcWidth = 512,
            srcHeight = 512,
        )
        assertEquals(0f, snapped.transform.normalizedPanX, 0.0001f)
        assertEquals(0f, snapped.transform.normalizedPanY, 0.0001f)
        assertTrue(snapped.guides.centerX)
        assertTrue(snapped.guides.centerY)

        // Desplazamiento por encima del umbral -> se libera suavemente
        val released = StickerFramingSnap.applySnap(
            rawTransform = StickerFramingTransform(
                mode = FramingMode.FIT,
                zoom = 1.5f,
                normalizedPanX = 0.08f,
                normalizedPanY = -0.08f,
            ),
            srcWidth = 512,
            srcHeight = 512,
        )
        assertEquals(0.08f, released.transform.normalizedPanX, 0.0001f)
        assertEquals(-0.08f, released.transform.normalizedPanY, 0.0001f)
        assertFalse(released.guides.centerX)
        assertFalse(released.guides.centerY)
    }

    @Test
    fun applySnap_snapsToCanvasEdgesAndZoomTargets() {
        // Imagen panorámica 1000x500 en FIT -> altura normalizada 0.5, bordes en ±0.25
        val edgeSnap = StickerFramingSnap.applySnap(
            rawTransform = StickerFramingTransform(
                mode = FramingMode.FIT,
                zoom = 1.02f, // dentro del umbral de zoom 1.0x
                normalizedPanX = 0f,
                normalizedPanY = -0.24f, // cerca del borde superior (-0.25)
            ),
            srcWidth = 1000,
            srcHeight = 500,
        )
        assertEquals(1f, edgeSnap.transform.zoom, 0.0001f)
        assertTrue(edgeSnap.guides.zoomSnap)
        assertEquals(-0.25f, edgeSnap.transform.normalizedPanY, 0.0001f)
        assertTrue(edgeSnap.guides.topEdge)
        assertFalse(edgeSnap.guides.bottomEdge)

        // Zoom cerca del punto exacto de relleno (2.0x para 1000x500 en FIT)
        val fillZoomSnap = StickerFramingSnap.applySnap(
            rawTransform = StickerFramingTransform(
                mode = FramingMode.FIT,
                zoom = 1.96f,
            ),
            srcWidth = 1000,
            srcHeight = 500,
        )
        assertEquals(2f, fillZoomSnap.transform.zoom, 0.0001f)
        assertTrue(fillZoomSnap.guides.zoomSnap)
    }

    @Test
    fun applySnap_respectsOpaqueContentBoundsInEditMode() {
        // Sticker 512x512 con banda opaca 512x256 arriba (y=0..256)
        val bounds = OpaqueContentBounds(
            left = 0,
            top = 0,
            right = 512,
            bottom = 256,
            imageWidth = 512,
            imageHeight = 512,
        )
        // Al mover hacia abajo +0.24 (cerca de centrar la banda en Y = +0.25), imanta al centro Y
        val centered = StickerFramingSnap.applySnap(
            rawTransform = StickerFramingTransform(
                mode = FramingMode.FIT,
                zoom = 1f,
                normalizedPanX = 0f,
                normalizedPanY = 0.24f,
            ),
            srcWidth = 512,
            srcHeight = 512,
            contentBounds = bounds,
        )
        assertEquals(0.25f, centered.transform.normalizedPanY, 0.0001f)
        assertTrue(centered.guides.centerY)
    }
}
