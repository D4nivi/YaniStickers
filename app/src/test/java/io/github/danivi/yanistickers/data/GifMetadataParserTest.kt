package io.github.danivi.yanistickers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class GifMetadataParserTest {

    @Test
    fun parseBytes_extractsDimensionsAndFrameDurations() {
        val gif = buildMinimalGif89a(
            width = 320,
            height = 240,
            delaysCentiseconds = listOf(5, 0, 12),
        )

        val meta = GifMetadataParser.parseBytes(gif)
        assertNotNull(meta)
        assertEquals(320, meta!!.width)
        assertEquals(240, meta.height)
        assertEquals(listOf(50, 100, 120), meta.frameDurationsMs)
        assertEquals(270L, meta.totalDurationMs)
    }

    @Test
    fun parseBytes_returnsNullForInvalidOrTruncatedData() {
        assertNull(GifMetadataParser.parseBytes("NOTAGIF".toByteArray()))
        assertNull(GifMetadataParser.parseBytes(ByteArray(8)))
    }

    private fun buildMinimalGif89a(
        width: Int,
        height: Int,
        delaysCentiseconds: List<Int>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Header GIF89a
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical Screen Descriptor (sin GCT)
        out.write(width and 0xFF)
        out.write((width ushr 8) and 0xFF)
        out.write(height and 0xFF)
        out.write((height ushr 8) and 0xFF)
        out.write(0x00) // packed
        out.write(0x00) // bg color
        out.write(0x00) // aspect ratio

        delaysCentiseconds.forEach { cs ->
            // Graphic Control Extension (0x21 0xF9)
            out.write(0x21)
            out.write(0xF9)
            out.write(0x04) // block size
            out.write(0x00) // packed
            out.write(cs and 0xFF)
            out.write((cs ushr 8) and 0xFF)
            out.write(0x00) // transparent index
            out.write(0x00) // terminator

            // Image Descriptor (0x2C) sin LCT y con 1 sub-bloque vacío
            out.write(0x2C)
            repeat(8) { out.write(0x00) } // left/top/w/h
            out.write(0x00) // packed
            out.write(0x02) // LZW min code size
            out.write(0x01) // sub-block len
            out.write(0x00) // data byte
            out.write(0x00) // sub-block terminator
        }
        out.write(0x3B) // Trailer
        return out.toByteArray()
    }
}
