package io.github.danivi.yanistickers.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.danivi.yanistickers.validation.ImageContainerFormat
import io.github.danivi.yanistickers.validation.ImageContainerInspector
import io.github.danivi.yanistickers.validation.PackValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AnimatedWebpNativeInstrumentedTest {

    @Test
    fun nativeWebpCodec_encodesAndDecodesTransparentAnimatedSticker() {
        val sizePx = PackValidator.STICKER_DIMENSION_PX
        val frameBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frameBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.MAGENTA)

        val encodedBytes = try {
            NativeWebpCodec.openEncoder(width = sizePx, height = sizePx).use { encoder ->
                colors.forEachIndexed { idx, color ->
                    frameBitmap.eraseColor(Color.TRANSPARENT)
                    paint.color = color
                    canvas.drawCircle(128f + idx * 64f, 256f, 80f, paint)
                    encoder.addFrame(
                        bitmap = frameBitmap,
                        durationMs = 100,
                        quality = 75f,
                        lossless = false,
                        method = 3,
                    )
                }
                encoder.finish()
            }
        } finally {
            frameBitmap.recycle()
        }

        val info = ImageContainerInspector.inspectBytes(encodedBytes)
        assertNotNull(info)
        assertEquals(ImageContainerFormat.WEBP, info!!.format)
        assertTrue(info.isAnimated)
        assertEquals(sizePx, info.width)
        assertEquals(sizePx, info.height)
        assertEquals(colors.size, info.frameCount)
        assertEquals(400L, info.totalDurationMs)
        assertTrue(info.fileSizeBytes <= PackValidator.MAX_ANIMATED_STICKER_BYTES)

        NativeWebpCodec.openDecoder(encodedBytes).use { decoder ->
            assertEquals(sizePx, decoder.width)
            assertEquals(sizePx, decoder.height)
            assertEquals(colors.size, decoder.frameCount)
            val decoded = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            try {
                var decodedCount = 0
                while (decoder.decodeNextFrame(decoded) != null) {
                    decodedCount++
                }
                assertEquals(colors.size, decodedCount)
            } finally {
                decoded.recycle()
            }
        }
    }

    @Test
    fun nativeWebpCodec_encodesDirectRgbaBufferQuickly() {
        val sizePx = PackValidator.STICKER_DIMENSION_PX
        val directRgba = ByteBuffer.allocateDirect(sizePx * sizePx * 4).order(ByteOrder.nativeOrder())
        val bytes = NativeWebpCodec.openEncoder(width = sizePx, height = sizePx, minimizeSize = false).use { encoder ->
            repeat(4) { frameIdx ->
                directRgba.clear()
                val r = ((frameIdx * 60) and 0xFF).toByte()
                val g = ((255 - frameIdx * 40) and 0xFF).toByte()
                repeat(sizePx * sizePx) {
                    directRgba.put(r)
                    directRgba.put(g)
                    directRgba.put(0x80.toByte())
                    directRgba.put(0xFF.toByte())
                }
                directRgba.rewind()
                encoder.addRgbaBufferFrame(directRgba, durationMs = 80, quality = 60f, method = 1)
            }
            encoder.finish()
        }

        val info = ImageContainerInspector.inspectBytes(bytes)
        assertNotNull(info)
        assertTrue(info!!.isAnimated)
        assertEquals(4, info.frameCount)
        assertEquals(320L, info.totalDurationMs)
    }
}
