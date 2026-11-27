package io.github.danivi.yanistickers.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImageContainerInspectorTest {

    @Test
    fun inspectBytes_parsesStaticVp8lWebp() {
        val bytes = TestImageBuilders.staticVp8lWebp(512, 512)
        val info = ImageContainerInspector.inspectBytes(bytes)

        assertNotNull(info)
        assertEquals(ImageContainerFormat.WEBP, info!!.format)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertFalse(info.isAnimated)
        assertEquals(1, info.frameCount)
    }

    @Test
    fun inspectBytes_parsesStaticVp8LossyWebp() {
        val bytes = TestImageBuilders.staticVp8LossyWebp(512, 512)
        val info = ImageContainerInspector.inspectBytes(bytes)

        assertNotNull(info)
        assertEquals(ImageContainerFormat.WEBP, info!!.format)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertFalse(info.isAnimated)
        assertEquals(1, info.frameCount)
    }

    @Test
    fun inspectBytes_parsesAnimatedWebpFrameDurations() {
        val bytes = TestImageBuilders.animatedWebp(512, 512, listOf(50, 100, 150))
        val info = ImageContainerInspector.inspectBytes(bytes)

        assertNotNull(info)
        assertEquals(ImageContainerFormat.WEBP, info!!.format)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertTrue(info.isAnimated)
        assertEquals(3, info.frameCount)
        assertEquals(listOf(50, 100, 150), info.frameDurationsMs)
        assertEquals(50, info.minFrameDurationMs)
        assertEquals(300L, info.totalDurationMs)
    }

    @Test
    fun inspectBytes_parsesPngDimensions() {
        val bytes = TestImageBuilders.png(96, 96)
        val info = ImageContainerInspector.inspectBytes(bytes)

        assertNotNull(info)
        assertEquals(ImageContainerFormat.PNG, info!!.format)
        assertEquals(96, info.width)
        assertEquals(96, info.height)
        assertFalse(info.isAnimated)
    }

    @Test
    fun inspectBytes_rejectsCorruptAndTruncatedHeaders() {
        assertNull(ImageContainerInspector.inspectBytes(ByteArray(0)))
        assertNull(ImageContainerInspector.inspectBytes("NOT_AN_IMAGE".toByteArray()))
        val truncated = TestImageBuilders.staticVp8lWebp(512, 512).copyOf(16)
        assertNull(ImageContainerInspector.inspectBytes(truncated))
    }
}

internal object TestImageBuilders {

    fun staticVp8lWebp(width: Int, height: Int, extraJunkBytes: Int = 0): ByteArray {
        val packed = ((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14)
        val vp8l = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x2F.toByte())
            .putInt(packed)
            .array()
        val chunks = mutableListOf("VP8L" to vp8l)
        if (extraJunkBytes > 0) {
            chunks.add("JUNK" to ByteArray(extraJunkBytes))
        }
        return wrapRiffWebp(chunks)
    }

    fun staticVp8LossyWebp(width: Int, height: Int): ByteArray {
        val vp8 = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x00.toByte()) // keyframe (bit 0 = 0)
            .put(0x00.toByte())
            .put(0x00.toByte())
            .put(0x9D.toByte())
            .put(0x01.toByte())
            .put(0x2A.toByte())
            .putShort((width and 0x3FFF).toShort())
            .putShort((height and 0x3FFF).toShort())
            .array()
        return wrapRiffWebp(listOf("VP8 " to vp8))
    }

    fun animatedWebp(
        width: Int,
        height: Int,
        frameDurationsMs: List<Int>,
        extraJunkBytes: Int = 0,
    ): ByteArray {
        val vp8x = ByteArray(10)
        vp8x[0] = 0x02 // flag ANIM
        writeUInt24LE(vp8x, 4, width - 1)
        writeUInt24LE(vp8x, 7, height - 1)

        val chunks = mutableListOf<Pair<String, ByteArray>>("VP8X" to vp8x)
        for (duration in frameDurationsMs) {
            val anmf = ByteArray(16)
            writeUInt24LE(anmf, 6, width - 1)
            writeUInt24LE(anmf, 9, height - 1)
            writeUInt24LE(anmf, 12, duration)
            chunks.add("ANMF" to anmf)
        }
        if (extraJunkBytes > 0) {
            chunks.add("JUNK" to ByteArray(extraJunkBytes))
        }
        return wrapRiffWebp(chunks)
    }

    fun png(width: Int, height: Int, extraPayloadSize: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(8.toByte())
            .put(6.toByte())
            .put(0.toByte())
            .put(0.toByte())
            .put(0.toByte())
            .array()
        out.write(ByteBuffer.allocate(4).putInt(13).array())
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.write(ihdr)
        out.write(ByteArray(4)) // CRC de marcador para inspección de cabecera
        if (extraPayloadSize > 0) {
            out.write(ByteBuffer.allocate(4).putInt(extraPayloadSize).array())
            out.write("tEXt".toByteArray(Charsets.US_ASCII))
            out.write(ByteArray(extraPayloadSize))
            out.write(ByteArray(4))
        }
        return out.toByteArray()
    }

    private fun wrapRiffWebp(chunks: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        for ((fourCc, payload) in chunks) {
            body.write(fourCc.toByteArray(Charsets.US_ASCII))
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array())
            body.write(payload)
            if ((payload.size and 1) == 1) {
                body.write(0)
            }
        }
        val bodyBytes = body.toByteArray()
        val full = ByteArrayOutputStream()
        full.write("RIFF".toByteArray(Charsets.US_ASCII))
        full.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bodyBytes.size).array())
        full.write(bodyBytes)
        return full.toByteArray()
    }

    private fun writeUInt24LE(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dest[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }
}
